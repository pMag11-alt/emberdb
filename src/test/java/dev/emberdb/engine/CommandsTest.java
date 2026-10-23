package dev.emberdb.engine;

import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.emberdb.Config;
import dev.emberdb.protocol.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {

    private ManualClock clock;
    private Engine engine;
    private TestClient c;

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        engine = new Engine(Config.defaults(), clock, new SplittableRandom(42));
        c = new TestClient(engine);
    }

    @Nested
    class Dispatch {
        @Test
        void unknownCommandsAndWrongArityAreErrors() {
            assertEquals("ERR unknown command 'nope', with args beginning with: 'a' ", c.error("nope", "a"));
            assertEquals("ERR wrong number of arguments for 'get' command", c.error("GET"));
            assertEquals("ERR wrong number of arguments for 'set' command", c.error("SET", "k"));
        }

        @Test
        void commandNamesAreCaseInsensitive() {
            assertEquals(Reply.OK, c.call("sEt", "k", "v"));
            assertEquals("v", c.bulk("GET", "k"));
        }

        @Test
        void wrongTypeIsReported() {
            c.call("RPUSH", "list", "a");
            assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", c.error("GET", "list"));
            assertEquals("WRONGTYPE Operation against a key holding the wrong kind of value", c.error("HSET", "list", "f", "v"));
        }

        @Test
        void pingAndEcho() {
            assertEquals(Reply.PONG, c.call("PING"));
            assertEquals("hi", c.bulk("PING", "hi"));
            assertEquals("hello", c.bulk("ECHO", "hello"));
        }
    }

    @Nested
    class Strings {
        @Test
        void setAndGet() {
            assertEquals(Reply.OK, c.call("SET", "k", "v"));
            assertEquals("v", c.bulk("GET", "k"));
            assertNull(c.bulk("GET", "missing"));
            assertEquals(1, c.integer("STRLEN", "k"));
        }

        @Test
        void binarySafeValues() {
            String binary = "\u0000ÿ\r\n\u0080";
            c.call("SET", "bin", binary);
            assertEquals(binary, c.bulk("GET", "bin"));
            assertEquals(5, c.integer("STRLEN", "bin"));
        }

        @Test
        void setNxXxAndGet() {
            assertEquals(Reply.OK, c.call("SET", "k", "1", "NX"));
            assertEquals(Reply.NIL, c.call("SET", "k", "2", "NX"));
            assertEquals(Reply.NIL, c.call("SET", "other", "2", "XX"));
            assertEquals("1", c.bulk("SET", "k", "3", "XX", "GET"));
            assertEquals("3", c.bulk("GET", "k"));
            assertEquals("ERR syntax error", c.error("SET", "k", "v", "NX", "XX"));
            assertEquals("ERR syntax error", c.error("SET", "k", "v", "EX", "10", "KEEPTTL"));
            assertEquals("ERR invalid expire time in 'set' command", c.error("SET", "k", "v", "EX", "0"));
        }

        @Test
        void setWithExpiryAndKeepTtl() {
            c.call("SET", "k", "v", "EX", "10");
            assertEquals(10, c.integer("TTL", "k"));
            c.call("SET", "k", "v2", "KEEPTTL");
            assertEquals(10, c.integer("TTL", "k"));
            c.call("SET", "k", "v3");
            assertEquals(-1, c.integer("TTL", "k"));
        }

        @Test
        void counters() {
            assertEquals(1, c.integer("INCR", "n"));
            assertEquals(11, c.integer("INCRBY", "n", "10"));
            assertEquals(10, c.integer("DECR", "n"));
            assertEquals(5, c.integer("DECRBY", "n", "5"));
            assertEquals("5.5", c.bulk("INCRBYFLOAT", "n", "0.5"));
            c.call("SET", "n", "abc");
            assertEquals("ERR value is not an integer or out of range", c.error("INCR", "n"));
            c.call("SET", "n", "007");
            assertEquals("ERR value is not an integer or out of range", c.error("INCR", "n"));
            c.call("SET", "n", Long.toString(Long.MAX_VALUE));
            assertEquals("ERR increment or decrement would overflow", c.error("INCR", "n"));
        }

        @Test
        void incrKeepsTheTtl() {
            c.call("SET", "n", "1", "EX", "100");
            c.call("INCR", "n");
            assertEquals(100, c.integer("TTL", "n"));
        }

        @Test
        void multiKeyCommands() {
            assertEquals(Reply.OK, c.call("MSET", "a", "1", "b", "2"));
            c.call("RPUSH", "list", "x");
            assertEquals(java.util.Arrays.asList("1", "2", null, null), c.strings("MGET", "a", "b", "missing", "list"));
            assertEquals(0, c.integer("MSETNX", "a", "9", "c", "3"));
            assertEquals(1, c.integer("MSETNX", "c", "3", "d", "4"));
            assertEquals("ERR wrong number of arguments for 'mset' command", c.error("MSET", "a", "1", "b"));
        }

        @Test
        void appendGetrangeGetdel() {
            assertEquals(5, c.integer("APPEND", "s", "Hello"));
            assertEquals(11, c.integer("APPEND", "s", " World"));
            assertEquals("World", c.bulk("GETRANGE", "s", "-5", "-1"));
            assertEquals("Hello", c.bulk("GETRANGE", "s", "0", "4"));
            assertEquals("", c.bulk("GETRANGE", "s", "20", "30"));
            assertEquals("Hello World", c.bulk("GETDEL", "s"));
            assertEquals(0, c.integer("EXISTS", "s"));
        }
    }

    @Nested
    class Keys {
        @Test
        void delExistsType() {
            c.call("MSET", "a", "1", "b", "2");
            c.call("SADD", "set", "x");
            assertEquals(3, c.integer("EXISTS", "a", "b", "a"));
            assertEquals("set", ((Reply.Simple) c.call("TYPE", "set")).text());
            assertEquals("none", ((Reply.Simple) c.call("TYPE", "nope")).text());
            assertEquals(2, c.integer("DEL", "a", "b", "nope"));
            assertEquals(1, c.integer("DBSIZE"));
        }

        @Test
        void keysPattern() {
            c.call("MSET", "user:1", "a", "user:2", "b", "order:1", "c");
            assertEquals(List.of("user:1", "user:2"), c.strings("KEYS", "user:*").stream().sorted().toList());
            assertEquals(List.of("order:1"), c.strings("KEYS", "o?der:[0-9]"));
        }

        @Test
        void renameKeepsTtl() {
            c.call("SET", "a", "1", "EX", "50");
            assertEquals(Reply.OK, c.call("RENAME", "a", "b"));
            assertEquals("1", c.bulk("GET", "b"));
            assertEquals(50, c.integer("TTL", "b"));
            assertEquals("ERR no such key", c.error("RENAME", "a", "c"));
            c.call("SET", "c", "3");
            assertEquals(0, c.integer("RENAMENX", "b", "c"));
        }

        @Test
        void expiryIsLazyAndActive() {
            c.call("SET", "lazy", "v", "PX", "100");
            c.call("SET", "active", "v", "PX", "100");
            c.call("SET", "forever", "v");
            clock.advance(100);
            assertNull(c.bulk("GET", "lazy")); // deleted on access
            assertEquals(2, engine.db().size()); // "active" is still physically there...
            engine.withLock(() -> engine.db().activeExpireCycle());
            assertEquals(1, engine.db().size()); // ...until the background cycle finds it
            assertEquals(2, engine.db().expiredKeys());
        }

        @Test
        void ttlFamily() {
            assertEquals(-2, c.integer("TTL", "nope"));
            c.call("SET", "k", "v");
            assertEquals(-1, c.integer("TTL", "k"));
            assertEquals(1, c.integer("EXPIRE", "k", "100"));
            assertEquals(100_000, c.integer("PTTL", "k"));
            assertEquals((clock.getAsLong() + 100_000) / 1000, c.integer("EXPIRETIME", "k"));
            assertEquals(1, c.integer("PERSIST", "k"));
            assertEquals(-1, c.integer("TTL", "k"));
            assertEquals(1, c.integer("PEXPIREAT", "k", Long.toString(clock.getAsLong() - 1)));
            assertEquals(0, c.integer("EXISTS", "k"));
        }

        @Test
        void expireOptions() {
            c.call("SET", "k", "v");
            assertEquals(0, c.integer("EXPIRE", "k", "100", "XX")); // no TTL yet
            assertEquals(1, c.integer("EXPIRE", "k", "100", "NX"));
            assertEquals(0, c.integer("EXPIRE", "k", "50", "GT"));
            assertEquals(1, c.integer("EXPIRE", "k", "200", "GT"));
            assertEquals(1, c.integer("EXPIRE", "k", "50", "LT"));
            assertEquals(50, c.integer("TTL", "k"));
        }
    }

    @Nested
    class Lists {
        @Test
        void pushPopRange() {
            assertEquals(3, c.integer("RPUSH", "l", "a", "b", "c"));
            assertEquals(5, c.integer("LPUSH", "l", "y", "z"));
            assertEquals(List.of("z", "y", "a", "b", "c"), c.strings("LRANGE", "l", "0", "-1"));
            assertEquals(List.of("a", "b"), c.strings("LRANGE", "l", "2", "3"));
            assertEquals("z", c.bulk("LPOP", "l"));
            assertEquals(List.of("c", "b"), c.strings("RPOP", "l", "2"));
            assertEquals(2, c.integer("LLEN", "l"));
            assertEquals("a", c.bulk("LINDEX", "l", "-1"));
        }

        @Test
        void emptyListsDisappear() {
            c.call("RPUSH", "l", "a");
            c.call("LPOP", "l");
            assertEquals(0, c.integer("EXISTS", "l"));
            assertEquals(Reply.NIL, c.call("LPOP", "l"));
            assertEquals(Reply.NIL_ARRAY, c.call("LPOP", "l", "3"));
            assertEquals(0, c.integer("LPUSHX", "l", "a"));
        }

        @Test
        void setTrimRemove() {
            c.call("RPUSH", "l", "a", "b", "a", "c", "a");
            assertEquals(Reply.OK, c.call("LSET", "l", "1", "B"));
            assertEquals("ERR index out of range", c.error("LSET", "l", "9", "x"));
            assertEquals(2, c.integer("LREM", "l", "2", "a"));
            assertEquals(List.of("B", "c", "a"), c.strings("LRANGE", "l", "0", "-1"));
            assertEquals(Reply.OK, c.call("LTRIM", "l", "1", "-1"));
            assertEquals(List.of("c", "a"), c.strings("LRANGE", "l", "0", "-1"));
            assertEquals(Reply.OK, c.call("LTRIM", "l", "5", "10"));
            assertEquals(0, c.integer("EXISTS", "l"));
        }
    }

    @Nested
    class Hashes {
        @Test
        void fields() {
            assertEquals(2, c.integer("HSET", "h", "name", "Ada", "year", "1815"));
            assertEquals(0, c.integer("HSET", "h", "name", "Ada Lovelace"));
            assertEquals("Ada Lovelace", c.bulk("HGET", "h", "name"));
            assertEquals(List.of("name", "Ada Lovelace", "year", "1815"), c.strings("HGETALL", "h"));
            assertEquals(java.util.Arrays.asList("1815", null), c.strings("HMGET", "h", "year", "nope"));
            assertEquals(1816, c.integer("HINCRBY", "h", "year", "1"));
            assertEquals("ERR hash value is not an integer", c.error("HINCRBY", "h", "name", "1"));
            assertEquals(0, c.integer("HSETNX", "h", "name", "x"));
            assertEquals(2, c.integer("HLEN", "h"));
            assertEquals(2, c.integer("HDEL", "h", "name", "year", "nope"));
            assertEquals(0, c.integer("EXISTS", "h"));
        }
    }

    @Nested
    class Sets {
        @Test
        void membersAndAlgebra() {
            assertEquals(3, c.integer("SADD", "a", "1", "2", "3"));
            assertEquals(0, c.integer("SADD", "a", "1"));
            c.call("SADD", "b", "2", "3", "4");
            assertEquals(List.of("2", "3"), c.strings("SINTER", "a", "b"));
            assertEquals(List.of("1", "2", "3", "4"), c.strings("SUNION", "a", "b"));
            assertEquals(List.of("1"), c.strings("SDIFF", "a", "b"));
            assertEquals(List.of(), c.strings("SINTER", "a", "missing"));
            assertEquals(2, c.integer("SINTERSTORE", "dest", "a", "b"));
            assertEquals(2, c.integer("SCARD", "dest"));
            assertEquals(1, c.integer("SISMEMBER", "a", "2"));
            assertEquals(1, c.integer("SREM", "a", "2", "9"));
        }

        @Test
        void spopRemovesDistinctMembers() {
            c.call("SADD", "s", "a", "b", "c", "d");
            List<String> popped = c.strings("SPOP", "s", "3");
            assertEquals(3, popped.stream().distinct().count());
            assertEquals(1, c.integer("SCARD", "s"));
            assertEquals(1, c.strings("SRANDMEMBER", "s", "-5").stream().distinct().count());
        }
    }

    @Nested
    class SortedSets {
        @BeforeEach
        void leaderboard() {
            c.call("ZADD", "board", "100", "alice", "250", "bob", "175", "carol", "175", "dave");
        }

        @Test
        void rangesAndRanks() {
            assertEquals(List.of("alice", "carol", "dave", "bob"), c.strings("ZRANGE", "board", "0", "-1"));
            assertEquals(List.of("bob", "250", "dave", "175"), c.strings("ZREVRANGE", "board", "0", "1", "WITHSCORES"));
            assertEquals(0, c.integer("ZRANK", "board", "alice"));
            assertEquals(0, c.integer("ZREVRANK", "board", "bob"));
            assertEquals("175", c.bulk("ZSCORE", "board", "carol"));
            assertEquals(4, c.integer("ZCARD", "board"));
        }

        @Test
        void scoreRanges() {
            assertEquals(List.of("carol", "dave"), c.strings("ZRANGEBYSCORE", "board", "150", "200"));
            assertEquals(List.of("bob"), c.strings("ZRANGEBYSCORE", "board", "(175", "+inf"));
            assertEquals(List.of("dave", "carol"), c.strings("ZREVRANGEBYSCORE", "board", "200", "-inf", "LIMIT", "0", "2"));
            assertEquals(List.of("dave"), c.strings("ZRANGEBYSCORE", "board", "-inf", "+inf", "LIMIT", "2", "1"));
            assertEquals(3, c.integer("ZCOUNT", "board", "100", "(250"));
            assertEquals("ERR min or max is not a float", c.error("ZCOUNT", "board", "low", "high"));
        }

        @Test
        void zaddOptions() {
            assertEquals(0, c.integer("ZADD", "board", "NX", "1", "alice"));
            assertEquals("100", c.bulk("ZSCORE", "board", "alice"));
            assertEquals(1, c.integer("ZADD", "board", "XX", "CH", "120", "alice"));
            assertEquals(0, c.integer("ZADD", "board", "GT", "CH", "50", "alice"));
            assertEquals("120", c.bulk("ZSCORE", "board", "alice"));
            assertEquals("125.5", c.bulk("ZADD", "board", "INCR", "5.5", "alice"));
            assertEquals("130.5", c.bulk("ZINCRBY", "board", "5", "alice"));
            assertEquals("ERR value is not a valid float", c.error("ZADD", "board", "x", "alice"));
            assertEquals("ERR XX and NX options at the same time are not compatible",
                    c.error("ZADD", "board", "NX", "XX", "1", "a"));
        }

        @Test
        void popAndRemove() {
            assertEquals(List.of("alice", "100"), c.strings("ZPOPMIN", "board"));
            assertEquals(List.of("bob", "250"), c.strings("ZPOPMAX", "board"));
            assertEquals(2, c.integer("ZREM", "board", "carol", "dave", "nobody"));
            assertEquals(0, c.integer("EXISTS", "board"));
        }
    }

    @Nested
    class Server {
        @Test
        void infoHasSections() {
            c.call("SET", "a", "1");
            String info = c.bulk("INFO");
            assertTrue(info.contains("# Server\r\nredis_version:7.2.0"));
            assertTrue(info.contains("db0:keys=1,expires=0"));
            String keyspace = c.bulk("INFO", "keyspace");
            assertTrue(keyspace.startsWith("# Keyspace"));
            assertTrue(!keyspace.contains("# Server"));
        }

        @Test
        void flushall() {
            c.call("MSET", "a", "1", "b", "2");
            assertEquals(Reply.OK, c.call("FLUSHALL"));
            assertEquals(0, c.integer("DBSIZE"));
        }

        @Test
        void configGet() {
            assertEquals(List.of("appendonly", "no"), c.strings("CONFIG", "GET", "appendonly"));
            assertEquals(List.of("port", "6379"), c.strings("CONFIG", "GET", "po*"));
        }

        @Test
        void selectOnlyDatabaseZero() {
            assertEquals(Reply.OK, c.call("SELECT", "0"));
            assertEquals("ERR DB index is out of range", c.error("SELECT", "1"));
        }

        @Test
        void clientName() {
            assertEquals(Reply.NIL, c.call("CLIENT", "GETNAME"));
            assertEquals(Reply.OK, c.call("CLIENT", "SETNAME", "worker-1"));
            assertEquals("worker-1", c.bulk("CLIENT", "GETNAME"));
            assertTrue(c.bulk("CLIENT", "LIST").contains("name=worker-1"));
        }
    }

    @Nested
    class Eviction {
        @Test
        void leastRecentlyUsedKeyIsEvicted() {
            Engine small = new Engine(Config.defaults().withMaxKeys(3), clock, new SplittableRandom(1));
            TestClient client = new TestClient(small);
            client.call("SET", "a", "1");
            client.call("SET", "b", "2");
            client.call("SET", "c", "3");
            client.call("GET", "a"); // a is now the most recently used, b the least
            client.call("SET", "d", "4");
            assertEquals(List.of("a", "c", "d"), client.strings("KEYS", "*").stream().sorted().toList());
            assertEquals(1, small.db().evictedKeys());
        }
    }
}
