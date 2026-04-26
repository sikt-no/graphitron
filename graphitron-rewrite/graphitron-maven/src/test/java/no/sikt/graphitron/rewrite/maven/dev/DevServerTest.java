package no.sikt.graphitron.rewrite.maven.dev;

import no.sikt.graphitron.lsp.catalog.CompletionData;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives a real {@link DevServer} on an ephemeral port and asserts a
 * client can connect, initialize, and round-trip a request. Also covers
 * the bind-failure path so the {@code DevMojo} message contract holds.
 */
class DevServerTest {

    @Test
    void bindsRandomPortAndServesCompletion() throws Exception {
        var catalog = new CompletionData(
            List.of(new CompletionData.Table(
                "FILM", "", CompletionData.SourceLocation.UNKNOWN, List.of(), List.of())),
            List.of(),
            List.of()
        );
        try (var server = new DevServer(loopback(0), new Workspace(catalog))) {
            assertThat(server.port()).isGreaterThan(0);

            try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                var proxy = clientProxy(socket);
                proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

                String uri = "file:///schema.graphqls";
                String source = "type Foo @table(name: \"\") { bar: Int }\n";
                proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, "graphql", 1, source)));

                var params = new CompletionParams(
                    new TextDocumentIdentifier(uri),
                    new Position(0, source.indexOf('"') + 1)
                );
                var result = proxy.getTextDocumentService().completion(params)
                    .get(5, TimeUnit.SECONDS);

                assertThat(result.isLeft()).isTrue();
                var labels = result.getLeft().stream().map(c -> c.getLabel()).toList();
                assertThat(labels).containsExactly("FILM");
            }
        }
    }

    @Test
    void multipleClientsShareWorkspaceState() throws Exception {
        var workspace = new Workspace();
        try (var server = new DevServer(loopback(0), workspace)) {
            try (var s1 = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                var p1 = clientProxy(s1);
                p1.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
                p1.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem("file:///a.graphqls", "graphql", 1, "type A { x: Int }")));
                // Force the notification to flush before the next assertion.
                p1.getTextDocumentService().completion(new CompletionParams(
                    new TextDocumentIdentifier("file:///a.graphqls"), new Position(0, 0))
                ).get(5, TimeUnit.SECONDS);
            }
            // First connection closed; the workspace state from it must
            // outlive the connection (warm-state contract).
            assertThat(workspace.get("file:///a.graphqls")).isPresent();

            try (var s2 = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                var p2 = clientProxy(s2);
                p2.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
                // The new connection sees the file the first one opened.
                var result = p2.getTextDocumentService().completion(new CompletionParams(
                    new TextDocumentIdentifier("file:///a.graphqls"), new Position(0, 0))
                ).get(5, TimeUnit.SECONDS);
                assertThat(result.isLeft()).isTrue();
            }
        }
    }

    @Test
    void bindingTakenPortFailsWithIoException() throws Exception {
        try (var first = new DevServer(loopback(0), new Workspace())) {
            int port = first.port();
            assertThatThrownBy(() ->
                new DevServer(loopback(port), new Workspace())
            ).isInstanceOf(java.io.IOException.class);
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static LanguageServer clientProxy(Socket socket) throws Exception {
        var clientStub = new TestLanguageClient();
        var launcher = new Launcher.Builder<LanguageServer>()
            .setLocalService(clientStub)
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
        return launcher.getRemoteProxy();
    }

    /** Trivial client stub that satisfies the lsp4j contract. */
    static class TestLanguageClient implements LanguageClient {
        @Override public void telemetryEvent(Object o) {}
        @Override public void publishDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams d) {}
        @Override public void showMessage(org.eclipse.lsp4j.MessageParams p) {}
        @Override public CompletableFuture<org.eclipse.lsp4j.MessageActionItem> showMessageRequest(org.eclipse.lsp4j.ShowMessageRequestParams p) { return CompletableFuture.completedFuture(null); }
        @Override public void logMessage(org.eclipse.lsp4j.MessageParams m) {}
    }
}
