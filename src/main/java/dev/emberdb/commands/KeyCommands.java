package dev.emberdb.commands;

import dev.emberdb.engine.CommandContext;
import dev.emberdb.engine.CommandException;
import dev.emberdb.engine.CommandTable;
import dev.emberdb.protocol.Reply;
import dev.emberdb.store.Database;

import static dev.emberdb.engine.Command.Flag.WRITE;

/** Generic key commands: DEL, EXISTS, TYPE, KEYS, RENAME and the TTL family. */
final class KeyCommands {

    private KeyCommands() {}

    static void register(CommandTable t) {
        t.add("del", -2, KeyCommands::del, WRITE);
        t.add("unlink", -2, KeyCommands::del, WRITE);
        t.add("exists", -2, KeyCommands::exists);
        t.add("type", 2, c -> Reply.simple(c.db().type(c.arg(1))));
        t.add("keys", 2, c -> Reply.bulks(c.db().keys(c.arg(1))));
        t.add("rename", 3, KeyCommands::rename, WRITE);
        t.add("renamenx", 3, KeyCommands::renamenx, WRITE);
        t.add("expire", -3, c -> expire(c, c.now(), 1000), WRITE);
        t.add("pexpire", -3, c -> expire(c, c.now(), 1), WRITE);
        t.add("expireat", -3, c -> expire(c, 0, 1000), WRITE);
        t.add("pexpireat", -3, c -> expire(c, 0, 1), WRITE);
        t.add("ttl", 2, c -> ttl(c, true));
        t.add("pttl", 2, c -> ttl(c, false));
        t.add("expiretime", 2, c -> expireTime(c, true));
        t.add("pexpiretime", 2, c -> expireTime(c, false));
        t.add("persist", 2, c -> Reply.integer(c.db().persist(c.arg(1)) ? 1 : 0), WRITE);
    }

    private static Reply del(CommandContext c) {
        int deleted = 0;
        for (String key : c.args(1)) {
            if (c.db().delete(key)) {
                deleted++;
            }
        }
        return Reply.integer(deleted);
    }

    private static Reply exists(CommandContext c) {
        int found = 0;
        for (String key : c.args(1)) {
            if (c.db().exists(key)) {
                found++;
            }
        }
        return Reply.integer(found);
    }

    private static Reply rename(CommandContext c) {
        String from = c.arg(1);
        String to = c.arg(2);
        if (!c.db().exists(from)) {
            throw new CommandException("ERR no such key");
        }
        if (!from.equals(to)) {
            c.db().rename(from, to);
        }
        return Reply.OK;
    }

    private static Reply renamenx(CommandContext c) {
        String from = c.arg(1);
        String to = c.arg(2);
        if (!c.db().exists(from)) {
            throw new CommandException("ERR no such key");
        }
        if (c.db().exists(to)) {
            return Reply.ZERO;
        }
        c.db().rename(from, to);
        return Reply.ONE;
    }

    /**
     * EXPIRE, PEXPIRE, EXPIREAT and PEXPIREAT, with the NX / XX / GT / LT options. The deadline is
     * {@code base + amount * unitMillis}. Whatever the client sent, the AOF records the absolute
     * {@code PEXPIREAT}.
     */
    private static Reply expire(CommandContext c, long base, long unitMillis) {
        String key = c.arg(1);
        long amount = c.longArg(2);
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        for (int i = 3; i < c.argc(); i++) {
            switch (c.option(i)) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "GT" -> gt = true;
                case "LT" -> lt = true;
                default -> throw new CommandException("ERR Unsupported option " + c.arg(i));
            }
        }
        if (nx && (xx || gt || lt)) {
            throw new CommandException("ERR NX and XX, GT or LT options at the same time are not compatible");
        }
        if (gt && lt) {
            throw new CommandException("ERR GT and LT options at the same time are not compatible");
        }
        long deadline = deadline(base, amount, unitMillis, c.name());

        Database db = c.db();
        if (!db.exists(key)) {
            return Reply.ZERO;
        }
        long current = db.getExpire(key); // -1: no TTL, which GT and LT treat as infinite
        if ((nx && current >= 0) || (xx && current < 0)
                || (gt && (current < 0 || deadline <= current)) || (lt && current >= 0 && deadline >= current)) {
            return Reply.ZERO;
        }
        db.setExpire(key, deadline);
        if (deadline <= c.now()) {
            c.propagate("DEL", key);
        } else {
            c.propagate("PEXPIREAT", key, Long.toString(deadline));
        }
        return Reply.ONE;
    }

    /** {@code base + amount * unit}, or an error if that overflows. Shared with SET EX and SETEX. */
    static long deadline(long base, long amount, long unitMillis, String command) {
        try {
            return Math.addExact(base, Math.multiplyExact(amount, unitMillis));
        } catch (ArithmeticException e) {
            throw new CommandException("ERR invalid expire time in '" + command + "' command");
        }
    }

    private static Reply ttl(CommandContext c, boolean seconds) {
        String key = c.arg(1);
        if (!c.db().exists(key)) {
            return Reply.integer(-2);
        }
        long deadline = c.db().getExpire(key);
        if (deadline < 0) {
            return Reply.integer(-1);
        }
        long remaining = Math.max(0, deadline - c.now());
        return Reply.integer(seconds ? (remaining + 500) / 1000 : remaining);
    }

    private static Reply expireTime(CommandContext c, boolean seconds) {
        String key = c.arg(1);
        if (!c.db().exists(key)) {
            return Reply.integer(-2);
        }
        long deadline = c.db().getExpire(key);
        if (deadline < 0) {
            return Reply.integer(-1);
        }
        return Reply.integer(seconds ? deadline / 1000 : deadline);
    }
}
