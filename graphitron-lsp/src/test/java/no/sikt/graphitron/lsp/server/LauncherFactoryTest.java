package no.sikt.graphitron.lsp.server;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.JsonRpcException;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the launcher factory's one judgement: a write that fails because the peer is gone is
 * dropped quietly, and every other write failure still propagates.
 *
 * <p>Two tiers, because they prove different things. The predicate boundary is checked with no
 * sockets at all, and it is the case that fails if the catch is ever widened. The suppression
 * itself is checked end to end over a real socket, since the noise this exists to remove is
 * produced inside lsp4j rather than in code we can call directly.
 */
class LauncherFactoryTest {

    /** lsp4j's own logger, whose records under a default JUL setup are the console noise. */
    private static final String REMOTE_ENDPOINT_LOGGER = "org.eclipse.lsp4j.jsonrpc.RemoteEndpoint";

    @Test
    @DisplayName("a stream-closed write is swallowed")
    void streamClosedWriteIsSwallowed() {
        MessageConsumer wrapped = LauncherFactory.quietOnStreamClosed(message -> {
            throw new JsonRpcException(new SocketException("Socket closed"));
        });

        // No assertion beyond returning: the point is that nothing propagates to lsp4j, which
        // is what keeps its default exception handler from logging the failure.
        wrapped.consume(new NotificationMessage());
    }

    @Test
    @DisplayName("any other write failure propagates unchanged")
    void unrelatedWriteFailurePropagates() {
        var cause = new JsonRpcException(new IOException("disk on fire"));
        MessageConsumer wrapped = LauncherFactory.quietOnStreamClosed(message -> {
            throw cause;
        });

        assertThatThrownBy(() -> wrapped.consume(new NotificationMessage()))
            .as("a framing or serialisation failure is a real defect and must stay visible")
            .isSameAs(cause);
    }

    /**
     * The response path, which is the one lsp4j never asked the question on. Latch-gated rather
     * than raced: a test that sent a request and closed fast would pass vacuously whenever the
     * response happened to win, and a pin that can silently prove nothing is worse than none.
     */
    @Test
    @DisplayName("a response written after the peer is gone logs nothing")
    void responseWriteAfterTeardownLogsNothing() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = new LatchedServer(entered, release);

        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var clientSocket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {

            // Not a try-with-resources: closing this socket mid-test is the event under test.
            var accepted = listener.accept();
            var records = new RecordingHandler();
            records.attach();
            try {
                var writes = new CountingOutputStream(accepted.getOutputStream());
                LauncherFactory.forStreams(server, accepted.getInputStream(), writes).startListening();
                var proxy = clientLauncher(clientSocket).getRemoteProxy();

                proxy.shutdown();
                assertThat(entered.await(5, TimeUnit.SECONDS))
                    .as("the handler is in flight, so the response is still owed")
                    .isTrue();

                // Exactly what teardown does to an in-flight request: the socket the response
                // will be written to is closed while the handler is still running.
                accepted.close();
                int before = writes.attempts();
                release.countDown();

                writes.awaitAttemptAfter(before);
                assertThat(records.withThrowable()).isEmpty();
            } finally {
                records.detach();
                release.countDown();
                accepted.close();
            }
        }
    }

    /**
     * The notification path, which an exception-handler-shaped fix would have missed entirely:
     * lsp4j catches a failed notification write itself and logs it, at INFO rather than SEVERE,
     * carrying the throwable either way. Under a default JUL setup that prints a stack trace
     * just the same, so asserting on SEVERE alone would pass while the console still filled.
     */
    @Test
    @DisplayName("a notification written after the peer is gone logs nothing")
    void notificationWriteAfterTeardownLogsNothing() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var clientSocket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {

            var accepted = listener.accept();
            var records = new RecordingHandler();
            records.attach();
            try {
                var writes = new CountingOutputStream(accepted.getOutputStream());
                var launcher = LauncherFactory.forStreams(
                    new LatchedServer(new CountDownLatch(1), new CountDownLatch(0)),
                    accepted.getInputStream(), writes);
                launcher.startListening();
                clientLauncher(clientSocket);

                accepted.close();
                int before = writes.attempts();

                // A publishDiagnostics push is what the diagnostics drain sends, and notify
                // writes it on the calling thread, so the attempt has happened by the time
                // this returns.
                launcher.getRemoteProxy().publishDiagnostics(
                    new PublishDiagnosticsParams("file:///gone.graphqls", List.of(new Diagnostic())));

                assertThat(writes.attempts())
                    .as("the write was attempted, so the absence of records means suppression")
                    .isGreaterThan(before);
                assertThat(records.withThrowable()).isEmpty();
            } finally {
                records.detach();
                accepted.close();
            }
        }
    }

    /** A client end that speaks the protocol; it is only here to give the server a live peer. */
    private static Launcher<LanguageServer> clientLauncher(Socket socket) throws IOException {
        var launcher = new Launcher.Builder<LanguageServer>()
            .setLocalService(new SilentLanguageClient())
            .setRemoteInterface(LanguageServer.class)
            .setInput(socket.getInputStream())
            .setOutput(socket.getOutputStream())
            .setExecutorService(Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "test-client-listener");
                t.setDaemon(true);
                return t;
            }))
            .create();
        launcher.startListening();
        return launcher;
    }

    /** Answers {@code shutdown} only when released, so a response can be owed on demand. */
    private static final class LatchedServer implements LanguageServer {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        LatchedServer(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        }

        @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult());
        }

        @Override public void exit() {}
        @Override public TextDocumentService getTextDocumentService() { return null; }
        @Override public WorkspaceService getWorkspaceService() { return null; }
    }

    /** Counts write attempts, so "nothing was logged" can be told apart from "nothing happened". */
    private static final class CountingOutputStream extends FilterOutputStream {
        private final AtomicInteger attempts = new AtomicInteger();

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        int attempts() {
            return attempts.get();
        }

        void awaitAttemptAfter(int baseline) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (attempts.get() <= baseline && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(attempts.get())
                .as("lsp4j attempted the write, so the absence of records means suppression")
                .isGreaterThan(baseline);
        }

        @Override
        public void write(int b) throws IOException {
            attempts.incrementAndGet();
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            attempts.incrementAndGet();
            out.write(b, off, len);
        }
    }

    /** Collects everything lsp4j's endpoint logs, at any level. */
    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger = Logger.getLogger(REMOTE_ENDPOINT_LOGGER);
        private Level restore;

        void attach() {
            restore = logger.getLevel();
            logger.setLevel(Level.ALL);
            logger.addHandler(this);
        }

        void detach() {
            logger.removeHandler(this);
            logger.setLevel(restore);
        }

        synchronized List<String> withThrowable() {
            // The level is what labels a record, not what makes it print a trace: JUL's default
            // console handler appends the stack trace of any record carrying a throwable, so the
            // throwable is what the console sees and the level is beside the point.
            return records.stream()
                .filter(record -> record.getThrown() != null)
                .map(record -> record.getLevel() + ": " + record.getMessage()
                    + " (" + record.getThrown() + ")")
                .toList();
        }

        @Override
        public synchronized void publish(LogRecord record) {
            records.add(record);
        }

        @Override public void flush() {}
        @Override public void close() {}
    }

    /** A client that discards everything; the tests assert on logs, not on delivery. */
    private static final class SilentLanguageClient implements LanguageClient {
        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
        @Override public void showMessage(org.eclipse.lsp4j.MessageParams params) {}
        @Override public CompletableFuture<org.eclipse.lsp4j.MessageActionItem> showMessageRequest(
            org.eclipse.lsp4j.ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(org.eclipse.lsp4j.MessageParams message) {}
    }
}
