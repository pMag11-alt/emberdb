package dev.emberdb.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkipListTest {

    private record Entry(double score, String member) {}

    private static final Comparator<Entry> ORDER =
            Comparator.comparingDouble(Entry::score).thenComparing(Entry::member);

    @Test
    void ordersByScoreThenMember() {
        SkipList list = new SkipList(new SplittableRandom(1));
        list.insert(2, "b");
        list.insert(1, "z");
        list.insert(2, "a");
        list.insert(-5, "neg");
        List<String> members = new ArrayList<>();
        for (SkipList.Node n = list.first(); n != null; n = n.next()) {
            members.add(n.member());
        }
        assertEquals(List.of("neg", "z", "a", "b"), members);
        assertEquals("b", list.last().member());
        assertEquals("a", list.last().prev().member());
    }

    @Test
    void ranksAndScoreRanges() {
        SkipList list = new SkipList(new SplittableRandom(2));
        for (int i = 0; i < 100; i++) {
            list.insert(i * 10, "m" + i);
        }
        assertEquals(0, list.rank(0, "m0"));
        assertEquals(42, list.rank(420, "m42"));
        assertEquals(-1, list.rank(420, "nope"));
        assertEquals("m99", list.byRank(99).member());
        assertNull(list.byRank(100));

        ScoreRange range = new ScoreRange(105, false, 200, true); // [105, 200)
        assertEquals("m11", list.firstInRange(range).member());
        assertEquals("m19", list.lastInRange(range).member());
        assertNull(list.firstInRange(new ScoreRange(1000, false, 2000, false)));
    }

    /**
     * Applies thousands of random inserts and deletes to the skip list and to a TreeSet, then checks that ordering,
     * rank and select-by-rank agree. A wrong span anywhere would make rank or byRank disagree.
     */
    @Test
    void matchesATreeSetUnderRandomOperations() {
        SplittableRandom random = new SplittableRandom(7);
        SkipList list = new SkipList(new SplittableRandom(8));
        TreeSet<Entry> reference = new TreeSet<>(ORDER);

        for (int op = 0; op < 20_000; op++) {
            Entry entry = new Entry(random.nextInt(50), "m" + random.nextInt(400));
            boolean present = reference.contains(entry);
            if (random.nextInt(3) == 0 && !reference.isEmpty()) {
                Entry victim = present ? entry : reference.higher(entry) != null ? reference.higher(entry) : reference.first();
                assertTrue(list.delete(victim.score(), victim.member()));
                reference.remove(victim);
            } else if (!present && !containsMember(reference, entry.member())) {
                list.insert(entry.score(), entry.member());
                reference.add(entry);
            }
            assertEquals(reference.size(), list.size());
        }

        List<Entry> expected = new ArrayList<>(reference);
        SkipList.Node node = list.first();
        for (int i = 0; i < expected.size(); i++, node = node.next()) {
            Entry e = expected.get(i);
            assertEquals(e.member(), node.member());
            assertEquals(i, list.rank(e.score(), e.member()));
            assertSame(node, list.byRank(i));
        }
        assertNull(node);
        assertFalse(list.delete(-1, "never-inserted"));
    }

    private static boolean containsMember(TreeSet<Entry> set, String member) {
        return set.stream().anyMatch(e -> e.member().equals(member));
    }
}
