package dev.emberdb.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Deadlines of the keys that have a TTL. Keys live in a dense array plus a key → slot map, so active expiry can
 * pick a uniformly random key in O(1), and removal is O(1) by moving the last entry into the freed slot.
 */
public final class ExpiryIndex {

    private final HashMap<String, Integer> slots = new HashMap<>();
    private final ArrayList<String> keys = new ArrayList<>();
    private long[] deadlines = new long[16];

    public int size() {
        return keys.size();
    }

    /** The key's deadline in epoch milliseconds, or -1 if it has none. */
    public long deadline(String key) {
        Integer slot = slots.get(key);
        return slot == null ? -1 : deadlines[slot];
    }

    public void put(String key, long deadline) {
        Integer slot = slots.get(key);
        if (slot == null) {
            slot = keys.size();
            keys.add(key);
            slots.put(key, slot);
            if (slot == deadlines.length) {
                deadlines = Arrays.copyOf(deadlines, deadlines.length * 2);
            }
        }
        deadlines[slot] = deadline;
    }

    public boolean remove(String key) {
        Integer slot = slots.remove(key);
        if (slot == null) {
            return false;
        }
        int last = keys.size() - 1;
        if (slot != last) {
            String moved = keys.get(last);
            keys.set(slot, moved);
            deadlines[slot] = deadlines[last];
            slots.put(moved, slot);
        }
        keys.remove(last);
        return true;
    }

    public String keyAt(int slot) {
        return keys.get(slot);
    }

    public long deadlineAt(int slot) {
        return deadlines[slot];
    }

    public void clear() {
        slots.clear();
        keys.clear();
        deadlines = new long[16];
    }
}
