package dev.emberdb.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListValueTest {

    @Test
    void wrapsAroundAndGrows() {
        ListValue list = new ListValue();
        for (int i = 0; i < 5; i++) {
            list.addFirst("f" + i);
            list.addLast("l" + i);
        }
        assertEquals(10, list.size());
        assertEquals("f4", list.get(0));
        assertEquals("l4", list.get(9));
        assertEquals("f0", list.get(4));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(10));
    }

    @Test
    void behavesLikeADequeUnderRandomOperations() {
        SplittableRandom random = new SplittableRandom(3);
        ListValue list = new ListValue();
        ArrayDeque<String> reference = new ArrayDeque<>();
        for (int i = 0; i < 50_000; i++) {
            String value = Integer.toString(i);
            switch (reference.isEmpty() ? random.nextInt(2) : random.nextInt(4)) {
                case 0 -> {
                    list.addFirst(value);
                    reference.addFirst(value);
                }
                case 1 -> {
                    list.addLast(value);
                    reference.addLast(value);
                }
                case 2 -> assertEquals(reference.removeFirst(), list.removeFirst());
                default -> assertEquals(reference.removeLast(), list.removeLast());
            }
        }
        assertEquals(new ArrayList<>(reference), toList(list));
    }

    @Test
    void retainAndRemove() {
        ListValue list = of("a", "b", "a", "c", "a", "d");
        assertEquals(2, list.remove("a", -2)); // the last two a's
        assertEquals(List.of("a", "b", "c", "d"), toList(list));
        list.retain(1, 2);
        assertEquals(List.of("b", "c"), toList(list));
        list.addFirst("x");
        assertEquals(List.of("x", "b", "c"), toList(list));
        assertEquals(0, list.remove("zzz", 0));
    }

    private static ListValue of(String... items) {
        ListValue list = new ListValue();
        for (String item : items) {
            list.addLast(item);
        }
        return list;
    }

    private static List<String> toList(ListValue list) {
        List<String> out = new ArrayList<>();
        list.forEach(out::add);
        return out;
    }
}
