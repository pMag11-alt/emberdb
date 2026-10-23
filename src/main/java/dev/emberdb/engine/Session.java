package dev.emberdb.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import dev.emberdb.protocol.Reply;

/**
 * Per-connection state: authentication, the MULTI queue, WATCHed keys and pub/sub subscriptions.
 * <p>
 * The connection's own thread writes replies, but PUBLISH on another connection can push messages into this one at
 * the same time, so writes go through a lock. It is a {@link ReentrantLock} rather than {@code synchronized}
 * because on Java 21 a virtual thread blocked inside {@code synchronized} pins its carrier thread.
 */
public final class Session {

    private static final AtomicLong IDS = new AtomicLong();

    record QueuedCommand(Command command, List<String> argv) {}

    private final long id = IDS.incrementAndGet();
    private final ReplySink sink;
    private final String address;
    private final long createdAt;
    private final ReentrantLock writeLock = new ReentrantLock();

    private volatile int protocol = 2;
    private volatile String name = "";
    private volatile String lastCommand = "";
    private volatile boolean closeRequested;
    private boolean authenticated;

    private List<QueuedCommand> queue;
    private boolean queueFailed;

    /** Guarded by the engine's command lock. */
    final Set<String> watchedKeys = new HashSet<>();
    /** Guarded by the engine's command lock. */
    boolean watchedKeyModified;

    final Set<String> channels = ConcurrentHashMap.newKeySet();
    final Set<String> patterns = ConcurrentHashMap.newKeySet();

    Session(ReplySink sink, String address, long createdAt, boolean authenticated) {
        this.sink = sink;
        this.address = address;
        this.createdAt = createdAt;
        this.authenticated = authenticated;
    }

    public long id() {
        return id;
    }

    public String address() {
        return address;
    }

    public long createdAt() {
        return createdAt;
    }

    /** 2 (RESP2, the default) or 3 (RESP3), as chosen with HELLO. */
    public int protocol() {
        return protocol;
    }

    public void setProtocol(int protocol) {
        this.protocol = protocol;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String lastCommand() {
        return lastCommand;
    }

    void setLastCommand(String lastCommand) {
        this.lastCommand = lastCommand;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    /** Ask the server to close the connection once the current reply is flushed (QUIT). */
    public void requestClose() {
        closeRequested = true;
    }

    public boolean isCloseRequested() {
        return closeRequested;
    }

    // ---- replies -----------------------------------------------------------------------------------------------

    /** Queues a reply; the server flushes once no more pipelined commands are waiting. */
    public void send(Reply reply) {
        writeLock.lock();
        try {
            sink.write(reply, protocol);
        } catch (IOException e) {
            closeRequested = true;
        } finally {
            writeLock.unlock();
        }
    }

    public void flush() {
        writeLock.lock();
        try {
            sink.flush();
        } catch (IOException e) {
            closeRequested = true;
        } finally {
            writeLock.unlock();
        }
    }

    /** Sends and flushes at once. Used for pub/sub messages pushed from other connections' threads. */
    public void push(Reply reply) {
        writeLock.lock();
        try {
            sink.write(reply, protocol);
            sink.flush();
        } catch (IOException e) {
            closeRequested = true;
        } finally {
            writeLock.unlock();
        }
    }

    // ---- transactions ------------------------------------------------------------------------------------------

    public boolean inMulti() {
        return queue != null;
    }

    void beginMulti() {
        queue = new ArrayList<>();
        queueFailed = false;
    }

    void enqueue(Command command, List<String> argv) {
        queue.add(new QueuedCommand(command, argv));
    }

    /** A command was rejected while queuing, so EXEC must abort. */
    void markQueueFailed() {
        if (queue != null) {
            queueFailed = true;
        }
    }

    boolean queueFailed() {
        return queueFailed;
    }

    List<QueuedCommand> endMulti() {
        List<QueuedCommand> queued = queue;
        queue = null;
        queueFailed = false;
        return queued;
    }

    // ---- pub/sub -----------------------------------------------------------------------------------------------

    public boolean isSubscribed() {
        return !channels.isEmpty() || !patterns.isEmpty();
    }

    public int subscriptionCount() {
        return channels.size() + patterns.size();
    }
}
