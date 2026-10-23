package dev.emberdb.store;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * The ordered half of a sorted set: a skip list sorted by (score, member), following the design in Redis's
 * {@code t_zset.c}.
 * <p>
 * Every forward link also stores its <em>span</em>, the number of level-0 nodes it jumps over. Adding up spans on
 * the way down gives a node's rank in O(log n), and walking down by rank finds the n-th node in O(log n). That is
 * what makes ZRANK and ZRANGE by index fast. Without spans both would be O(n).
 * <p>
 * Nodes are promoted to the next level with probability 1/4, so on average a node has 1.33 forward links.
 */
public final class SkipList {

    static final int MAX_LEVEL = 32;
    private static final double PROMOTION_PROBABILITY = 0.25;

    public static final class Node {
        private final String member;
        private final double score;
        private final Node[] next;
        private final int[] span;
        private Node prev;

        private Node(int level, double score, String member) {
            this.member = member;
            this.score = score;
            this.next = new Node[level];
            this.span = new int[level];
        }

        public String member() {
            return member;
        }

        public double score() {
            return score;
        }

        /** The next node in ascending order, or null. */
        public Node next() {
            return next[0];
        }

        /** The previous node in ascending order, or null. */
        public Node prev() {
            return prev;
        }
    }

    private final Node header = new Node(MAX_LEVEL, 0, null);
    private final RandomGenerator random;
    private Node tail;
    private int level = 1;
    private int length;

    public SkipList() {
        this(new SplittableRandom());
    }

    public SkipList(RandomGenerator random) {
        this.random = random;
    }

    public int size() {
        return length;
    }

    public Node first() {
        return header.next[0];
    }

    public Node last() {
        return tail;
    }

    /** Inserts a member that is not already present. */
    public Node insert(double score, String member) {
        Node[] update = new Node[MAX_LEVEL];
        int[] rank = new int[MAX_LEVEL];
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = i == level - 1 ? 0 : rank[i + 1];
            while (x.next[i] != null && precedes(x.next[i], score, member)) {
                rank[i] += x.span[i];
                x = x.next[i];
            }
            update[i] = x;
        }
        int nodeLevel = randomLevel();
        if (nodeLevel > level) {
            for (int i = level; i < nodeLevel; i++) {
                rank[i] = 0;
                update[i] = header;
                header.span[i] = length;
            }
            level = nodeLevel;
        }
        x = new Node(nodeLevel, score, member);
        for (int i = 0; i < nodeLevel; i++) {
            x.next[i] = update[i].next[i];
            update[i].next[i] = x;
            // rank[0] - rank[i] is how far update[i] is behind the insertion point.
            x.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = rank[0] - rank[i] + 1;
        }
        for (int i = nodeLevel; i < level; i++) {
            update[i].span[i]++;
        }
        x.prev = update[0] == header ? null : update[0];
        if (x.next[0] != null) {
            x.next[0].prev = x;
        } else {
            tail = x;
        }
        length++;
        return x;
    }

    /** Removes the node with exactly this score and member. Returns false if there is none. */
    public boolean delete(double score, String member) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && precedes(x.next[i], score, member)) {
                x = x.next[i];
            }
            update[i] = x;
        }
        x = x.next[0];
        if (x == null || x.score != score || !x.member.equals(member)) {
            return false;
        }
        for (int i = 0; i < level; i++) {
            if (update[i].next[i] == x) {
                update[i].span[i] += x.span[i] - 1;
                update[i].next[i] = x.next[i];
            } else {
                update[i].span[i]--;
            }
        }
        if (x.next[0] != null) {
            x.next[0].prev = x.prev;
        } else {
            tail = x.prev;
        }
        while (level > 1 && header.next[level - 1] == null) {
            level--;
        }
        length--;
        return true;
    }

    /** 0-based rank of the node with this score and member, or -1 if it is not present. */
    public int rank(double score, String member) {
        int rank = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null
                    && (precedes(x.next[i], score, member) || (x.next[i].score == score && x.next[i].member.equals(member)))) {
                rank += x.span[i];
                x = x.next[i];
            }
            if (x != header && x.member.equals(member)) {
                return rank - 1;
            }
        }
        return -1;
    }

    /** The node at a 0-based rank, or null if out of range. */
    public Node byRank(int index) {
        if (index < 0 || index >= length) {
            return null;
        }
        int target = index + 1;
        int traversed = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && traversed + x.span[i] <= target) {
                traversed += x.span[i];
                x = x.next[i];
            }
            if (traversed == target) {
                return x;
            }
        }
        return null;
    }

    /** The lowest node whose score is in the range, or null. */
    public Node firstInRange(ScoreRange range) {
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && !range.aboveMin(x.next[i].score)) {
                x = x.next[i];
            }
        }
        x = x.next[0];
        return x != null && range.belowMax(x.score) ? x : null;
    }

    /** The highest node whose score is in the range, or null. */
    public Node lastInRange(ScoreRange range) {
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.next[i] != null && range.belowMax(x.next[i].score)) {
                x = x.next[i];
            }
        }
        return x != header && range.aboveMin(x.score) ? x : null;
    }

    /** Ascending order: by score, then by member bytes (Latin-1 chars compare like bytes). */
    private static boolean precedes(Node node, double score, String member) {
        return node.score < score || (node.score == score && node.member.compareTo(member) < 0);
    }

    private int randomLevel() {
        int nodeLevel = 1;
        while (nodeLevel < MAX_LEVEL && random.nextDouble() < PROMOTION_PROBABILITY) {
            nodeLevel++;
        }
        return nodeLevel;
    }
}
