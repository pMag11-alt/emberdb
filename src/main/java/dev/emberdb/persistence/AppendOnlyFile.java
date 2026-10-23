package dev.emberdb.persistence;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import dev.emberdb.protocol.RespWriter;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * The append-only file (AOF): every write command, encoded in RESP exactly as a client would send it.
 * Replaying the file rebuilds the dataset.
 * <p>
 * Commands are appended to an in-memory buffer while the engine holds its command lock. The buffer is written to
 * the file and fsynced according to the policy:
 * <ul>
 *   <li>{@code ALWAYS}: write and fsync after every command. Nothing acknowledged is ever lost, but it is slow.</li>
 *   <li>{@code EVERYSEC}: write and fsync once a second from a background thread. At most a second of writes can
 *       be lost, and this is the Redis default.</li>
 *   <li>{@code NO}: write once a second and let the OS decide when to fsync.</li>
 * </ul>
 */
public final class AppendOnlyFile implements Closeable {

    public enum Fsync { ALWAYS, EVERYSEC, NO }

    /** Writes a compact snapshot of the dataset as commands. */
    @FunctionalInterface
    public interface SnapshotWriter {
        void write(OutputStream out) throws IOException;
    }

    /** A ByteArrayOutputStream whose contents can be written to a channel without copying them first. */
    private static final class Buffer extends ByteArrayOutputStream {
        Buffer() {
            super(64 * 1024);
        }

        ByteBuffer view() {
            return ByteBuffer.wrap(buf, 0, count);
        }
    }

    private final Path path;
    private final Fsync policy;
    private final ReentrantLock lock = new ReentrantLock();
    private final Buffer buffer = new Buffer();
    private FileChannel channel;
    private long size;
    private volatile boolean lastWriteFailed;

    private AppendOnlyFile(Path path, Fsync policy, FileChannel channel) throws IOException {
        this.path = path;
        this.policy = policy;
        this.channel = channel;
        this.size = channel.size();
    }

    public static AppendOnlyFile open(Path path, Fsync policy) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return new AppendOnlyFile(path, policy, FileChannel.open(path, CREATE, WRITE, APPEND));
    }

    public Fsync policy() {
        return policy;
    }

    public Path path() {
        return path;
    }

    /** Bytes in the file, not counting commands still in the buffer. */
    public long size() {
        return size;
    }

    public boolean lastWriteFailed() {
        return lastWriteFailed;
    }

    public void append(List<String> argv) {
        lock.lock();
        try {
            RespWriter.writeCommand(argv, buffer);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // writing to memory cannot fail
        } finally {
            lock.unlock();
        }
    }

    /** Writes buffered commands to the file, and fsyncs if asked to. */
    public void flush(boolean fsync) throws IOException {
        lock.lock();
        try {
            if (buffer.size() > 0) {
                ByteBuffer bytes = buffer.view();
                while (bytes.hasRemaining()) {
                    size += channel.write(bytes);
                }
                buffer.reset();
            }
            if (fsync) {
                channel.force(false);
            }
            lastWriteFailed = false;
        } catch (IOException e) {
            lastWriteFailed = true;
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /** The once-a-second background flush. */
    public void periodicFlush() throws IOException {
        if (policy != Fsync.ALWAYS) {
            flush(policy == Fsync.EVERYSEC);
        }
    }

    /**
     * Replaces the file with the snapshot, which describes the same dataset in as few commands as possible.
     * The new file is written next to the old one, fsynced, and renamed over it, so a crash at any point leaves
     * either the old file or the new one, never half of each. The caller must hold the engine's command lock so
     * the dataset does not change while the snapshot is written.
     */
    public void rewrite(SnapshotWriter snapshot) throws IOException {
        lock.lock();
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".rewrite.tmp");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp, CREATE, TRUNCATE_EXISTING, WRITE))) {
                snapshot.write(out);
            }
            try (FileChannel tmpChannel = FileChannel.open(tmp, WRITE)) {
                tmpChannel.force(true);
            }
            Files.move(tmp, path, ATOMIC_MOVE, REPLACE_EXISTING);
            channel.close();
            channel = FileChannel.open(path, WRITE, APPEND);
            size = channel.size();
            // Buffered commands were already applied to the dataset, so the snapshot includes them.
            buffer.reset();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            flush(true);
            channel.close();
        } finally {
            lock.unlock();
        }
    }
}
