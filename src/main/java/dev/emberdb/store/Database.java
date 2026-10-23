package dev.emberdb.store;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

import dev.emberdb.util.Glob;

/**
 * The keyspace. Not thread-safe: the engine only touches it while holding its command lock.
 * <p>
 * Values are {@link String} (the string type), {@link ListValue}, {@link HashValue}, {@link SetValue} or
 * {@link ZSetValue}. The map is a {@link LinkedHashMap} in <em>access order</em>, so its first entry is always the
 * least recently used key. With {@code maxKeys} set, that makes LRU eviction O(1).
 * <p>
 * Keys with a TTL expire in two ways, as in Redis: lazily, when a command looks them up, and actively, through
 * {@link #activeExpireCycle()}, which the engine runs ten times a second.
 */
public final class Database {

    /** Receives keyspace events: WATCH needs to know about modifications, the AOF about evictions. */
    public interface Listener {
        void keyModified(String key);

        void keyEvicted(String key);

        void flushed();
    }

    private static final Listener NO_LISTENER = new Listener() {
        @Override
        public void keyModified(String key) {}

        @Override
        public void keyEvicted(String key) {}

        @Override
        public void flushed() {}
    };

    private static final int EXPIRE_SAMPLE_SIZE = 20;
    private static final int EXPIRE_MAX_ROUNDS = 16;

    private final LinkedHashMap<String, Object> dict = new LinkedHashMap<>(64, 0.75f, true);
    private final ExpiryIndex expires = new ExpiryIndex();
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final int maxKeys;
    private Listener listener = NO_LISTENER;

    private long dirty;
    private long hits;
    private long misses;
    private long expiredKeys;
    private long evictedKeys;

    /**
     * @param clock   current time in epoch milliseconds
     * @param maxKeys evict the least recently used key once this many exist; 0 for no limit
     */
    public Database(LongSupplier clock, RandomGenerator random, int maxKeys) {
        this.clock = clock;
        this.random = random;
        this.maxKeys = maxKeys;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public long now() {
        return clock.getAsLong();
    }

    // ---- reads -------------------------------------------------------------------------------------------------

    /** The value at a key, or null. Expired keys are deleted on the way. Counts as a keyspace hit or miss. */
    public Object get(String key) {
        expireIfNeeded(key);
        Object value = dict.get(key);
        if (value == null) {
            misses++;
        } else {
            hits++;
        }
        return value;
    }

    public boolean exists(String key) {
        expireIfNeeded(key);
        return dict.containsKey(key);
    }

    public String getString(String key) {
        return typed(get(key), String.class);
    }

    public ListValue getList(String key) {
        return typed(get(key), ListValue.class);
    }

    public HashValue getHash(String key) {
        return typed(get(key), HashValue.class);
    }

    public SetValue getSet(String key) {
        return typed(get(key), SetValue.class);
    }

    public ZSetValue getZSet(String key) {
        return typed(get(key), ZSetValue.class);
    }

    public ListValue getOrCreateList(String key) {
        return getOrCreate(key, ListValue.class, k -> new ListValue());
    }

    public HashValue getOrCreateHash(String key) {
        return getOrCreate(key, HashValue.class, k -> new HashValue());
    }

    public SetValue getOrCreateSet(String key) {
        return getOrCreate(key, SetValue.class, k -> new SetValue());
    }

    public ZSetValue getOrCreateZSet(String key) {
        return getOrCreate(key, ZSetValue.class, k -> new ZSetValue());
    }

    /** "string", "list", "hash", "set", "zset" or "none". */
    public String type(String key) {
        expireIfNeeded(key);
        return typeName(dict.get(key));
    }

    public static String typeName(Object value) {
        return switch (value) {
            case null -> "none";
            case String s -> "string";
            case ListValue l -> "list";
            case HashValue h -> "hash";
            case SetValue s -> "set";
            case ZSetValue z -> "zset";
            default -> throw new IllegalStateException("unknown value " + value.getClass());
        };
    }

    public int size() {
        return dict.size();
    }

    public int expiresCount() {
        return expires.size();
    }

    /** Keys matching a glob pattern, skipping keys that have expired but not been deleted yet. */
    public List<String> keys(String pattern) {
        long now = now();
        boolean all = pattern.equals("*");
        List<String> result = new ArrayList<>();
        for (String key : dict.keySet()) {
            long deadline = expires.deadline(key);
            if ((deadline < 0 || deadline > now) && (all || Glob.matches(pattern, key))) {
                result.add(key);
            }
        }
        return result;
    }

    /** Iterates over every entry without changing the LRU order. Used to rewrite the append-only file. */
    public Iterable<Map.Entry<String, Object>> entries() {
        return dict.entrySet();
    }

    // ---- writes ------------------------------------------------------------------------------------------------

    /** Stores a value and clears any TTL, like SET. */
    public void set(String key, Object value) {
        expireIfNeeded(key);
        if (!dict.containsKey(key)) {
            makeRoomForNewKey();
        }
        dict.put(key, value);
        expires.remove(key);
        touch(key);
    }

    /** Stores a value but keeps the key's TTL, like INCR or SET ... KEEPTTL. */
    public void setKeepTtl(String key, Object value) {
        expireIfNeeded(key);
        if (!dict.containsKey(key)) {
            makeRoomForNewKey();
        }
        dict.put(key, value);
        touch(key);
    }

    public boolean delete(String key) {
        expireIfNeeded(key);
        if (dict.remove(key) == null) {
            return false;
        }
        expires.remove(key);
        touch(key);
        return true;
    }

    /**
     * Call after changing a list, hash, set or sorted set in place. Notifies watchers and deletes the key if the
     * collection is now empty, since Redis never keeps empty collections.
     */
    public void modified(String key) {
        Object value = dict.get(key);
        boolean empty = switch (value) {
            case ListValue l -> l.isEmpty();
            case HashValue h -> h.isEmpty();
            case SetValue s -> s.isEmpty();
            case ZSetValue z -> z.size() == 0;
            case null, default -> false;
        };
        if (empty) {
            dict.remove(key);
            expires.remove(key);
        }
        touch(key);
    }

    /** Moves a value, with its TTL, to a new key, replacing whatever was there. */
    public void rename(String from, String to) {
        long deadline = expires.deadline(from);
        Object value = dict.remove(from);
        expires.remove(from);
        touch(from);
        dict.remove(to);
        expires.remove(to);
        dict.put(to, value);
        if (deadline >= 0) {
            expires.put(to, deadline);
        }
        touch(to);
    }

    public void flush() {
        dict.clear();
        expires.clear();
        dirty++;
        listener.flushed();
    }

    // ---- expiry ------------------------------------------------------------------------------------------------

    /** Like {@link #getExpire} but never deletes anything, so it is safe while iterating {@link #entries()}. */
    public long deadlineOf(String key) {
        return expires.deadline(key);
    }

    /** Deadline in epoch milliseconds, or -1 if the key has no TTL (or does not exist). */
    public long getExpire(String key) {
        expireIfNeeded(key);
        return expires.deadline(key);
    }

    /** Sets an absolute deadline on an existing key. A deadline in the past deletes the key at once. */
    public void setExpire(String key, long deadline) {
        if (deadline <= now()) {
            delete(key);
            return;
        }
        expires.put(key, deadline);
        touch(key);
    }

    public boolean persist(String key) {
        expireIfNeeded(key);
        if (!expires.remove(key)) {
            return false;
        }
        touch(key);
        return true;
    }

    /**
     * One round of active expiry, as in Redis: sample up to 20 random keys that have a TTL and delete the expired
     * ones, repeating while more than a quarter of the sample had expired. Returns how many keys were deleted.
     */
    public int activeExpireCycle() {
        long now = now();
        int deleted = 0;
        for (int round = 0; round < EXPIRE_MAX_ROUNDS && expires.size() > 0; round++) {
            int sample = Math.min(EXPIRE_SAMPLE_SIZE, expires.size());
            int expired = 0;
            for (int i = 0; i < sample && expires.size() > 0; i++) {
                int slot = random.nextInt(expires.size());
                if (expires.deadlineAt(slot) <= now) {
                    removeExpired(expires.keyAt(slot));
                    expired++;
                }
            }
            deleted += expired;
            if (expired * 4 <= sample) {
                break;
            }
        }
        return deleted;
    }

    private void expireIfNeeded(String key) {
        long deadline = expires.deadline(key);
        if (deadline >= 0 && deadline <= now()) {
            removeExpired(key);
        }
    }

    private void removeExpired(String key) {
        dict.remove(key);
        expires.remove(key);
        expiredKeys++;
        // Expiry is not a client write, so it does not count towards the AOF dirty counter, but WATCH must see it.
        listener.keyModified(key);
    }

    // ---- stats -------------------------------------------------------------------------------------------------

    /** Incremented on every change. The engine compares it before and after a command to decide what to log. */
    public long dirty() {
        return dirty;
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public long expiredKeys() {
        return expiredKeys;
    }

    public long evictedKeys() {
        return evictedKeys;
    }

    // ---- internals ---------------------------------------------------------------------------------------------

    private void touch(String key) {
        dirty++;
        listener.keyModified(key);
    }

    private void makeRoomForNewKey() {
        if (maxKeys <= 0) {
            return;
        }
        Iterator<String> lru = dict.keySet().iterator();
        while (dict.size() >= maxKeys && lru.hasNext()) {
            String victim = lru.next();
            lru.remove();
            expires.remove(victim);
            evictedKeys++;
            listener.keyModified(victim);
            listener.keyEvicted(victim);
        }
    }

    private <T> T getOrCreate(String key, Class<T> type, Function<String, T> factory) {
        T existing = typed(get(key), type);
        if (existing != null) {
            return existing;
        }
        makeRoomForNewKey();
        T created = factory.apply(key);
        dict.put(key, created);
        return created;
    }

    private static <T> T typed(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new WrongTypeException();
        }
        return type.cast(value);
    }
}
