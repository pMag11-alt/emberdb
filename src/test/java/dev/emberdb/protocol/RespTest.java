package dev.emberdb.protocol;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespTest {

    private static RespReader reader(String data) {
        return new RespReader(new ByteArrayInputStream(data.getBytes(ISO_8859_1)));
    }

    @Test
    void readsMultiBulkCommands() throws IOException {
        RespReader r = reader("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n");
        assertEquals(List.of("SET", "key", "value"), r.readCommand());
        assertNull(r.readCommand());
    }

    @Test
    void bulkStringsAreBinarySafe() throws IOException {
        RespReader r = reader("*2\r\n$4\r\nECHO\r\n$4\r\na\r\nb\r\n");
        assertEquals(List.of("ECHO", "a\r\nb"), r.readCommand());
    }

    @Test
    void readsInlineCommandsWithQuotes() throws IOException {
        RespReader r = reader("SET greeting \"hello world\"\r\nPING\n\r\n");
        assertEquals(List.of("SET", "greeting", "hello world"), r.readCommand());
        assertEquals(List.of("PING"), r.readCommand());
        assertEquals(List.of(), r.readCommand());
    }

    @Test
    void reportsPipelinedInputAndPositions() throws IOException {
        String ping = "*1\r\n$4\r\nPING\r\n";
        RespReader r = reader(ping + ping);
        r.readCommand();
        assertTrue(r.hasBufferedInput());
        assertEquals(ping.length(), r.position());
        r.readCommand();
        assertFalse(r.hasBufferedInput());
        assertEquals(2L * ping.length(), r.position());
    }

    @Test
    void readsValuesLargerThanTheBuffer() throws IOException {
        String big = "x".repeat(100_000);
        RespReader r = reader("*2\r\n$3\r\nSET\r\n$100000\r\n" + big + "\r\n*1\r\n$4\r\nPING\r\n");
        assertEquals(big, r.readCommand().get(1));
        assertEquals(List.of("PING"), r.readCommand());
    }

    /** Delivers one byte per read() call, the worst case for a parser that assumes whole messages arrive. */
    @Test
    void handlesCommandsSplitAcrossReads() throws IOException {
        byte[] data = "*2\r\n$3\r\nGET\r\n$1\r\nk\r\n".getBytes(ISO_8859_1);
        InputStream trickle = new InputStream() {
            private int i;

            @Override
            public int read() {
                return i < data.length ? data[i++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (i >= data.length) {
                    return -1;
                }
                b[off] = data[i++];
                return 1;
            }
        };
        assertEquals(List.of("GET", "k"), new RespReader(trickle).readCommand());
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(ProtocolException.class, () -> reader("*1\r\n:3\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> reader("*x\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> reader("*1\r\n$-5\r\n").readCommand());
        assertThrows(ProtocolException.class, () -> reader("SET \"unterminated\r\n").readCommand());
        assertThrows(EOFException.class, () -> reader("*2\r\n$3\r\nGET\r\n").readCommand());
    }

    @Test
    void writesEveryReplyType() {
        assertEquals("+OK\r\n", encode(Reply.OK));
        assertEquals("-ERR bad  thing\r\n", encode(Reply.error("ERR bad\r\nthing"))); // CR and LF become spaces
        assertEquals(":-7\r\n", encode(Reply.integer(-7)));
        assertEquals("$5\r\nhello\r\n", encode(Reply.bulk("hello")));
        assertEquals("$-1\r\n", encode(Reply.NIL));
        assertEquals("*-1\r\n", encode(Reply.NIL_ARRAY));
        assertEquals("*2\r\n$1\r\na\r\n:1\r\n", encode(Reply.array(Reply.bulk("a"), Reply.ONE)));
        assertEquals("*1\r\n$4\r\nPING\r\n", new String(RespWriter.encodeCommand("PING"), ISO_8859_1));
    }

    /** Expected bytes were captured from a real Redis 8 server talking RESP3. */
    @Test
    void writesResp3TypesAndTheirResp2Fallbacks() {
        assertEquals("_\r\n", encode3(Reply.NIL));
        assertEquals("_\r\n", encode3(Reply.NIL_ARRAY));
        assertEquals(",1.5\r\n", encode3(Reply.dbl(1.5)));
        assertEquals("$3\r\n1.5\r\n", encode(Reply.dbl(1.5)));

        Reply map = Reply.dict(List.of(Reply.bulk("a"), Reply.bulk("1")));
        assertEquals("%1\r\n$1\r\na\r\n$1\r\n1\r\n", encode3(map));
        assertEquals("*2\r\n$1\r\na\r\n$1\r\n1\r\n", encode(map));

        assertEquals("~1\r\n$1\r\nx\r\n", encode3(Reply.setOf(List.of("x"))));
        assertEquals(">2\r\n$7\r\nmessage\r\n:1\r\n", encode3(Reply.push(Reply.bulk("message"), Reply.ONE)));
        assertEquals("=8\r\ntxt:info\r\n", encode3(Reply.verbatim("info")));

        Reply withScores = Reply.pairs(List.of(Reply.bulk("x"), Reply.dbl(2.5), Reply.bulk("y"), Reply.dbl(3)));
        assertEquals("*2\r\n*2\r\n$1\r\nx\r\n,2.5\r\n*2\r\n$1\r\ny\r\n,3\r\n", encode3(withScores));
        assertEquals("*4\r\n$1\r\nx\r\n$3\r\n2.5\r\n$1\r\ny\r\n$1\r\n3\r\n", encode(withScores));
    }

    private static String encode(Reply reply) {
        return new String(RespWriter.encode(reply), ISO_8859_1);
    }

    private static String encode3(Reply reply) {
        return new String(RespWriter.encode(reply, 3), ISO_8859_1);
    }
}
