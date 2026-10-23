package dev.emberdb.engine;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

import dev.emberdb.Config;
import dev.emberdb.Log;
import dev.emberdb.commands.Commands;
import dev.emberdb.engine.Command.Flag;
import dev.emberdb.persistence.AofLoader;
import dev.emberdb.persistence.AofRewriter;
import dev.emberdb.persistence.AppendOnlyFile;
import dev.emberdb.protocol.Reply;
import dev.emberdb.store.Database;
import dev.emberdb.store.WrongTypeException;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Executes commands. This is the server minus the network: tests drive it directly.
 * <p>
 * <b>Concurrency model.</b> Each connection has its own virtual thread for socket I/O, but commands execute one at
 * a time under a single lock, as on Redis's main thread. Every command is therefore atomic, and the data
 * structures need no locking of their own. Pub/sub runs outside the lock because it only touches its own
 * concurrent maps and other connections' sockets.
 */
public final class Engine implements Closeable, Database.Listener {

    public static final String VERSION = "1.0.0";
    /** The Redis version reported to clients, for libraries that check which features to use. */
    public static final String REDIS_COMPAT_VERSION = "7.2.0";

    private static final ReplySink NO_REPLIES = new ReplySink() {
        @Override
        public void write(Reply reply, int protocol) {}

        @Override
        public void flush() {}
    };

    private final Config config;
    private final RandomGenerator random;
    private final Database db;
    private final CommandTable commands;
    private final PubSub pubsub = new PubSub();
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final LongAdder commandsProcessed = new LongAdder();
    private final AtomicLong connectionsReceived = new AtomicLong();
    private final long startedAt;

    /** Key → sessions watching it. Guarded by {@link #lock}. */
    private final HashMap<String, Set<Session>> watchers = new HashMap<>();
    /** Commands waiting to be appended to the AOF when the current command finishes. Guarded by {@link #lock}. */
    private final List<List<String>> propagation = new ArrayList<>();

    private AppendOnlyFile aof;
    private boolean loading;
    private ScheduledExecutorService cron;
    private long cronTicks;
    private volatile Runnable shutdownHandler = () -> {};

    public Engine(Config config) {
        this(config, System::currentTimeMillis, new SplittableRandom());
    }

    public Engine(Config config, LongSupplier clock, RandomGenerator random) {
        this.config = config;
        this.random = random;
        this.db = new Database(clock, random, config.maxKeys());
        this.db.setListener(this);
        this.commands = Commands.table();
        this.startedAt = clock.getAsLong();
    }

    /** Replays the append-only file, if enabled, and starts the background jobs. */
    public void start() throws IOException {
        if (config.appendOnly()) {
            Path path = config.aofPath();
            if (Files.exists(path)) {
                loadAof(path);
            }
            aof = AppendOnlyFile.open(path, config.appendFsync());
        }
        cron = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("ember-cron").daemon().factory());
        cron.scheduleAtFixedRate(this::cronTick, 100, 100, MILLISECONDS);
    }

    // ---- sessions ----------------------------------------------------------------------------------------------

    public Session connect(ReplySink sink, String address) {
        Session session = new Session(sink, address, db.now(), config.requirePass() == null);
        sessions.add(session);
        connectionsReceived.incrementAndGet();
        return session;
    }

    public void disconnect(Session session) {
        pubsub.unsubscribeAll(session);
        lock.lock();
        try {
            unwatchAll(session);
        } finally {
            lock.unlock();
        }
        sessions.remove(session);
    }

    // ---- dispatch ----------------------------------------------------------------------------------------------

    /** Runs one client command and sends its reply (or replies) through the session. */
    public void dispatch(Session session, List<String> argv) {
        if (argv.isEmpty()) {
            return;
        }
        String name = argv.get(0).toLowerCase(Locale.ROOT);
        session.setLastCommand(name);
        Command command = commands.get(name);
        if (command == null) {
            reject(session, unknownCommand(argv));
            return;
        }
        if (!command.acceptsArgCount(argv.size())) {
            reject(session, "ERR wrong number of arguments for '" + name + "' command");
            return;
        }
        if (!session.isAuthenticated() && !command.has(Flag.NO_AUTH)) {
            reject(session, "NOAUTH Authentication required.");
            return;
        }
        // RESP2 has no way to tell a reply from a pushed message, so a subscribed RESP2 connection is limited to
        // pub/sub commands. RESP3 marks messages as pushes, so any command is allowed.
        if (session.isSubscribed() && session.protocol() == 2 && !command.has(Flag.PUBSUB_CONTEXT)) {
            reject(session, "ERR Can't execute '" + name + "': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT"
                    + " / RESET are allowed in this context");
            return;
        }
        if (session.inMulti() && !command.has(Flag.TRANSACTION)) {
            if (command.has(Flag.NO_MULTI)) {
                reject(session, "ERR Command not allowed inside a transaction");
                return;
            }
            session.enqueue(command, argv);
            session.send(Reply.QUEUED);
            return;
        }
        Reply reply;
        if (command.has(Flag.NO_LOCK)) {
            reply = run(session, command, argv);
        } else {
            lock.lock();
            try {
                reply = run(session, command, argv);
                writePropagated();
            } finally {
                lock.unlock();
            }
        }
        if (reply != null) {
            session.send(reply);
        }
    }

    /** Rejecting a command while in MULTI makes the following EXEC fail, as in Redis. */
    private static void reject(Session session, String message) {
        session.markQueueFailed();
        session.send(Reply.error(message));
    }

    private Reply run(Session session, Command command, List<String> argv) {
        CommandContext ctx = new CommandContext(this, session, argv);
        boolean write = command.has(Flag.WRITE);
        long dirtyBefore = write ? db.dirty() : 0;
        Reply reply;
        try {
            reply = command.handler().handle(ctx);
        } catch (CommandException | WrongTypeException e) {
            reply = Reply.error(e.getMessage());
        }
        commandsProcessed.increment();
        if (write && !ctx.propagated() && db.dirty() != dirtyBefore) {
            propagate(argv);
        }
        return reply;
    }

    private static String unknownCommand(List<String> argv) {
        StringBuilder message = new StringBuilder("ERR unknown command '").append(argv.get(0))
                .append("', with args beginning with: ");
        for (String arg : argv.subList(1, Math.min(argv.size(), 4))) {
            message.append('\'').append(arg.length() > 32 ? arg.substring(0, 32) : arg).append("' ");
        }
        return message.toString();
    }

    // ---- append-only file --------------------------------------------------------------------------------------

    void propagate(List<String> command) {
        if (aof != null && !loading) {
            propagation.add(command);
        }
    }

    private void writePropagated() {
        if (propagation.isEmpty()) {
            return;
        }
        for (List<String> command : propagation) {
            aof.append(command);
        }
        propagation.clear();
        if (aof.policy() == AppendOnlyFile.Fsync.ALWAYS) {
            try {
                aof.flush(true);
            } catch (IOException e) {
                Log.warn("Error writing to the append-only file: %s", e.getMessage());
            }
        }
    }

    private void loadAof(Path path) throws IOException {
        long started = System.nanoTime();
        Session loader = new Session(NO_REPLIES, "aof", 0, true);
        loading = true;
        try {
            AofLoader.Result result = AofLoader.load(path, argv -> {
                Command command = commands.get(argv.get(0).toLowerCase(Locale.ROOT));
                if (command == null || !command.acceptsArgCount(argv.size())) {
                    throw new IOException("unknown or malformed command in append-only file: " + argv.get(0));
                }
                if (run(loader, command, argv) instanceof Reply.Err error) {
                    Log.warn("Replaying %s from the append-only file failed: %s", argv.get(0), error.message());
                }
            });
            if (result.truncated()) {
                Log.warn("The append-only file ended with an incomplete command; truncated it to %d bytes",
                        result.validBytes());
            }
            Log.info("DB loaded from append only file: %d commands, %d keys, %.3f seconds", result.commands(),
                    db.size(), (System.nanoTime() - started) / 1e9);
        } finally {
            loading = false;
        }
    }

    /** BGREWRITEAOF. Runs with the command lock held, so the snapshot is consistent. */
    public void rewriteAof() throws IOException {
        if (aof == null) {
            throw new CommandException("ERR Append only file is disabled; start the server with --appendonly yes");
        }
        long before = aof.size();
        aof.rewrite(out -> AofRewriter.writeSnapshot(db, out));
        Log.info("Append only file rewritten: %d bytes -> %d bytes", before, aof.size());
    }

    public AppendOnlyFile aof() {
        return aof;
    }

    // ---- transactions ------------------------------------------------------------------------------------------

    public Reply multi(Session session) {
        if (session.inMulti()) {
            throw new CommandException("ERR MULTI calls can not be nested");
        }
        session.beginMulti();
        return Reply.OK;
    }

    /**
     * Runs the queued commands back to back while holding the lock, so no other client's command can run in between.
     * In the AOF they are wrapped in MULTI/EXEC so a crash cannot replay half of them.
     */
    public Reply exec(Session session) {
        if (!session.inMulti()) {
            throw new CommandException("ERR EXEC without MULTI");
        }
        boolean failed = session.queueFailed();
        List<Session.QueuedCommand> queued = session.endMulti();
        boolean watchedKeyModified = session.watchedKeyModified;
        unwatchAll(session);
        if (failed) {
            return Reply.error("EXECABORT Transaction discarded because of previous errors.");
        }
        if (watchedKeyModified) {
            return Reply.NIL_ARRAY;
        }
        int mark = propagation.size();
        List<Reply> replies = new ArrayList<>(queued.size());
        for (Session.QueuedCommand q : queued) {
            Reply reply = run(session, q.command(), q.argv());
            replies.add(reply == null ? Reply.NIL : reply);
        }
        if (propagation.size() > mark) {
            propagation.add(mark, List.of("MULTI"));
            propagation.add(List.of("EXEC"));
        }
        return Reply.array(replies);
    }

    public Reply discard(Session session) {
        if (!session.inMulti()) {
            throw new CommandException("ERR DISCARD without MULTI");
        }
        session.endMulti();
        unwatchAll(session);
        return Reply.OK;
    }

    /** WATCH: EXEC will fail if any of these keys changes before it runs (optimistic locking). */
    public Reply watch(Session session, List<String> keys) {
        if (session.inMulti()) {
            throw new CommandException("ERR WATCH inside MULTI is not allowed");
        }
        for (String key : keys) {
            if (session.watchedKeys.add(key)) {
                watchers.computeIfAbsent(key, k -> new HashSet<>()).add(session);
            }
        }
        return Reply.OK;
    }

    public Reply unwatch(Session session) {
        unwatchAll(session);
        return Reply.OK;
    }

    private void unwatchAll(Session session) {
        for (String key : session.watchedKeys) {
            Set<Session> set = watchers.get(key);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    watchers.remove(key);
                }
            }
        }
        session.watchedKeys.clear();
        session.watchedKeyModified = false;
    }

    // ---- Database.Listener -------------------------------------------------------------------------------------

    @Override
    public void keyModified(String key) {
        if (watchers.isEmpty()) {
            return;
        }
        Set<Session> set = watchers.get(key);
        if (set != null) {
            for (Session session : set) {
                session.watchedKeyModified = true;
            }
        }
    }

    @Override
    public void keyEvicted(String key) {
        // Log the eviction so that replaying the file ends with the same keys.
        propagate(List.of("DEL", key));
    }

    @Override
    public void flushed() {
        for (Set<Session> set : watchers.values()) {
            for (Session session : set) {
                session.watchedKeyModified = true;
            }
        }
    }

    // ---- background jobs ---------------------------------------------------------------------------------------

    /** Ten times a second: expire keys. Once a second: write (and maybe fsync) the AOF buffer. */
    private void cronTick() {
        try {
            lock.lock();
            try {
                db.activeExpireCycle();
            } finally {
                lock.unlock();
            }
            if (aof != null && ++cronTicks % 10 == 0) {
                aof.periodicFlush();
            }
        } catch (Throwable t) {
            // An exception would silently cancel the scheduled task, so log it and keep going.
            Log.warn("Background job failed: %s", t);
        }
    }

    /** Runs the given action under the command lock. Used by tests and by SHUTDOWN. */
    public void withLock(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public void setShutdownHandler(Runnable handler) {
        this.shutdownHandler = handler;
    }

    /** SHUTDOWN: flushes the AOF, then asks the server to stop. */
    public void requestShutdown() {
        Log.info("User requested shutdown...");
        Thread.ofPlatform().name("ember-shutdown").start(shutdownHandler);
    }

    @Override
    public void close() throws IOException {
        if (cron != null) {
            cron.shutdownNow();
        }
        lock.lock();
        try {
            if (aof != null) {
                aof.close();
                aof = null;
            }
        } finally {
            lock.unlock();
        }
    }

    // ---- accessors for INFO, CLIENT and friends ----------------------------------------------------------------

    public Config config() {
        return config;
    }

    public Database db() {
        return db;
    }

    /** Randomness for SPOP and SRANDMEMBER. Only use it while holding the command lock. */
    public RandomGenerator random() {
        return random;
    }

    public CommandTable commands() {
        return commands;
    }

    public PubSub pubsub() {
        return pubsub;
    }

    public Set<Session> sessions() {
        return sessions;
    }

    public long commandsProcessed() {
        return commandsProcessed.sum();
    }

    public long connectionsReceived() {
        return connectionsReceived.get();
    }

    public long startedAt() {
        return startedAt;
    }
}
