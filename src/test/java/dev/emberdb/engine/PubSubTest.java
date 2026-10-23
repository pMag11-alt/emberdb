package dev.emberdb.engine;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.emberdb.Config;
import dev.emberdb.protocol.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PubSubTest {

    private Engine engine;
    private TestClient subscriber;
    private TestClient publisher;

    @BeforeEach
    void setUp() {
        engine = new Engine(Config.defaults());
        subscriber = new TestClient(engine);
        publisher = new TestClient(engine);
    }

    @Test
    void subscribeConfirmsEachChannel() {
        List<Reply> replies = subscriber.callAll("SUBSCRIBE", "news", "sport");
        assertEquals(List.of(
                Reply.push(Reply.bulk("subscribe"), Reply.bulk("news"), Reply.integer(1)),
                Reply.push(Reply.bulk("subscribe"), Reply.bulk("sport"), Reply.integer(2))), replies);
    }

    @Test
    void publishDeliversToChannelAndPatternSubscribers() {
        subscriber.call("SUBSCRIBE", "orders");
        TestClient watcher = new TestClient(engine);
        watcher.call("PSUBSCRIBE", "order*");

        assertEquals(2, publisher.integer("PUBLISH", "orders", "#1042 shipped"));
        assertEquals(List.of("message", "orders", "#1042 shipped"), TestClient.strings(subscriber.received().getLast()));
        assertEquals(List.of("pmessage", "order*", "orders", "#1042 shipped"),
                TestClient.strings(watcher.received().getLast()));
        assertEquals(0, publisher.integer("PUBLISH", "nobody-listens", "hello"));
    }

    @Test
    void subscribedClientsCanOnlyUsePubSubCommands() {
        subscriber.call("SUBSCRIBE", "news");
        assertEquals("ERR Can't execute 'get': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET are"
                + " allowed in this context", subscriber.error("GET", "k"));
        assertEquals(Reply.bulks("pong", ""), subscriber.call("PING"));
    }

    /** RESP3 marks messages as pushes, so a subscribed RESP3 connection may run any command. */
    @Test
    void resp3ClientsCanRunCommandsWhileSubscribed() {
        subscriber.call("HELLO", "3");
        subscriber.call("SUBSCRIBE", "news");
        publisher.call("SET", "k", "v");
        assertEquals("v", subscriber.bulk("GET", "k"));
        assertEquals(Reply.PONG, subscriber.call("PING"));
    }

    @Test
    void unsubscribeWithoutArgumentsLeavesEverything() {
        subscriber.call("SUBSCRIBE", "a", "b");
        List<Reply> replies = subscriber.callAll("UNSUBSCRIBE");
        assertEquals(2, replies.size());
        assertEquals(Reply.push(Reply.bulk("unsubscribe"), Reply.bulk("b"), Reply.integer(0)), replies.getLast());
        assertEquals(Reply.PONG, subscriber.call("PING")); // back to normal mode
        assertEquals(0, publisher.integer("PUBLISH", "a", "x"));
    }

    @Test
    void disconnectRemovesSubscriptions() {
        subscriber.call("SUBSCRIBE", "news");
        subscriber.disconnect();
        assertEquals(0, publisher.integer("PUBLISH", "news", "anyone?"));
        assertEquals(List.of(), publisher.strings("PUBSUB", "CHANNELS"));
    }

    @Test
    void pubsubIntrospection() {
        subscriber.call("SUBSCRIBE", "news");
        assertEquals(List.of("news"), publisher.strings("PUBSUB", "CHANNELS", "n*"));
        assertEquals(Reply.array(Reply.bulk("news"), Reply.integer(1), Reply.bulk("x"), Reply.integer(0)),
                publisher.call("PUBSUB", "NUMSUB", "news", "x"));
    }
}
