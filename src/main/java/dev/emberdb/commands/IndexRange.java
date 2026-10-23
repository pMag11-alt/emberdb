package dev.emberdb.commands;

/** An inclusive index range {@code [from, to]} inside a collection. */
record IndexRange(int from, int to) {

    /**
     * Applies Redis's range rules (negative indexes count from the end, out-of-range ends are clamped) and returns
     * null when nothing is selected.
     */
    static IndexRange of(long start, long stop, int size) {
        if (start < 0) {
            start += size;
        }
        if (stop < 0) {
            stop += size;
        }
        if (start < 0) {
            start = 0;
        }
        if (start > stop || start >= size) {
            return null;
        }
        if (stop >= size) {
            stop = size - 1;
        }
        return new IndexRange((int) start, (int) stop);
    }

    int length() {
        return to - from + 1;
    }
}
