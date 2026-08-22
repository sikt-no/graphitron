package no.sikt.graphitron.rewrite.maven.dev;

import no.sikt.graphitron.lsp.server.GraphitronLanguageServer;
import no.sikt.graphitron.lsp.server.LauncherFactory;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The {@code dev} goal's socket-side surface: binds a {@link ServerSocket}
 * on a configured loopback port, accepts editor connections, and hands
 * each connection's streams to a fresh lsp4j {@link Launcher}, built by
 * {@link LauncherFactory} so this transport and the stdio one share a
 * connection policy, backed by a shared {@link Workspace}. One server
 * instance per Mojo invocation; one {@link GraphitronLanguageServer} per
 * editor connection (so each editor session has its own client proxy).
 *
 * <p>The shared workspace means parsed buffers and the catalog reference
 * survive editor restarts: an editor reattach is sub-second because all
 * state stays warm in the JVM.
 *
 * <p>Cancellation: closing the server unblocks {@link ServerSocket#accept()}
 * with a {@link java.net.SocketException}, which the accept loop treats
 * as graceful exit. Existing connections are left to drain on their own
 * launcher threads; the JVM shutdown hook in {@code DevMojo} terminates
 * them when the Maven process exits.
 */
public final class DevServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DevServer.class);

    private final ServerSocket socket;
    private final Workspace workspace;
    private final Consumer<String> onSchemaSaved;
    private final ExecutorService acceptExecutor;
    private final ExecutorService connectionExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Bind a server on the supplied address. {@link BindException} is
     * surfaced as-is; callers translate it into a Mojo error pointing at
     * the override property.
     *
     * <p>The {@code onSchemaSaved} listener is propagated to each per-connection
     * {@link GraphitronLanguageServer}, where it fires from {@code didSave}.
     */
    public DevServer(InetSocketAddress address, Workspace workspace, Consumer<String> onSchemaSaved) throws IOException {
        this.workspace = workspace;
        this.onSchemaSaved = onSchemaSaved;
        this.socket = new ServerSocket();
        try {
            this.socket.bind(address);
        } catch (IOException e) {
            this.socket.close();
            throw e;
        }
        this.acceptExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "graphitron-dev-accept"));
        this.connectionExecutor = Executors.newCachedThreadPool(r -> daemon(r, "graphitron-dev-conn"));
        this.acceptExecutor.submit(this::acceptLoop);
    }

    public int port() {
        return socket.getLocalPort();
    }

    /**
     * Whether {@link #close()} has run. Public so {@code DevMojoTest}, which sits in the parent
     * {@code maven} package, can assert the LSP socket was released when a later bind (the MCP
     * server) fails partway through {@code DevMojo.bindServer} and the partial startup is unwound.
     */
    public boolean isClosed() {
        return closed.get();
    }

    public Workspace workspace() {
        return workspace;
    }

    private void acceptLoop() {
        while (!closed.get()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (!closed.get()) {
                    LOGGER.warn("graphitron:dev: accept failed: {}", e.getMessage());
                }
                return;
            }
            connectionExecutor.submit(() -> serve(client));
        }
    }

    private void serve(Socket client) {
        // Per connection, like the GraphitronLanguageServer it feeds: the diagnostics drain must
        // leave lsp4j's message-reader thread (a drain there stops the server reading its own
        // input, $/cancelRequest included), and it gets a named thread so a stack dump of a stuck
        // session says which thread the drain is on.
        var drainExecutor = Executors.newSingleThreadExecutor(
            r -> daemon(r, "graphitron-lsp-diagnostics-drain"));
        GraphitronLanguageServer server = null;
        try {
            server = new GraphitronLanguageServer(workspace, onSchemaSaved, drainExecutor);
            var launcher = LauncherFactory.forStreams(
                server, client.getInputStream(), client.getOutputStream());
            server.connect(launcher.getRemoteProxy());
            launcher.startListening().get();
        } catch (Exception e) {
            LOGGER.warn("graphitron:dev: client session ended with error: {}", e.getMessage());
        } finally {
            // This finally is the per-connection teardown seam: exit() is a client-driven
            // notification a disconnecting editor may never send, so this is the only place
            // guaranteed to run. The disconnect goes first: it releases what this connection
            // installed in the shared workspace, so a build swap arriving after it reaches
            // nothing rather than a service whose executor is about to die. The interrupting
            // shutdown then drops any queued drain and interrupts one in flight, best-effort: a
            // store read already inside the database runs to its budget, and a publish that
            // still lands on the closed client is dropped by the launcher's stream-closed
            // policy. The daemon flag keeps JVM exit correct regardless. The disconnect narrows
            // the window without closing it, so the document service still absorbs a rejected
            // submit from a mutation that read the listener slot before the clear.
            if (server != null) {
                server.disconnect();
            }
            drainExecutor.shutdownNow();
            try {
                client.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            LOGGER.warn("graphitron:dev: error closing socket: {}", e.getMessage());
        }
        acceptExecutor.shutdownNow();
        connectionExecutor.shutdownNow();
    }

    private static Thread daemon(Runnable r, String name) {
        var t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
