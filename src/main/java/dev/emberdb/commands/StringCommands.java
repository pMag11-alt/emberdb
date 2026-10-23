package dev.emberdb.commands;

import java.util.ArrayList;
import java.util.List;

import dev.emberdb.engine.CommandContext;
import dev.emberdb.engine.CommandException;
import dev.emberdb.engine.CommandTable;
import dev.emberdb.protocol.Reply;
import dev.emberdb.store.Database;
import dev.emberdb.util.Numbers;

import static dev.emberdb.engine.Command.Flag.WRITE;

/** The string type: GET, SET and its variants, counters, APPEND, GETRANGE. */
final class StringCommands {

    private StringCommands() {}

    static void register(CommandTable t) {
        t.add("get", 2, c -> Reply.bulk(c.db().getString(c.arg(1))));
        t.add("set", -3, StringCommands::set, WRITE);
        t.add("setnx", 3, StringCommands::setnx, WRITE);
        t.add("setex", 4, c -> setWithTtl(c, 1000), WRITE);
        t.add("psetex", 4, c -> setWithTtl(c, 1), WRITE);
        t.add("getset", 3, StringCommands::getset, WRITE);
        t.add("getdel", 2, StringCommands::getdel, WRITE);
        t.add("mget", -2, StringCommands::mget);
        t.add("mset", -3, StringCommands::mset, WRITE);
        t.add("msetnx", -3, StringCommands::msetnx, WRITE);
        t.add("incr", 2, c -> incrBy(c, 1), WRITE);
        t.add("decr", 2, c -> incrBy(c, -1), WRITE);
        t.add("incrby", 3, c -> incrBy(c, c.longArg(2)), WRITE);
        t.add("decrby", 3, StringCommands::decrby, WRITE);
        t.add("incrbyfloat", 3, StringCommands::incrbyfloat, WRITE);
        t.add("append", 3, StringCommands::append, WRITE);
        t.add("strlen", 2, c -> Reply.integer(lengthOf(c.db().getString(c.arg(1)))));
        t.add("getrange", 4, StringCommands::getrange);
    }

    /** SET key value [NX | XX] [GET] [EX s | PX ms | EXAT s | PXAT ms | KEEPTTL] */
    private static Reply set(CommandContext c) {
        String key = c.arg(1);
        String value = c.arg(2);
        boolean nx = false;
        boolean xx = false;
        boolean get = false;
        boolean keepTtl = false;
        long deadline = -1;
        for (int i = 3; i < c.argc(); i++) {
            String option = c.option(i);
            switch (option) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "GET" -> get = true;
                case "KEEPTTL" -> keepTtl = true;
                case "EX", "PX", "EXAT", "PXAT" -> {
                    if (deadline >= 0 || i + 1 >= c.argc()) {
                        throw CommandException.syntax();
                    }
                    long amount = c.longArg(++i);
                    if (amount <= 0) {
                        throw new CommandException("ERR invalid expire time in 'set' command");
                    }
                    deadline = switch (option) {
                        case "EX" -> KeyCommands.deadline(c.now(), amount, 1000, "set");
                        case "PX" -> KeyCommands.deadline(c.now(), amount, 1, "set");
                        case "EXAT" -> KeyCommands.deadline(0, amount, 1000, "set");
                        default -> amount;
                    };
                }
                default -> throw CommandException.syntax();
            }
        }
        if ((nx && xx) || (keepTtl && deadline >= 0)) {
            throw CommandException.syntax();
        }

        Database db = c.db();
        String old = get ? db.getString(key) : null; // with GET, a non-string key is a WRONGTYPE error
        boolean exists = db.exists(key);
        if ((nx && exists) || (xx && !exists)) {
            return get ? Reply.bulk(old) : Reply.NIL;
        }
        if (keepTtl) {
            db.setKeepTtl(key, value);
            c.propagate("SET", key, value, "KEEPTTL");
        } else if (deadline >= 0) {
            db.set(key, value);
            db.setExpire(key, deadline);
            c.propagate("SET", key, value, "PXAT", Long.toString(deadline));
        } else {
            db.set(key, value);
            c.propagate("SET", key, value);
        }
        return get ? Reply.bulk(old) : Reply.OK;
    }

    private static Reply setnx(CommandContext c) {
        if (c.db().exists(c.arg(1))) {
            return Reply.ZERO;
        }
        c.db().set(c.arg(1), c.arg(2));
        return Reply.ONE;
    }

    /** SETEX key seconds value / PSETEX key milliseconds value */
    private static Reply setWithTtl(CommandContext c, long unitMillis) {
        String key = c.arg(1);
        long amount = c.longArg(2);
        String value = c.arg(3);
        if (amount <= 0) {
            throw new CommandException("ERR invalid expire time in '" + c.name() + "' command");
        }
        long deadline = KeyCommands.deadline(c.now(), amount, unitMillis, c.name());
        c.db().set(key, value);
        c.db().setExpire(key, deadline);
        c.propagate("SET", key, value, "PXAT", Long.toString(deadline));
        return Reply.OK;
    }

    private static Reply getset(CommandContext c) {
        String old = c.db().getString(c.arg(1));
        c.db().set(c.arg(1), c.arg(2));
        return Reply.bulk(old);
    }

    private static Reply getdel(CommandContext c) {
        String value = c.db().getString(c.arg(1));
        if (value != null) {
            c.db().delete(c.arg(1));
        }
        return Reply.bulk(value);
    }

    /** MGET returns nil for keys that hold other types instead of failing. */
    private static Reply mget(CommandContext c) {
        List<Reply> values = new ArrayList<>(c.argc() - 1);
        for (String key : c.args(1)) {
            values.add(c.db().get(key) instanceof String s ? Reply.bulk(s) : Reply.NIL);
        }
        return Reply.array(values);
    }

    private static Reply mset(CommandContext c) {
        if (c.argc() % 2 == 0) {
            throw new CommandException("ERR wrong number of arguments for '" + c.name() + "' command");
        }
        for (int i = 1; i < c.argc(); i += 2) {
            c.db().set(c.arg(i), c.arg(i + 1));
        }
        return Reply.OK;
    }

    private static Reply msetnx(CommandContext c) {
        if (c.argc() % 2 == 0) {
            throw new CommandException("ERR wrong number of arguments for '" + c.name() + "' command");
        }
        for (int i = 1; i < c.argc(); i += 2) {
            if (c.db().exists(c.arg(i))) {
                return Reply.ZERO;
            }
        }
        mset(c);
        return Reply.ONE;
    }

    private static Reply decrby(CommandContext c) {
        try {
            return incrBy(c, Math.negateExact(c.longArg(2)));
        } catch (ArithmeticException e) {
            throw new CommandException("ERR decrement would overflow");
        }
    }

    /** INCR, DECR, INCRBY, DECRBY. The value must be a 64-bit integer; the key keeps its TTL. */
    private static Reply incrBy(CommandContext c, long delta) {
        String key = c.arg(1);
        String current = c.db().getString(key);
        long value = 0;
        if (current != null) {
            Long parsed = Numbers.parseLong(current);
            if (parsed == null) {
                throw CommandException.notInteger();
            }
            value = parsed;
        }
        long result;
        try {
            result = Math.addExact(value, delta);
        } catch (ArithmeticException e) {
            throw new CommandException("ERR increment or decrement would overflow");
        }
        c.db().setKeepTtl(key, Long.toString(result));
        return Reply.integer(result);
    }

    /** Logged as a SET of the result, so replaying the AOF cannot drift through floating-point rounding. */
    private static Reply incrbyfloat(CommandContext c) {
        String key = c.arg(1);
        double delta = c.doubleArg(2);
        String current = c.db().getString(key);
        double value = 0;
        if (current != null) {
            Double parsed = Numbers.parseDouble(current);
            if (parsed == null) {
                throw CommandException.notFloat();
            }
            value = parsed;
        }
        double result = value + delta;
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new CommandException("ERR increment would produce NaN or Infinity");
        }
        String formatted = Numbers.formatDouble(result);
        c.db().setKeepTtl(key, formatted);
        c.propagate("SET", key, formatted, "KEEPTTL");
        return Reply.bulk(formatted);
    }

    private static Reply append(CommandContext c) {
        String key = c.arg(1);
        String current = c.db().getString(key);
        String updated = current == null ? c.arg(2) : current + c.arg(2);
        c.db().setKeepTtl(key, updated);
        return Reply.integer(updated.length());
    }

    /** GETRANGE key start end, both inclusive; negative offsets count from the end. */
    private static Reply getrange(CommandContext c) {
        long start = c.longArg(2);
        long end = c.longArg(3);
        String value = c.db().getString(c.arg(1));
        if (value == null || (start < 0 && end < 0 && start > end)) {
            return Reply.bulk("");
        }
        int length = value.length();
        if (start < 0) {
            start = Math.max(0, start + length);
        }
        if (end < 0) {
            end = Math.max(0, end + length);
        }
        end = Math.min(end, length - 1);
        if (start > end || length == 0) {
            return Reply.bulk("");
        }
        return Reply.bulk(value.substring((int) start, (int) end + 1));
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }
}
