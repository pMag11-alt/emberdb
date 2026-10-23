package dev.emberdb.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;

import dev.emberdb.util.Numbers;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/** Encodes replies in RESP2 or RESP3, and commands in RESP (for the append-only file). */
public final class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(ISO_8859_1);
    private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(ISO_8859_1);
    private static final byte[] RESP3_NULL = "_\r\n".getBytes(ISO_8859_1);
    private static final byte[] PAIR = "*2\r\n".getBytes(ISO_8859_1);

    private RespWriter() {}

    /** Encodes in RESP2. */
    public static void write(Reply reply, OutputStream out) throws IOException {
        write(reply, out, 2);
    }

    public static void write(Reply reply, OutputStream out, int protocol) throws IOException {
        boolean resp3 = protocol >= 3;
        switch (reply) {
            case Reply.Simple s -> line(out, '+', singleLine(s.text()));
            case Reply.Err e -> line(out, '-', singleLine(e.message()));
            case Reply.Int i -> line(out, ':', Long.toString(i.value()));
            case Reply.Bulk b -> {
                if (b.value() != null) {
                    bulk(out, b.value());
                } else {
                    out.write(resp3 ? RESP3_NULL : NULL_BULK);
                }
            }
            case Reply.Multi m -> {
                if (m.items() != null) {
                    aggregate(out, '*', m.items().size(), m.items(), protocol);
                } else {
                    out.write(resp3 ? RESP3_NULL : NULL_ARRAY);
                }
            }
            case Reply.Dbl d -> {
                if (resp3) {
                    line(out, ',', Numbers.formatDouble(d.value()));
                } else {
                    bulk(out, Numbers.formatDouble(d.value()));
                }
            }
            case Reply.Dict d -> aggregate(out, resp3 ? '%' : '*', resp3 ? d.flat().size() / 2 : d.flat().size(),
                    d.flat(), protocol);
            case Reply.SetOf s -> aggregate(out, resp3 ? '~' : '*', s.items().size(), s.items(), protocol);
            case Reply.Push p -> aggregate(out, resp3 ? '>' : '*', p.items().size(), p.items(), protocol);
            case Reply.Verbatim v -> {
                if (resp3) {
                    byte[] data = v.text().getBytes(ISO_8859_1);
                    line(out, '=', Integer.toString(data.length + 4));
                    out.write("txt:".getBytes(ISO_8859_1));
                    out.write(data);
                    out.write(CRLF);
                } else {
                    bulk(out, v.text());
                }
            }
            case Reply.Pairs p -> {
                List<Reply> flat = p.flat();
                if (resp3) {
                    line(out, '*', Integer.toString(flat.size() / 2));
                    for (int i = 0; i < flat.size(); i += 2) {
                        out.write(PAIR);
                        write(flat.get(i), out, protocol);
                        write(flat.get(i + 1), out, protocol);
                    }
                } else {
                    aggregate(out, '*', flat.size(), flat, protocol);
                }
            }
        }
    }

    /** Writes a command the way clients send it: an array of bulk strings. Used for the append-only file. */
    public static void writeCommand(List<String> argv, OutputStream out) throws IOException {
        line(out, '*', Integer.toString(argv.size()));
        for (String arg : argv) {
            bulk(out, arg);
        }
    }

    public static byte[] encode(Reply reply) {
        return encode(reply, 2);
    }

    public static byte[] encode(Reply reply, int protocol) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(reply, out, protocol);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static byte[] encodeCommand(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writeCommand(List.of(argv), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void aggregate(OutputStream out, char prefix, int count, List<Reply> items, int protocol)
            throws IOException {
        line(out, prefix, Integer.toString(count));
        for (Reply item : items) {
            write(item, out, protocol);
        }
    }

    private static void bulk(OutputStream out, String value) throws IOException {
        byte[] data = value.getBytes(ISO_8859_1);
        line(out, '$', Integer.toString(data.length));
        out.write(data);
        out.write(CRLF);
    }

    private static void line(OutputStream out, char prefix, String text) throws IOException {
        out.write(prefix);
        out.write(text.getBytes(ISO_8859_1));
        out.write(CRLF);
    }

    /** Simple strings and errors cannot contain CR or LF, so they are replaced with spaces (as Redis does). */
    private static String singleLine(String text) {
        return text.indexOf('\r') < 0 && text.indexOf('\n') < 0 ? text : text.replace('\r', ' ').replace('\n', ' ');
    }
}
