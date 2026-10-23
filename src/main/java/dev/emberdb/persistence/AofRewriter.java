package dev.emberdb.persistence;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.emberdb.protocol.RespWriter;
import dev.emberdb.store.Database;
import dev.emberdb.store.HashValue;
import dev.emberdb.store.ListValue;
import dev.emberdb.store.SetValue;
import dev.emberdb.store.SkipList;
import dev.emberdb.store.ZSetValue;
import dev.emberdb.util.Numbers;

/**
 * Writes the current dataset as the shortest list of commands that recreates it. A key that was incremented a
 * million times becomes a single SET, and a deleted key disappears entirely.
 */
public final class AofRewriter {

    /** Collections are written in chunks of this many elements per command, as Redis does. */
    private static final int ITEMS_PER_COMMAND = 64;

    private AofRewriter() {}

    public static void writeSnapshot(Database db, OutputStream out) throws IOException {
        long now = db.now();
        for (Map.Entry<String, Object> entry : db.entries()) {
            String key = entry.getKey();
            long deadline = db.deadlineOf(key);
            if (deadline >= 0 && deadline <= now) {
                continue;
            }
            switch (entry.getValue()) {
                case String value -> RespWriter.writeCommand(List.of("SET", key, value), out);
                case ListValue list -> writeChunked(out, "RPUSH", key, list, 1);
                case SetValue set -> writeChunked(out, "SADD", key, set, 1);
                case HashValue hash -> {
                    List<String> flat = new ArrayList<>(hash.size() * 2);
                    hash.forEach((field, value) -> {
                        flat.add(field);
                        flat.add(value);
                    });
                    writeChunked(out, "HSET", key, flat, 2);
                }
                case ZSetValue zset -> {
                    List<String> flat = new ArrayList<>(zset.size() * 2);
                    for (SkipList.Node n = zset.ordered().first(); n != null; n = n.next()) {
                        flat.add(Numbers.formatDouble(n.score()));
                        flat.add(n.member());
                    }
                    writeChunked(out, "ZADD", key, flat, 2);
                }
                default -> throw new IllegalStateException("unknown value type " + entry.getValue().getClass());
            }
            if (deadline >= 0) {
                RespWriter.writeCommand(List.of("PEXPIREAT", key, Long.toString(deadline)), out);
            }
        }
    }

    /** {@code itemsPerElement} is 2 for field/value or score/member pairs, so a chunk never splits a pair. */
    private static void writeChunked(OutputStream out, String command, String key, Iterable<String> items,
                                     int itemsPerElement) throws IOException {
        List<String> argv = new ArrayList<>();
        int limit = 2 + ITEMS_PER_COMMAND * itemsPerElement;
        for (String item : items) {
            if (argv.isEmpty()) {
                argv.add(command);
                argv.add(key);
            }
            argv.add(item);
            if (argv.size() == limit) {
                RespWriter.writeCommand(argv, out);
                argv.clear();
            }
        }
        if (!argv.isEmpty()) {
            RespWriter.writeCommand(argv, out);
        }
    }
}
