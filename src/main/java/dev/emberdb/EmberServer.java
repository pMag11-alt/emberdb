package dev.emberdb;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.emberdb.engine.Engine;
import dev.emberdb.engine.ReplySink;
import dev.emberdb.engine.Session;
import dev.emberdb.protocol.ProtocolException;
import dev.emberdb.protocol.Reply;
import dev.emberdb.protocol.RespReader;
import dev.emberdb.protocol.RespWriter;

/**
 * The TCP front end: accepts connections and gives each one a virtual thread that reads commands, hands them to
 * the {@link Engine} and writes the replies back.
 * <p>
 * Replies are buffered and only flushed when no further pipelined command is already waiting in the read buffer.
 * A client that sends 100 commands in one packet therefore gets its 100 replies in roughly one write.
 */
public final class EmberServer implements Closeable {

    private final Config config;
    private final Engine engine;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean();
    private ServerSocket serverSocket;

    public EmberServer(Config config) {
        this.config = config;
        this.engine = new Engine(config);
        this.engine.setShutdownHandler(this::close);
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.print(Config.USAGE);
            return;
        }
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("EmberDB " + Engine.VERSION);
            return;
        }
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("emberdb: " + e.getMessage());
            System.err.print(Config.USAGE);
            System.exit(2);
            return;
        }

        EmberServer server = new EmberServer(config);
        Log.info("EmberDB %s starting (Java %s, pid %d)", Engine.VERSION, Runtime.version(), ProcessHandle.current().pid());
        try {
            server.start();
        } catch (BindException e) {
            System.err.printf("emberdb: cannot listen on %s:%d (%s)%n", config.bind(), config.port(), e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("emberdb: " + e.getMessage());
            System.exit(1);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "ember-shutdown-hook"));
        if (config.appendOnly()) {
            Log.info("Append-only file: %s (fsync %s)", config.aofPath().toAbsolutePath(),
                    config.appendFsync().name().toLowerCase(Locale.ROOT));
        }
        Log.info("Ready to accept connections on %s:%d", config.bind(), server.port());
        server.awaitShutdown();
    }

    /** Loads persisted data, binds the port and starts accepting connections. */
    public void start() throws IOException {
        engine.start();
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(config.bind(), config.port()), 511);
        serverSocket = socket;
        Thread.ofPlatform().name("ember-accept").start(this::acceptLoop);
    }

    /** The bound port, useful when the config asked for port 0. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    public Engine engine() {
        return engine;
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                clients.add(socket);
                Thread.ofVirtual().name("ember-client").start(() -> serve(socket));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    Log.warn("Accepting a connection failed: %s", e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        Session session = null;
        try (socket) {
            RespReader reader = new RespReader(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
            InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
            session = engine.connect(new StreamSink(out), remote.getAddress().getHostAddress() + ":" + remote.getPort());
            while (!session.isCloseRequested()) {
                List<String> argv;
                try {
                    argv = reader.readCommand();
                } catch (ProtocolException e) {
                    session.send(Reply.error("ERR Protocol error: " + e.getMessage()));
                    session.flush();
                    break;
                }
                if (argv == null) {
                    break;
                }
                engine.dispatch(session, argv);
                if (!reader.hasBufferedInput() || session.isCloseRequested()) {
                    session.flush();
                }
            }
        } catch (EOFException | SocketException e) {
            // The client disconnected mid-command or the server is shutting down.
        } catch (IOException e) {
            Log.warn("Connection error: %s", e.getMessage());
        } finally {
            clients.remove(socket);
            if (session != null) {
                engine.disconnect(session);
            }
        }
    }

    /** Stops accepting, closes client connections and flushes the append-only file. Safe to call twice. */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
            for (Socket client : clients) {
                client.close();
            }
            engine.close();
            Log.info("EmberDB is now ready to exit, bye bye...");
        } catch (IOException e) {
            Log.warn("Error during shutdown: %s", e.getMessage());
        } finally {
            stopped.countDown();
        }
    }

    /** Writes replies to a buffered socket stream. */
    private record StreamSink(OutputStream out) implements ReplySink {
        @Override
        public void write(Reply reply, int protocol) throws IOException {
            RespWriter.write(reply, out, protocol);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }
}
