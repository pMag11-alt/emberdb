package dev.emberdb.commands;

import java.util.ArrayList;
import java.util.List;

import dev.emberdb.engine.CommandContext;
import dev.emberdb.engine.CommandException;
import dev.emberdb.engine.CommandTable;
import dev.emberdb.protocol.Reply;
import dev.emberdb.store.HashValue;
import dev.emberdb.util.Numbers;

import static dev.emberdb.engine.Command.Flag.WRITE;

/** The hash type. */
final class HashCommands {

    private HashCommands() {}

    static void register(CommandTable t) {
        t.add("hset", -4, HashCommands::hset, WRITE);
        t.add("hmset", -4, c -> {
            hset(c);
            return Reply.OK;
        }, WRITE);
        t.add("hsetnx", 4, HashCommands::hsetnx, WRITE);
        t.add("hget", 3, HashCommands::hget);
        t.add("hmget", -3, HashCommands::hmget);
        t.add("hdel", -3, HashCommands::hdel, WRITE);
        t.add("hgetall", 2, HashCommands::hgetall);
        t.add("hkeys", 2, c -> Reply.bulks(orEmpty(c.db().getHash(c.arg(1))).keySet()));
        t.add("hvals", 2, c -> Reply.bulks(orEmpty(c.db().getHash(c.arg(1))).values()));
        t.add("hlen", 2, c -> Reply.integer(orEmpty(c.db().getHash(c.arg(1))).size()));
        t.add("hexists", 3, c -> Reply.integer(orEmpty(c.db().getHash(c.arg(1))).containsKey(c.arg(2)) ? 1 : 0));
        t.add("hstrlen", 3, HashCommands::hstrlen);
        t.add("hincrby", 4, HashCommands::hincrby, WRITE);
        t.add("hincrbyfloat", 4, HashCommands::hincrbyfloat, WRITE);
    }

    private static final HashValue EMPTY = new HashValue();

    private static HashValue orEmpty(HashValue hash) {
        return hash == null ? EMPTY : hash;
    }

    /** HSET key field value [field value ...]: returns how many fields were new. */
    private static Reply hset(CommandContext c) {
        if (c.argc() % 2 != 0) {
            throw new CommandException("ERR wrong number of arguments for '" + c.name() + "' command");
        }
        String key = c.arg(1);
        HashValue hash = c.db().getOrCreateHash(key);
        int added = 0;
        for (int i = 2; i < c.argc(); i += 2) {
            if (hash.put(c.arg(i), c.arg(i + 1)) == null) {
                added++;
            }
        }
        c.db().modified(key);
        return Reply.integer(added);
    }

    private static Reply hsetnx(CommandContext c) {
        String key = c.arg(1);
        if (orEmpty(c.db().getHash(key)).containsKey(c.arg(2))) {
            return Reply.ZERO;
        }
        c.db().getOrCreateHash(key).put(c.arg(2), c.arg(3));
        c.db().modified(key);
        return Reply.ONE;
    }

    private static Reply hget(CommandContext c) {
        return Reply.bulk(orEmpty(c.db().getHash(c.arg(1))).get(c.arg(2)));
    }

    private static Reply hmget(CommandContext c) {
        HashValue hash = orEmpty(c.db().getHash(c.arg(1)));
        List<Reply> values = new ArrayList<>(c.argc() - 2);
        for (String field : c.args(2)) {
            values.add(Reply.bulk(hash.get(field)));
        }
        return Reply.array(values);
    }

    private static Reply hdel(CommandContext c) {
        String key = c.arg(1);
        HashValue hash = c.db().getHash(key);
        if (hash == null) {
            return Reply.ZERO;
        }
        int removed = 0;
        for (String field : c.args(2)) {
            if (hash.remove(field) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            c.db().modified(key);
        }
        return Reply.integer(removed);
    }

    private static Reply hgetall(CommandContext c) {
        HashValue hash = orEmpty(c.db().getHash(c.arg(1)));
        List<Reply> flat = new ArrayList<>(hash.size() * 2);
        hash.forEach((field, value) -> {
            flat.add(Reply.bulk(field));
            flat.add(Reply.bulk(value));
        });
        return Reply.dict(flat);
    }

    private static Reply hstrlen(CommandContext c) {
        String value = orEmpty(c.db().getHash(c.arg(1))).get(c.arg(2));
        return Reply.integer(value == null ? 0 : value.length());
    }

    private static Reply hincrby(CommandContext c) {
        String key = c.arg(1);
        String field = c.arg(2);
        long delta = c.longArg(3);
        String current = orEmpty(c.db().getHash(key)).get(field);
        long value = 0;
        if (current != null) {
            Long parsed = Numbers.parseLong(current);
            if (parsed == null) {
                throw new CommandException("ERR hash value is not an integer");
            }
            value = parsed;
        }
        long result;
        try {
            result = Math.addExact(value, delta);
        } catch (ArithmeticException e) {
            throw new CommandException("ERR increment or decrement would overflow");
        }
        c.db().getOrCreateHash(key).put(field, Long.toString(result));
        c.db().modified(key);
        return Reply.integer(result);
    }

    private static Reply hincrbyfloat(CommandContext c) {
        String key = c.arg(1);
        String field = c.arg(2);
        double delta = c.doubleArg(3);
        String current = orEmpty(c.db().getHash(key)).get(field);
        double value = 0;
        if (current != null) {
            Double parsed = Numbers.parseDouble(current);
            if (parsed == null) {
                throw new CommandException("ERR hash value is not a float");
            }
            value = parsed;
        }
        double result = value + delta;
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            throw new CommandException("ERR increment would produce NaN or Infinity");
        }
        String formatted = Numbers.formatDouble(result);
        c.db().getOrCreateHash(key).put(field, formatted);
        c.db().modified(key);
        c.propagate("HSET", key, field, formatted);
        return Reply.bulk(formatted);
    }
}
