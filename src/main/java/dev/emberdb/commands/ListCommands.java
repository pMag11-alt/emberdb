package dev.emberdb.commands;

import java.util.ArrayList;
import java.util.List;

import dev.emberdb.engine.CommandContext;
import dev.emberdb.engine.CommandException;
import dev.emberdb.engine.CommandTable;
import dev.emberdb.protocol.Reply;
import dev.emberdb.store.ListValue;

import static dev.emberdb.engine.Command.Flag.WRITE;

/** The list type. */
final class ListCommands {

    private ListCommands() {}

    static void register(CommandTable t) {
        t.add("lpush", -3, c -> push(c, true, false), WRITE);
        t.add("rpush", -3, c -> push(c, false, false), WRITE);
        t.add("lpushx", -3, c -> push(c, true, true), WRITE);
        t.add("rpushx", -3, c -> push(c, false, true), WRITE);
        t.add("lpop", -2, c -> pop(c, true), WRITE);
        t.add("rpop", -2, c -> pop(c, false), WRITE);
        t.add("llen", 2, c -> Reply.integer(sizeOf(c.db().getList(c.arg(1)))));
        t.add("lindex", 3, ListCommands::lindex);
        t.add("lset", 4, ListCommands::lset, WRITE);
        t.add("lrange", 4, ListCommands::lrange);
        t.add("ltrim", 4, ListCommands::ltrim, WRITE);
        t.add("lrem", 4, ListCommands::lrem, WRITE);
    }

    private static Reply push(CommandContext c, boolean left, boolean onlyIfExists) {
        String key = c.arg(1);
        ListValue list = onlyIfExists ? c.db().getList(key) : c.db().getOrCreateList(key);
        if (list == null) {
            return Reply.ZERO;
        }
        for (String element : c.args(2)) {
            if (left) {
                list.addFirst(element);
            } else {
                list.addLast(element);
            }
        }
        c.db().modified(key);
        return Reply.integer(list.size());
    }

    /** LPOP key [count]: without a count, one bulk string; with one, an array. */
    private static Reply pop(CommandContext c, boolean left) {
        if (c.argc() > 3) {
            throw CommandException.syntax();
        }
        boolean withCount = c.argc() == 3;
        long count = withCount ? c.longArg(2) : 1;
        if (count < 0) {
            throw new CommandException("ERR value is out of range, must be positive");
        }
        String key = c.arg(1);
        ListValue list = c.db().getList(key);
        if (list == null) {
            return withCount ? Reply.NIL_ARRAY : Reply.NIL;
        }
        List<Reply> popped = new ArrayList<>();
        while (popped.size() < count && !list.isEmpty()) {
            popped.add(Reply.bulk(left ? list.removeFirst() : list.removeLast()));
        }
        if (!popped.isEmpty()) {
            c.db().modified(key);
        }
        return withCount ? Reply.array(popped) : popped.getFirst();
    }

    private static Reply lindex(CommandContext c) {
        ListValue list = c.db().getList(c.arg(1));
        long index = c.longArg(2);
        if (list == null) {
            return Reply.NIL;
        }
        if (index < 0) {
            index += list.size();
        }
        return index >= 0 && index < list.size() ? Reply.bulk(list.get((int) index)) : Reply.NIL;
    }

    private static Reply lset(CommandContext c) {
        String key = c.arg(1);
        long index = c.longArg(2);
        ListValue list = c.db().getList(key);
        if (list == null) {
            throw new CommandException("ERR no such key");
        }
        if (index < 0) {
            index += list.size();
        }
        if (index < 0 || index >= list.size()) {
            throw new CommandException("ERR index out of range");
        }
        list.set((int) index, c.arg(3));
        c.db().modified(key);
        return Reply.OK;
    }

    private static Reply lrange(CommandContext c) {
        long start = c.longArg(2);
        long stop = c.longArg(3);
        ListValue list = c.db().getList(c.arg(1));
        IndexRange range = list == null ? null : IndexRange.of(start, stop, list.size());
        if (range == null) {
            return Reply.EMPTY_ARRAY;
        }
        List<Reply> items = new ArrayList<>(range.length());
        for (int i = range.from(); i <= range.to(); i++) {
            items.add(Reply.bulk(list.get(i)));
        }
        return Reply.array(items);
    }

    private static Reply ltrim(CommandContext c) {
        String key = c.arg(1);
        long start = c.longArg(2);
        long stop = c.longArg(3);
        ListValue list = c.db().getList(key);
        if (list == null) {
            return Reply.OK;
        }
        IndexRange range = IndexRange.of(start, stop, list.size());
        if (range == null) {
            c.db().delete(key);
        } else if (range.length() != list.size()) {
            list.retain(range.from(), range.to());
            c.db().modified(key);
        }
        return Reply.OK;
    }

    private static Reply lrem(CommandContext c) {
        String key = c.arg(1);
        long count = c.longArg(2);
        ListValue list = c.db().getList(key);
        if (list == null) {
            return Reply.ZERO;
        }
        int removed = list.remove(c.arg(3), count);
        if (removed > 0) {
            c.db().modified(key);
        }
        return Reply.integer(removed);
    }

    private static int sizeOf(ListValue list) {
        return list == null ? 0 : list.size();
    }
}
