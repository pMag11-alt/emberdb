package dev.emberdb.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.emberdb.Config;
import dev.emberdb.engine.Engine;
import dev.emberdb.engine.ManualClock;
import dev.emberdb.engine.TestClient;
import dev.emberdb.persistence.AppendOnlyFile.Fsync;
import dev.emberdb.protocol.RespWriter;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AofTest {

    @TempDir
    Path dir;

    private final ManualClock clock = new ManualClock();
    private Engine engine;

    @AfterEach
    void tearDown() throws IOException {
        if (engine != null) {
            engine.close();
        }
    }

    /** Starts a fresh engine on the same directory, as a server restart would. */
    private TestClient restart() throws IOException {
        if (engine != null) {
            engine.close();
        }
        engine = new Engine(Config.defaults().withAppendOnly(dir, Fsync.ALWAYS), clock, new SplittableRandom(1));
        engine.start();
        return new TestClient(engine);
    }

    private String aofText() throws IOException {
        return Files.readString(dir.resolve("appendonly.aof"), ISO_8859_1);
    }

    @Test
    void writesSurviveARestart() throws IOException {
        TestClient c = restart();
        c.call("SET", "name", "ember");
        c.call("RPUSH", "queue", "a", "b", "c");
        c.call("LPOP", "queue");
        c.call("HSET", "user", "id", "7");
        c.call("ZADD", "board", "1.5", "x", "2", "y");
        c.call("SADD", "tags", "java", "redis");
        c.call("INCR", "visits");
        c.call("DEL", "tags");

        c = restart();
        assertEquals("ember", c.bulk("GET", "name"));
        assertEquals(List.of("b", "c"), c.strings("LRANGE", "queue", "0", "-1"));
        assertEquals("7", c.bulk("HGET", "user", "id"));
        assertEquals(List.of("x", "1.5", "y", "2"), c.strings("ZRANGE", "board", "0", "-1", "WITHSCORES"));
        assertEquals(0, c.integer("EXISTS", "tags"));
        assertEquals("1", c.bulk("GET", "visits"));
    }

    @Test
    void readsAndFailedWritesAreNotLogged() throws IOException {
        TestClient c = restart();
        c.call("GET", "nothing");
        c.call("SET", "k", "v", "NX");
        c.call("SET", "k", "v2", "NX"); // does nothing, so it is not logged
        c.call("LPUSH", "k", "x"); // WRONGTYPE error
        assertEquals(new String(RespWriter.encodeCommand("SET", "k", "v"), ISO_8859_1), aofText());
    }

    @Test
    void relativeExpiriesAreLoggedAsAbsoluteDeadlines() throws IOException {
        TestClient c = restart();
        c.call("SET", "session", "abc", "EX", "60");
        c.call("SET", "other", "x");
        c.call("EXPIRE", "other", "30");
        long deadline = clock.getAsLong() + 30_000;
        assertTrue(aofText().contains("PEXPIREAT\r\n$5\r\nother\r\n$13\r\n" + deadline));

        clock.advance(40_000); // the server is down for 40 seconds
        c = restart();
        assertEquals(20, c.integer("TTL", "session")); // 60 - 40, not a fresh 60
        assertNull(c.bulk("GET", "other")); // already expired
    }

    @Test
    void randomCommandsAreLoggedAsTheirEffect() throws IOException {
        TestClient c = restart();
        c.call("SADD", "s", "a", "b", "c", "d");
        List<String> popped = c.strings("SPOP", "s", "2");
        assertTrue(aofText().contains("SREM"));
        assertFalse(aofText().contains("SPOP"));

        c = restart();
        for (String member : popped) {
            assertEquals(0, c.integer("SISMEMBER", "s", member));
        }
        assertEquals(2, c.integer("SCARD", "s"));
    }

    @Test
    void transactionsAreWrappedInMultiExec() throws IOException {
        TestClient c = restart();
        c.call("MULTI");
        c.call("SET", "a", "1");
        c.call("INCR", "a");
        c.call("EXEC");
        String expected = new String(RespWriter.encodeCommand("MULTI"), ISO_8859_1)
                + new String(RespWriter.encodeCommand("SET", "a", "1"), ISO_8859_1)
                + new String(RespWriter.encodeCommand("INCR", "a"), ISO_8859_1)
                + new String(RespWriter.encodeCommand("EXEC"), ISO_8859_1);
        assertEquals(expected, aofText());
    }

    /** A crash halfway through writing a command leaves a partial record; it is dropped and the file repaired. */
    @Test
    void truncatedTailIsDropped() throws IOException {
        TestClient c = restart();
        c.call("SET", "safe", "1");
        engine.close();
        engine = null;
        Path aof = dir.resolve("appendonly.aof");
        long goodLength = Files.size(aof);
        Files.writeString(aof, "*3\r\n$3\r\nSET\r\n$4\r\nlost", ISO_8859_1, APPEND);

        c = restart();
        assertEquals("1", c.bulk("GET", "safe"));
        assertEquals(0, c.integer("EXISTS", "lost"));
        assertEquals(goodLength, Files.size(aof));
    }

    @Test
    void unfinishedTransactionIsDroppedAsAWhole() throws IOException {
        TestClient c = restart();
        c.call("SET", "before", "1");
        engine.close();
        engine = null;
        Path aof = dir.resolve("appendonly.aof");
        Files.write(aof, RespWriter.encodeCommand("MULTI"), APPEND);
        Files.write(aof, RespWriter.encodeCommand("SET", "half", "1"), APPEND);

        c = restart();
        assertEquals("1", c.bulk("GET", "before"));
        assertEquals(0, c.integer("EXISTS", "half"));
    }

    @Test
    void rewriteCompactsTheLogAndKeepsTheData() throws IOException {
        TestClient c = restart();
        for (int i = 0; i < 500; i++) {
            c.call("INCR", "counter");
        }
        c.call("RPUSH", "list", "a", "b");
        for (int i = 0; i < 100; i++) {
            c.call("ZADD", "z", Integer.toString(i), "m" + i);
        }
        c.call("SET", "ttl", "x", "PX", "5000");
        long before = Files.size(dir.resolve("appendonly.aof"));

        c.call("BGREWRITEAOF");
        long after = Files.size(dir.resolve("appendonly.aof"));
        assertTrue(after < before / 3, "expected the rewrite to shrink the file, " + before + " -> " + after);

        c.call("SET", "after-rewrite", "1"); // appended to the new file
        c = restart();
        assertEquals("500", c.bulk("GET", "counter"));
        assertEquals(List.of("a", "b"), c.strings("LRANGE", "list", "0", "-1"));
        assertEquals(100, c.integer("ZCARD", "z"));
        assertEquals(5000, c.integer("PTTL", "ttl"));
        assertEquals("1", c.bulk("GET", "after-rewrite"));
    }

    @Test
    void evictionsAreLoggedSoReplayKeepsTheSameKeys() throws IOException {
        engine = new Engine(Config.defaults().withMaxKeys(2).withAppendOnly(dir, Fsync.ALWAYS), clock,
                new SplittableRandom(1));
        engine.start();
        TestClient c = new TestClient(engine);
        c.call("SET", "a", "1");
        c.call("SET", "b", "2");
        c.call("GET", "a"); // reads are not logged, so the replay would evict a different key without the DEL
        c.call("SET", "c", "3");
        engine.close();

        engine = new Engine(Config.defaults().withAppendOnly(dir, Fsync.ALWAYS), clock, new SplittableRandom(1));
        engine.start();
        c = new TestClient(engine);
        assertEquals(List.of("a", "c"), c.strings("KEYS", "*").stream().sorted().toList());
    }
}
