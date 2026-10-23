package dev.emberdb.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * Reads client commands from a byte stream. Two formats are accepted, like Redis:
 * <ul>
 *   <li>multi-bulk: {@code *2\r\n$3\r\nGET\r\n$3\r\nkey\r\n} (what client libraries send)</li>
 *   <li>inline: {@code GET key\r\n} (handy from telnet or netcat)</li>
 * </ul>
 * The reader keeps its own buffer so the server can tell whether more pipelined commands are already
 * waiting, and so the append-only-file loader knows the exact byte offset of every command.
 */
public final class RespReader {

    public static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;
    public static final int MAX_ARGS = 1024 * 1024;
    private static final int MAX_INLINE_LENGTH = 64 * 1024;

    private final InputStream in;
    private final byte[] buf = new byte[16 * 1024];
    private int pos;
    private int limit;
    /** Number of stream bytes consumed before {@code buf[0]}. */
    private long base;

    public RespReader(InputStream in) {
        this.in = in;
    }

    /**
     * Returns the next command's arguments, an empty list for a blank or zero-length command, or {@code null} if
     * the stream ended cleanly between commands.
     *
     * @throws EOFException if the stream ends in the middle of a command
     * @throws ProtocolException if the bytes are not valid RESP
     */
    public List<String> readCommand() throws IOException {
        if (pos == limit && !fill()) {
            return null;
        }
        if (buf[pos] == '*') {
            pos++;
            return readMultiBulk();
        }
        return readInline();
    }

    /** True if bytes of another command are already buffered, so a reply flush can wait. */
    public boolean hasBufferedInput() {
        return pos < limit;
    }

    /** Offset of the next unread byte from the start of the stream. */
    public long position() {
        return base + pos;
    }

    private List<String> readMultiBulk() throws IOException {
        long count = readLength("multibulk length");
        if (count <= 0) {
            return List.of();
        }
        if (count > MAX_ARGS) {
            throw new ProtocolException("invalid multibulk length");
        }
        List<String> args = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            int type = readByte();
            if (type != '$') {
                throw new ProtocolException("expected '$', got '" + printable(type) + "'");
            }
            long length = readLength("bulk length");
            if (length < 0 || length > MAX_BULK_LENGTH) {
                throw new ProtocolException("invalid bulk length");
            }
            args.add(readBulk((int) length));
            if (readByte() != '\r' || readByte() != '\n') {
                throw new ProtocolException("expected CRLF after bulk string");
            }
        }
        return args;
    }

    /** Reads a decimal number terminated by CRLF. */
    private long readLength(String what) throws IOException {
        int b = readByte();
        boolean negative = b == '-';
        if (negative) {
            b = readByte();
        }
        long value = 0;
        int digits = 0;
        while (b != '\r') {
            if (b < '0' || b > '9' || ++digits > 18) {
                throw new ProtocolException("invalid " + what);
            }
            value = value * 10 + (b - '0');
            b = readByte();
        }
        if (readByte() != '\n' || digits == 0) {
            throw new ProtocolException("invalid " + what);
        }
        return negative ? -value : value;
    }

    private String readBulk(int length) throws IOException {
        if (limit - pos >= length) {
            String value = new String(buf, pos, length, ISO_8859_1);
            pos += length;
            return value;
        }
        // Larger than what is buffered: copy the buffered part, then read the rest straight into the array.
        byte[] data = new byte[length];
        int have = limit - pos;
        System.arraycopy(buf, pos, data, 0, have);
        pos = limit;
        while (have < length) {
            int n = in.read(data, have, length - have);
            if (n < 0) {
                throw new EOFException("stream ended inside a bulk string");
            }
            have += n;
            base += n;
        }
        return new String(data, ISO_8859_1);
    }

    private List<String> readInline() throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = readByte()) != '\n') {
            if (line.length() >= MAX_INLINE_LENGTH) {
                throw new ProtocolException("too big inline request");
            }
            line.append((char) b);
        }
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') {
            line.setLength(end - 1);
        }
        return splitInline(line);
    }

    /** Splits an inline command on whitespace, honouring "double" and 'single' quotes. */
    static List<String> splitInline(CharSequence line) throws ProtocolException {
        List<String> args = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (true) {
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i == n) {
                return args;
            }
            StringBuilder arg = new StringBuilder();
            while (i < n && !Character.isWhitespace(line.charAt(i))) {
                char c = line.charAt(i++);
                if (c == '"' || c == '\'') {
                    char quote = c;
                    while (true) {
                        if (i == n) {
                            throw new ProtocolException("unbalanced quotes in request");
                        }
                        c = line.charAt(i++);
                        if (c == quote) {
                            break;
                        }
                        if (c == '\\' && quote == '"' && i < n) {
                            c = switch (line.charAt(i++)) {
                                case 'n' -> '\n';
                                case 'r' -> '\r';
                                case 't' -> '\t';
                                default -> line.charAt(i - 1);
                            };
                        }
                        arg.append(c);
                    }
                } else {
                    arg.append(c);
                }
            }
            args.add(arg.toString());
        }
    }

    private int readByte() throws IOException {
        if (pos == limit && !fill()) {
            throw new EOFException("stream ended inside a command");
        }
        return buf[pos++] & 0xFF;
    }

    private boolean fill() throws IOException {
        base += limit;
        pos = 0;
        limit = 0;
        int n = in.read(buf);
        if (n <= 0) {
            return false;
        }
        limit = n;
        return true;
    }

    private static String printable(int b) {
        return b >= 32 && b < 127 ? String.valueOf((char) b) : String.format("\\x%02x", b);
    }
}
