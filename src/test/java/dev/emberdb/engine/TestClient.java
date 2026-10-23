package dev.emberdb.engine;

import java.util.ArrayList;
import java.util.List;

import dev.emberdb.protocol.Reply;
import dev.emberdb.util.Numbers;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Drives an {@link Engine} like a connected client, without sockets, and records every reply. */
public final class TestClient {

    private final Engine engine;
    private final Session session;
    private final List<Reply> replies = new ArrayList<>();

    public TestClient(Engine engine) {
        this.engine = engine;
        this.session = engine.connect(new ReplySink() {
            @Override
            public void write(Reply reply, int protocol) {
                synchronized (replies) {
                    replies.add(reply);
                }
            }

            @Override
            public void flush() {}
        }, "127.0.0.1:0");
    }

    public Session session() {
        return session;
    }

    /** Runs a command and returns its (last) reply, or null if it sent none. */
    public Reply call(String... argv) {
        List<Reply> all = callAll(argv);
        return all.isEmpty() ? null : all.getLast();
    }

    /** Runs a command and returns every reply it sent. */
    public List<Reply> callAll(String... argv) {
        int before = count();
        engine.dispatch(session, List.of(argv));
        synchronized (replies) {
            return new ArrayList<>(replies.subList(before, replies.size()));
        }
    }

    /** A string-like reply (bulk, double or verbatim text) as a Java string, or null for a nil bulk. */
    public String bulk(String... argv) {
        return text(call(argv));
    }

    public long integer(String... argv) {
        return assertInstanceOf(Reply.Int.class, call(argv)).value();
    }

    /** A list-like reply as Java strings (nulls for nil elements). */
    public List<String> strings(String... argv) {
        return strings(call(argv));
    }

    public String error(String... argv) {
        return assertInstanceOf(Reply.Err.class, call(argv)).message();
    }

    /** Any list-like reply (array, map, set, push, pairs) flattened into strings. */
    public static List<String> strings(Reply reply) {
        List<Reply> items = switch (reply) {
            case Reply.Multi m -> m.items();
            case Reply.Dict d -> d.flat();
            case Reply.SetOf s -> s.items();
            case Reply.Push p -> p.items();
            case Reply.Pairs p -> p.flat();
            default -> throw new AssertionError("expected a list-like reply, got " + reply);
        };
        List<String> out = new ArrayList<>();
        for (Reply item : items) {
            out.add(text(item));
        }
        return out;
    }

    public static String text(Reply reply) {
        return switch (reply) {
            case Reply.Bulk b -> b.value();
            case Reply.Dbl d -> Numbers.formatDouble(d.value());
            case Reply.Verbatim v -> v.text();
            case Reply.Int i -> Long.toString(i.value());
            default -> throw new AssertionError("expected a string reply, got " + reply);
        };
    }

    /** Every reply received so far, including messages pushed by other clients. */
    public List<Reply> received() {
        synchronized (replies) {
            return new ArrayList<>(replies);
        }
    }

    private int count() {
        synchronized (replies) {
            return replies.size();
        }
    }

    public void disconnect() {
        engine.disconnect(session);
    }
}
