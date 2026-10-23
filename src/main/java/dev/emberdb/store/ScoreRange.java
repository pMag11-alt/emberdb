package dev.emberdb.store;

/** A score interval such as {@code [1, 5]}, {@code (1, +inf]} or {@code [-inf, 3)}. */
public record ScoreRange(double min, boolean minExclusive, double max, boolean maxExclusive) {

    public boolean aboveMin(double score) {
        return minExclusive ? score > min : score >= min;
    }

    public boolean belowMax(double score) {
        return maxExclusive ? score < max : score <= max;
    }

    public boolean isEmpty() {
        return min > max || (min == max && (minExclusive || maxExclusive));
    }
}
