package dev.emberdb.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.emberdb.engine.CommandContext;
import dev.emberdb.engine.CommandException;
import dev.emberdb.engine.CommandTable;
import dev.emberdb.protocol.Reply;
import dev.emberdb.store.Database;
import dev.emberdb.store.SetValue;

import static dev.emberdb.engine.Command.Flag.WRITE;

/** The set type, including intersections, unions and differences. */
final class SetCommands {

    private SetCommands() {}

    private enum Op { INTER, UNION, DIFF }

    static void register(CommandTable t) {
        t.add("sadd", -3, SetCommands::sadd, WRITE);
        t.add("srem", -3, SetCommands::srem, WRITE);
        t.add("smembers", 2, c -> Reply.setOf(orEmpty(c.db().getSet(c.arg(1)))));
        t.add("sismember", 3, c -> Reply.integer(orEmpty(c.db().getSet(c.arg(1))).contains(c.arg(2)) ? 1 : 0));
        t.add("smismember", -3, SetCommands::smismember);
        t.add("scard", 2, c -> Reply.integer(orEmpty(c.db().getSet(c.arg(1))).size()));
        t.add("spop", -2, SetCommands::spop, WRITE);
        t.add("srandmember", -2, SetCommands::srandmember);
        t.add("sinter", -2, c -> Reply.setOf(combine(c.db(), c.args(1), Op.INTER)));
        t.add("sunion", -2, c -> Reply.setOf(combine(c.db(), c.args(1), Op.UNION)));
        t.add("sdiff", -2, c -> Reply.setOf(combine(c.db(), c.args(1), Op.DIFF)));
        t.add("sinterstore", -3, c -> store(c, Op.INTER), WRITE);
        t.add("sunionstore", -3, c -> store(c, Op.UNION), WRITE);
        t.add("sdiffstore", -3, c -> store(c, Op.DIFF), WRITE);
    }

    private static final SetValue EMPTY = new SetValue();

    private static SetValue orEmpty(SetValue set) {
        return set == null ? EMPTY : set;
    }

    private static Reply sadd(CommandContext c) {
        String key = c.arg(1);
        SetValue set = c.db().getOrCreateSet(key);
        int added = 0;
        for (String member : c.args(2)) {
            if (set.add(member)) {
                added++;
            }
        }
        c.db().modified(key);
        return Reply.integer(added);
    }

    private static Reply srem(CommandContext c) {
        String key = c.arg(1);
        SetValue set = c.db().getSet(key);
        if (set == null) {
            return Reply.ZERO;
        }
        int removed = 0;
        for (String member : c.args(2)) {
            if (set.remove(member)) {
                removed++;
            }
        }
        if (removed > 0) {
            c.db().modified(key);
        }
        return Reply.integer(removed);
    }

    private static Reply smismember(CommandContext c) {
        SetValue set = orEmpty(c.db().getSet(c.arg(1)));
        List<Reply> result = new ArrayList<>();
        for (String member : c.args(2)) {
            result.add(Reply.integer(set.contains(member) ? 1 : 0));
        }
        return Reply.array(result);
    }

    /**
     * SPOP removes random members, so replaying "SPOP key" from the AOF would remove different ones.
     * It is logged as an SREM of the members actually removed.
     */
    private static Reply spop(CommandContext c) {
        if (c.argc() > 3) {
            throw CommandException.syntax();
        }
        boolean withCount = c.argc() == 3;
        long count = withCount ? c.longArg(2) : 1;
        if (count < 0) {
            throw new CommandException("ERR value is out of range, must be positive");
        }
        String key = c.arg(1);
        SetValue set = c.db().getSet(key);
        if (set == null) {
            return withCount ? Reply.setOf(List.of()) : Reply.NIL;
        }
        List<String> popped = pick(c, set, (int) Math.min(count, set.size()));
        set.removeAll(popped);
        if (!popped.isEmpty()) {
            List<String> srem = new ArrayList<>(popped.size() + 2);
            srem.add("SREM");
            srem.add(key);
            srem.addAll(popped);
            c.propagate(srem);
            c.db().modified(key);
        }
        return withCount ? Reply.setOf(popped) : Reply.bulk(popped.isEmpty() ? null : popped.getFirst());
    }

    /** SRANDMEMBER key [count]: a positive count gives distinct members, a negative one may repeat. */
    private static Reply srandmember(CommandContext c) {
        if (c.argc() > 3) {
            throw CommandException.syntax();
        }
        SetValue set = c.db().getSet(c.arg(1));
        if (c.argc() == 2) {
            return set == null ? Reply.NIL : Reply.bulk(pick(c, set, 1).getFirst());
        }
        long count = c.longArg(2);
        if (set == null || count == 0) {
            return Reply.EMPTY_ARRAY;
        }
        if (count > 0) {
            return Reply.bulks(pick(c, set, (int) Math.min(count, set.size())));
        }
        List<String> members = new ArrayList<>(set);
        List<String> result = new ArrayList<>();
        for (long i = 0; i < -count; i++) {
            result.add(members.get(c.engine().random().nextInt(members.size())));
        }
        return Reply.bulks(result);
    }

    /** {@code n} distinct random members, by a partial Fisher-Yates shuffle. */
    private static List<String> pick(CommandContext c, SetValue set, int n) {
        List<String> members = new ArrayList<>(set);
        for (int i = 0; i < n; i++) {
            int j = i + c.engine().random().nextInt(members.size() - i);
            String swap = members.get(i);
            members.set(i, members.get(j));
            members.set(j, swap);
        }
        return new ArrayList<>(members.subList(0, n));
    }

    /**
     * Intersection starts from the smallest set and probes the others, so its cost is driven by the smallest input,
     * which is the same trick Redis uses. A missing key counts as an empty set.
     */
    private static List<String> combine(Database db, List<String> keys, Op op) {
        List<SetValue> sets = new ArrayList<>(keys.size());
        for (String key : keys) {
            sets.add(orEmpty(db.getSet(key))); // type-checks every key before computing anything
        }
        SetValue result = new SetValue();
        switch (op) {
            case INTER -> {
                List<SetValue> bySize = new ArrayList<>(sets);
                bySize.sort(Comparator.comparingInt(SetValue::size));
                outer:
                for (String member : bySize.getFirst()) {
                    for (int i = 1; i < bySize.size(); i++) {
                        if (!bySize.get(i).contains(member)) {
                            continue outer;
                        }
                    }
                    result.add(member);
                }
            }
            case UNION -> sets.forEach(result::addAll);
            case DIFF -> {
                result.addAll(sets.getFirst());
                for (int i = 1; i < sets.size() && !result.isEmpty(); i++) {
                    result.removeAll(sets.get(i));
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static Reply store(CommandContext c, Op op) {
        String destination = c.arg(1);
        List<String> members = combine(c.db(), c.args(2), op);
        if (members.isEmpty()) {
            c.db().delete(destination);
        } else {
            SetValue set = new SetValue();
            set.addAll(members);
            c.db().set(destination, set);
        }
        return Reply.integer(members.size());
    }
}
