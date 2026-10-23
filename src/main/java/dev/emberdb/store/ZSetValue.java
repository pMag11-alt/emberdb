package dev.emberdb.store;

import java.util.HashMap;

/**
 * The sorted-set type. A hash map gives O(1) member → score lookups (ZSCORE) and a skip list keeps members
 * ordered for ranks and ranges, the same pairing Redis uses.
 */
public final class ZSetValue {

    private final HashMap<String, Double> scores = new HashMap<>();
    private final SkipList ordered = new SkipList();

    public int size() {
        return scores.size();
    }

    public Double score(String member) {
        return scores.get(member);
    }

    /** Adds the member or moves it to a new score. Returns true if the member is new. */
    public boolean put(String member, double score) {
        Double old = scores.put(member, score);
        if (old != null) {
            if (old == score) {
                return false;
            }
            ordered.delete(old, member);
        }
        ordered.insert(score, member);
        return old == null;
    }

    public boolean remove(String member) {
        Double old = scores.remove(member);
        if (old == null) {
            return false;
        }
        ordered.delete(old, member);
        return true;
    }

    /** 0-based rank in ascending order, or -1 if the member is absent. */
    public int rank(String member) {
        Double score = scores.get(member);
        return score == null ? -1 : ordered.rank(score, member);
    }

    public SkipList ordered() {
        return ordered;
    }
}
