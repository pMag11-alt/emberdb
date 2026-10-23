package dev.emberdb.engine;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.emberdb.Config;
import dev.emberdb.protocol.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionTest {

    private Engine engine;
    private TestClient alice;
    private TestClient bob;

    @BeforeEach
    void setUp() {
        engine = new Engine(Config.defaults());
        alice = new TestClient(engine);
        bob = new TestClient(engine);
    }

    @Test
    void execRunsQueuedCommandsInOrder() {
        assertEquals(Reply.OK, alice.call("MULTI"));
        assertEquals(Reply.QUEUED, alice.call("SET", "balance", "100"));
        assertEquals(Reply.QUEUED, alice.call("INCRBY", "balance", "-30"));
        assertEquals(Reply.QUEUED, alice.call("GET", "balance"));
        assertEquals(Reply.array(Reply.OK, Reply.integer(70), Reply.bulk("70")), alice.call("EXEC"));
    }

    @Test
    void queuedCommandsAreInvisibleUntilExec() {
        alice.call("MULTI");
        alice.call("SET", "k", "v");
        assertEquals(Reply.NIL, bob.call("GET", "k"));
        alice.call("EXEC");
        assertEquals("v", bob.bulk("GET", "k"));
    }

    @Test
    void runtimeErrorsDoNotAbortTheRest() {
        alice.call("SET", "s", "text");
        alice.call("MULTI");
        alice.call("INCR", "s");
        alice.call("SET", "t", "ok");
        List<Reply> results = ((Reply.Multi) alice.call("EXEC")).items();
        assertEquals(Reply.error("ERR value is not an integer or out of range"), results.get(0));
        assertEquals(Reply.OK, results.get(1));
    }

    @Test
    void queueTimeErrorsAbortTheTransaction() {
        alice.call("MULTI");
        alice.call("SET", "k", "v");
        assertEquals("ERR unknown command 'BOGUS', with args beginning with: ", alice.error("BOGUS"));
        assertEquals("EXECABORT Transaction discarded because of previous errors.", alice.error("EXEC"));
        assertEquals(0, alice.integer("EXISTS", "k"));
    }

    @Test
    void discardAndNestingErrors() {
        assertEquals("ERR EXEC without MULTI", alice.error("EXEC"));
        assertEquals("ERR DISCARD without MULTI", alice.error("DISCARD"));
        alice.call("MULTI");
        assertEquals("ERR MULTI calls can not be nested", alice.error("MULTI"));
        alice.call("SET", "k", "v");
        assertEquals(Reply.OK, alice.call("DISCARD"));
        assertEquals(0, alice.integer("EXISTS", "k"));
    }

    @Test
    void watchAbortsWhenAnotherClientWrites() {
        alice.call("SET", "stock", "10");
        alice.call("WATCH", "stock");
        bob.call("DECR", "stock"); // someone else buys first
        alice.call("MULTI");
        alice.call("DECR", "stock");
        assertEquals(Reply.NIL_ARRAY, alice.call("EXEC"));
        assertEquals("9", alice.bulk("GET", "stock"));
    }

    @Test
    void watchSucceedsWhenNothingChanged() {
        alice.call("SET", "stock", "10");
        alice.call("WATCH", "stock");
        bob.call("SET", "unrelated", "x");
        alice.call("MULTI");
        alice.call("DECR", "stock");
        assertEquals(Reply.array(Reply.integer(9)), alice.call("EXEC"));
    }

    @Test
    void watchSeesExpiryAndFlush() {
        alice.call("SET", "k", "v");
        alice.call("WATCH", "k");
        bob.call("FLUSHALL");
        alice.call("MULTI");
        alice.call("SET", "k", "v2");
        assertEquals(Reply.NIL_ARRAY, alice.call("EXEC"));
    }

    @Test
    void execClearsWatches() {
        alice.call("WATCH", "k");
        alice.call("MULTI");
        alice.call("EXEC");
        bob.call("SET", "k", "changed");
        alice.call("MULTI");
        alice.call("GET", "k");
        assertEquals(Reply.array(Reply.bulk("changed")), alice.call("EXEC"));
    }

    @Test
    void watchInsideMultiIsRejected() {
        alice.call("MULTI");
        assertEquals("ERR WATCH inside MULTI is not allowed", alice.error("WATCH", "k"));
    }
}
