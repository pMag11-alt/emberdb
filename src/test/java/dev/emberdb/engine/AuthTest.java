package dev.emberdb.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.emberdb.Config;
import dev.emberdb.protocol.Reply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AuthTest {

    @Test
    void commandsNeedAuthWhenAPasswordIsSet() {
        TestClient c = new TestClient(new Engine(Config.defaults().withRequirePass("s3cret")));
        assertEquals("NOAUTH Authentication required.", c.error("GET", "k"));
        assertEquals("WRONGPASS invalid username-password pair or user is disabled.", c.error("AUTH", "guess"));
        assertEquals(Reply.OK, c.call("AUTH", "s3cret"));
        assertEquals(Reply.NIL, c.call("GET", "k"));
    }

    @Test
    void helloCanAuthenticate() {
        TestClient c = new TestClient(new Engine(Config.defaults().withRequirePass("pw")));
        assertInstanceOf(Reply.Err.class, c.call("HELLO", "2"));
        assertInstanceOf(Reply.Dict.class, c.call("HELLO", "2", "AUTH", "default", "pw", "SETNAME", "app"));
        assertEquals("app", c.bulk("CLIENT", "GETNAME"));
        assertEquals("NOPROTO unsupported protocol version", c.error("HELLO", "4"));
        List<Reply> hello = assertInstanceOf(Reply.Dict.class, c.call("HELLO", "3")).flat();
        assertEquals(List.of(Reply.bulk("proto"), Reply.integer(3)), hello.subList(4, 6));
        assertEquals(3, c.session().protocol());
    }

    @Test
    void authWithoutPasswordConfiguredIsAnError() {
        TestClient c = new TestClient(new Engine(Config.defaults()));
        assertEquals("ERR AUTH <password> called without any password configured for the default user. Are you sure"
                + " your configuration is correct?", c.error("AUTH", "x"));
    }
}
