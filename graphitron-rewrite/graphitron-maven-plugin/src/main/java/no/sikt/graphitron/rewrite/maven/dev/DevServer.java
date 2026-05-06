package no.sikt.graphitron.rewrite.maven.dev;

import no.sikt.graphitron.lsp.server.GraphitronLanguageServer;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
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

/**
 * The {@code dev} goal's socket-side surface: binds a {@link ServerSocket}
 * on a configured loopback port, accepts editor connections, and hands
 * each connection's streams to a fresh lsp4j {@link Launcher} backed by
 * a shared {@link Workspace}. One server instance per Mojo invocation;
 * one {@link GraphitronLanguageServer} per editor connection (so each
 * editor session has its own client proxy).
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
    private final ExecutorService acceptExecutor;
    private final ExecutorService connectionExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Bind a server on the supplied address. {@link BindException} is
     * surfaced as-is; callers translate it into a Mojo error pointing at
     * the override property.
     */
    public DevServer(InetSocketAddress address, Workspace workspace) throws IOException {
        this.workspace = workspace;
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
        try {
            var server = new GraphitronLanguageServer(workspace);
            var launcher = new Launcher.Builder<LanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(LanguageClient.class)
                .setInput(client.getInputStream())
                .setOutput(client.getOutputStream())
                .create();
            server.connect(launcher.getRemoteProxy());
            launcher.startListening().get();
        } catch (Exception e) {
            LOGGER.warn("graphitron:dev: client session ended with error: {}", e.getMessage());
        } finally {
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
