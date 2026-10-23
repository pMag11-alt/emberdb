package dev.emberdb.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A reply, independent of protocol version. {@link RespWriter} encodes it as RESP2 or RESP3, depending on what the
 * client chose with HELLO. Types that RESP2 lacks fall back to their RESP2 equivalents: a {@link Dict} becomes a
 * flat array, a {@link Dbl} a bulk string, and so on.
 * <p>
 * Strings are Latin-1, so each char stands for exactly one byte of the original data.
 */
public sealed interface Reply {

    /** {@code +OK} */
    record Simple(String text) implements Reply {}

    /** {@code -ERR message}. The first word of the message is the error code. */
    record Err(String message) implements Reply {}

    /** {@code :42} */
    record Int(long value) implements Reply {}

    /** {@code $5 hello}, or null ({@code $-1} / RESP3 {@code _}) when {@code value} is null. */
    record Bulk(String value) implements Reply {}

    /** {@code *2 ...}, or null ({@code *-1} / RESP3 {@code _}) when {@code items} is null. */
    record Multi(List<Reply> items) implements Reply {}

    /** RESP3 double {@code ,1.5}; a bulk string in RESP2. */
    record Dbl(double value) implements Reply {}

    /** RESP3 map {@code %2 k v k v}, given as a flat key/value list; a flat array in RESP2. */
    record Dict(List<Reply> flat) implements Reply {}

    /** RESP3 set {@code ~2 a b}; an array in RESP2. */
    record SetOf(List<Reply> items) implements Reply {}

    /** RESP3 out-of-band push {@code >3 message channel text}, used by pub/sub; an array in RESP2. */
    record Push(List<Reply> items) implements Reply {}

    /** RESP3 verbatim text {@code =15 txt:...}, used for INFO-style output; a bulk string in RESP2. */
    record Verbatim(String text) implements Reply {}

    /**
     * Pairs such as (member, score), given flat. RESP3 nests them, {@code [[a, 1], [b, 2]]}, while RESP2 sends
     * {@code [a, 1, b, 2]}. This is how real Redis answers {@code ZRANGE ... WITHSCORES}.
     */
    record Pairs(List<Reply> flat) implements Reply {}

    Reply OK = new Simple("OK");
    Reply PONG = new Simple("PONG");
    Reply QUEUED = new Simple("QUEUED");
    Reply NIL = new Bulk(null);
    Reply NIL_ARRAY = new Multi(null);
    Reply EMPTY_ARRAY = new Multi(List.of());
    Reply ZERO = new Int(0);
    Reply ONE = new Int(1);

    static Reply simple(String text) {
        return new Simple(text);
    }

    static Reply error(String message) {
        return new Err(message);
    }

    static Reply integer(long value) {
        return value == 0 ? ZERO : value == 1 ? ONE : new Int(value);
    }

    static Reply bulk(String value) {
        return value == null ? NIL : new Bulk(value);
    }

    static Reply dbl(double value) {
        return new Dbl(value);
    }

    static Reply array(List<Reply> items) {
        return new Multi(items);
    }

    static Reply array(Reply... items) {
        return new Multi(List.of(items));
    }

    /** An array of bulk strings; null elements become null bulk strings. */
    static Reply bulks(Iterable<String> values) {
        return new Multi(toBulks(values));
    }

    static Reply bulks(String... values) {
        return bulks(Arrays.asList(values));
    }

    static Reply dict(List<Reply> flat) {
        return new Dict(flat);
    }

    static Reply setOf(Iterable<String> members) {
        return new SetOf(toBulks(members));
    }

    static Reply push(Reply... items) {
        return new Push(List.of(items));
    }

    static Reply verbatim(String text) {
        return new Verbatim(text);
    }

    static Reply pairs(List<Reply> flat) {
        return new Pairs(flat);
    }

    private static List<Reply> toBulks(Iterable<String> values) {
        List<Reply> items = new ArrayList<>();
        for (String value : values) {
            items.add(bulk(value));
        }
        return items;
    }
}
