package dev.emberdb.engine;

import java.util.List;
import java.util.Locale;

import dev.emberdb.store.Database;
import dev.emberdb.util.Numbers;

/** What a command handler gets: its arguments, the caller's session and the database. */
public final class CommandContext {

    private final Engine engine;
    private final Session session;
    private final List<String> argv;
    private boolean propagated;

    CommandContext(Engine engine, Session session, List<String> argv) {
        this.engine = engine;
        this.session = session;
        this.argv = argv;
    }

    public Engine engine() {
        return engine;
    }

    public Session session() {
        return session;
    }

    public Database db() {
        return engine.db();
    }

    public long now() {
        return engine.db().now();
    }

    /** Number of arguments including the command name. */
    public int argc() {
        return argv.size();
    }

    /** Argument {@code i}; argument 0 is the command name. */
    public String arg(int i) {
        return argv.get(i);
    }

    /** Argument {@code i} in upper case, for matching option keywords such as {@code EX} or {@code WITHSCORES}. */
    public String option(int i) {
        return argv.get(i).toUpperCase(Locale.ROOT);
    }

    /** Arguments from {@code from} to the end. */
    public List<String> args(int from) {
        return argv.subList(from, argv.size());
    }

    public String name() {
        return argv.get(0).toLowerCase(Locale.ROOT);
    }

    public long longArg(int i) {
        Long value = Numbers.parseLong(argv.get(i));
        if (value == null) {
            throw CommandException.notInteger();
        }
        return value;
    }

    public int intArg(int i) {
        long value = longArg(i);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw CommandException.notInteger();
        }
        return (int) value;
    }

    public double doubleArg(int i) {
        Double value = Numbers.parseDouble(argv.get(i));
        if (value == null) {
            throw CommandException.notFloat();
        }
        return value;
    }

    /**
     * Log this command to the append-only file instead of the one the client sent. Commands whose effect depends
     * on when they ran use it: {@code EXPIRE k 10} is logged as {@code PEXPIREAT k <absolute ms>}, so replaying
     * the file tomorrow does not give the key a fresh 10 seconds.
     */
    public void propagate(String... command) {
        propagated = true;
        engine.propagate(List.of(command));
    }

    public void propagate(List<String> command) {
        propagated = true;
        engine.propagate(List.copyOf(command));
    }

    boolean propagated() {
        return propagated;
    }
}
