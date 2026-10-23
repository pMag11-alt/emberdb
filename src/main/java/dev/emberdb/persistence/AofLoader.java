package dev.emberdb.persistence;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.emberdb.protocol.ProtocolException;
import dev.emberdb.protocol.RespReader;

import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Replays an append-only file on startup.
 * <p>
 * If the server crashed mid-write, the file can end with half a command, or with a MULTI block that never got
 * its EXEC. Like Redis with {@code aof-load-truncated yes}, the loader drops that tail and truncates the file to
 * the last complete command, so a transaction is replayed all-or-nothing. Corruption anywhere else is an error.
 */
public final class AofLoader {

    @FunctionalInterface
    public interface Executor {
        void execute(List<String> argv) throws IOException;
    }

    /**
     * @param commands   commands replayed
     * @param truncated  whether an incomplete tail was dropped
     * @param validBytes length of the file after loading
     */
    public record Result(long commands, boolean truncated, long validBytes) {}

    private AofLoader() {}

    public static Result load(Path path, Executor executor) throws IOException {
        long commands = 0;
        long validEnd = 0;
        boolean truncated = false;
        List<List<String>> transaction = null;
        try (InputStream in = Files.newInputStream(path)) {
            RespReader reader = new RespReader(in);
            while (true) {
                List<String> argv;
                try {
                    argv = reader.readCommand();
                } catch (EOFException e) {
                    truncated = true;
                    break;
                }
                if (argv == null) {
                    truncated = transaction != null;
                    break;
                }
                if (argv.isEmpty()) {
                    continue;
                }
                String name = argv.get(0).toUpperCase(Locale.ROOT);
                if (name.equals("MULTI")) {
                    transaction = new ArrayList<>();
                } else if (name.equals("EXEC")) {
                    if (transaction == null) {
                        throw new IOException("EXEC without MULTI in " + path + " near byte " + validEnd);
                    }
                    for (List<String> queued : transaction) {
                        executor.execute(queued);
                    }
                    commands += transaction.size();
                    transaction = null;
                    validEnd = reader.position();
                } else if (transaction != null) {
                    transaction.add(argv);
                } else {
                    executor.execute(argv);
                    commands++;
                    validEnd = reader.position();
                }
            }
        } catch (ProtocolException e) {
            throw new IOException("corrupt append-only file " + path + " near byte " + validEnd + ": " + e.getMessage(), e);
        }
        if (truncated) {
            try (FileChannel channel = FileChannel.open(path, WRITE)) {
                channel.truncate(validEnd);
            }
        }
        return new Result(commands, truncated, validEnd);
    }
}
