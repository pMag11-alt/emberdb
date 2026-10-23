package dev.emberdb.store;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * The list type: a growable ring buffer. Pushing and popping at either end is O(1) amortised, and so is reading
 * by index, which a linked list cannot do and {@link java.util.ArrayDeque} does not offer.
 * The capacity is always a power of two so that wrapping an index is a bit mask.
 */
public final class ListValue implements Iterable<String> {

    private String[] items = new String[8];
    private int head;
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void addFirst(String value) {
        ensureCapacity(size + 1);
        head = (head - 1) & mask();
        items[head] = value;
        size++;
    }

    public void addLast(String value) {
        ensureCapacity(size + 1);
        items[(head + size) & mask()] = value;
        size++;
    }

    public String removeFirst() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        String value = items[head];
        items[head] = null;
        head = (head + 1) & mask();
        size--;
        return value;
    }

    public String removeLast() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        int last = (head + size - 1) & mask();
        String value = items[last];
        items[last] = null;
        size--;
        return value;
    }

    public String get(int index) {
        return items[slot(index)];
    }

    public void set(int index, String value) {
        items[slot(index)] = value;
    }

    /** Keeps only the elements in {@code [start, stop]} (both inclusive, already clamped to the list). */
    public void retain(int start, int stop) {
        String[] kept = new String[Math.max(8, Integer.highestOneBit(Math.max(1, stop - start + 1)) << 1)];
        int n = 0;
        for (int i = start; i <= stop; i++) {
            kept[n++] = get(i);
        }
        items = kept;
        head = 0;
        size = n;
    }

    /**
     * Removes up to {@code |count|} elements equal to {@code value}: from the head when count is positive, from the
     * tail when negative, and all of them when zero. Returns how many were removed.
     */
    public int remove(String value, long count) {
        String[] kept = new String[items.length];
        long limit = count == 0 ? Long.MAX_VALUE : Math.abs(count);
        int removed = 0;
        int n = 0;
        if (count >= 0) {
            for (int i = 0; i < size; i++) {
                String item = get(i);
                if (removed < limit && item.equals(value)) {
                    removed++;
                } else {
                    kept[n++] = item;
                }
            }
        } else {
            // Walk backwards, filling the new array from the end so the order is kept.
            int write = size;
            for (int i = size - 1; i >= 0; i--) {
                String item = get(i);
                if (removed < limit && item.equals(value)) {
                    removed++;
                } else {
                    kept[--write] = item;
                }
            }
            n = size - removed;
            System.arraycopy(kept, write, kept, 0, n);
            Arrays.fill(kept, n, size, null);
        }
        items = kept;
        head = 0;
        size = n;
        return removed;
    }

    @Override
    public Iterator<String> iterator() {
        return new Iterator<>() {
            private int i;

            @Override
            public boolean hasNext() {
                return i < size;
            }

            @Override
            public String next() {
                if (i >= size) {
                    throw new NoSuchElementException();
                }
                return get(i++);
            }
        };
    }

    private int slot(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        return (head + index) & mask();
    }

    private int mask() {
        return items.length - 1;
    }

    private void ensureCapacity(int needed) {
        if (needed <= items.length) {
            return;
        }
        String[] grown = new String[items.length << 1];
        for (int i = 0; i < size; i++) {
            grown[i] = items[(head + i) & mask()];
        }
        items = grown;
        head = 0;
    }
}
