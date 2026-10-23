package dev.emberdb;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.emberdb.protocol.Reply;
import dev.emberdb.protocol.RespWriter;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Talks to a real server over TCP, byte for byte as a Redis client would. */
@Timeout(30)
class ServerIntegrationTest {

    private EmberServer server;

    @BeforeEach
    void start() throws IOException {
        server = new EmberServer(Config.defaults().withPort(0));
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void speaksResp() throws IOException {
        try (Client client = new Client(server.port())) {
            assertEquals(Reply.PONG, client.call("PING"));
            assertEquals(Reply.OK, client.call("SET", "k", "hello"));
            assertEquals(Reply.bulk("hello"), client.call("GET", "k"));
            assertEquals(Reply.NIL, client.call("GET", "missing"));
            assertEquals(Reply.bulks("hello", null), client.call("MGET", "k", "missing"));
            assertInstanceOf(Reply.Err.class, client.call("NOPE"));
        }
    }

    @Test
    void speaksResp3AfterHello() throws IOException {
        try (Client client = new Client(server.port())) {
            assertInstanceOf(Reply.Dict.class, client.call("HELLO", "3"));
            client.call("HSET", "h", "a", "1");
            assertEquals(Reply.dict(List.of(Reply.bulk("a"), Reply.bulk("1"))), client.call("HGETALL", "h"));
            assertEquals(Reply.NIL, client.call("GET", "missing"));
            client.call("ZADD", "z", "2.5", "x");
            assertEquals(Reply.dbl(2.5), client.call("ZSCORE", "z", "x"));
            assertEquals(Reply.array(Reply.array(Reply.bulk("x"), Reply.dbl(2.5))),
                    client.call("ZRANGE", "z", "0", "-1", "WITHSCORES"));
            assertEquals(Reply.push(Reply.bulk("subscribe"), Reply.bulk("c"), Reply.integer(1)),
                    client.call("SUBSCRIBE", "c"));
        }
    }

    @Test
    void acceptsInlineCommands() throws IOException {
        try (Client client = new Client(server.port())) {
            client.out.write("SET city \"New York\"\r\nGET city\r\n".getBytes(ISO_8859_1));
            client.out.flush();
            assertEquals(Reply.OK, client.read());
            assertEquals(Reply.bulk("New York"), client.read());
        }
    }

    @Test
    void pipelinedCommandsGetRepliesInOrder() throws IOException {
        try (Client client = new Client(server.port())) {
            for (int i = 0; i < 1000; i++) {
                client.out.write(RespWriter.encodeCommand("INCR", "n"));
            }
            client.out.flush();
            for (int i = 1; i <= 1000; i++) {
                assertEquals(Reply.integer(i), client.read());
            }
        }
    }

    /** INCR is atomic: 8 connections × 2,000 increments must give exactly 16,000. */
    @Test
    void concurrentIncrementsAreNotLost() throws Exception {
        int clients = 8;
        int perClient = 2_000;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < clients; t++) {
                futures.add(pool.submit(() -> {
                    try (Client client = new Client(server.port())) {
                        for (int i = 0; i < perClient; i++) {
                            client.call("INCR", "shared");
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        try (Client client = new Client(server.port())) {
            assertEquals(Reply.bulk(Integer.toString(clients * perClient)), client.call("GET", "shared"));
        }
    }

    @Test
    void publishReachesSubscriberOnAnotherConnection() throws IOException {
        try (Client subscriber = new Client(server.port()); Client publisher = new Client(server.port())) {
            assertEquals(Reply.array(Reply.bulk("subscribe"), Reply.bulk("chat"), Reply.integer(1)),
                    subscriber.call("SUBSCRIBE", "chat"));
            assertEquals(Reply.integer(1), publisher.call("PUBLISH", "chat", "hi there"));
            assertEquals(Reply.bulks("message", "chat", "hi there"), subscriber.read());
        }
    }

    @Test
    void protocolErrorsCloseTheConnection() throws IOException {
        try (Client client = new Client(server.port())) {
            client.out.write("*1\r\n:oops\r\n".getBytes(ISO_8859_1));
            client.out.flush();
            Reply reply = client.read();
            assertTrue(assertInstanceOf(Reply.Err.class, reply).message().startsWith("ERR Protocol error"));
            assertEquals(-1, client.in.read());
        }
    }

    @Test
    void quitClosesTheConnection() throws IOException {
        try (Client client = new Client(server.port())) {
            assertEquals(Reply.OK, client.call("QUIT"));
            assertEquals(-1, client.in.read());
        }
    }

    /** A minimal RESP client: encodes commands and decodes RESP2/RESP3 replies into {@link Reply} values. */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        Reply call(String... argv) throws IOException {
            out.write(RespWriter.encodeCommand(argv));
            out.flush();
            return read();
        }

        Reply read() throws IOException {
            int type = in.read();
            String line = line();
            return switch (type) {
                case '_' -> Reply.NIL;
                case ',' -> Reply.dbl(Double.parseDouble(line));
                case '%' -> Reply.dict(readItems(2 * Integer.parseInt(line)));
                case '~' -> new Reply.SetOf(readItems(Integer.parseInt(line)));
                case '>' -> new Reply.Push(readItems(Integer.parseInt(line)));
                case '+' -> Reply.simple(line);
                case '-' -> Reply.error(line);
                case ':' -> Reply.integer(Long.parseLong(line));
                case '$' -> {
                    int length = Integer.parseInt(line);
                    if (length < 0) {
                        yield Reply.NIL;
                    }
                    String value = new String(in.readNBytes(length), ISO_8859_1);
                    in.readNBytes(2);
                    yield Reply.bulk(value);
                }
                case '*' -> {
                    int count = Integer.parseInt(line);
                    if (count < 0) {
                        yield Reply.NIL_ARRAY;
                    }
                    List<Reply> items = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        items.add(read());
                    }
                    yield Reply.array(items);
                }
                default -> throw new IOException("unexpected reply type " + type);
            };
        }

        private List<Reply> readItems(int count) throws IOException {
            List<Reply> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                items.add(read());
            }
            return items;
        }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != '\r') {
                if (b < 0) {
                    throw new IOException("connection closed");
                }
                sb.append((char) b);
            }
            in.read();
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
