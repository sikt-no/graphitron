package no.sikt.graphitron.lsp;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.lsp.server.GraphitronLanguageServer;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real lsp4j {@code Launcher} pair (server / client) over piped
 * streams. Validates that the wire protocol, capabilities advertisement,
 * and slice-1 handlers (didOpen, didChange, completion) round-trip
 * end-to-end the same way an editor would exercise them.
 *
 * <p>Capability-advertisement coverage stays trivial here; the round-trip
 * confirmation is the bigger value.
 */
class TextDocumentServiceTest {

    private ExecutorService serverThread;
    private ExecutorService clientThread;
    private Future<Void> serverListening;
    private Future<Void> clientListening;
    private TestLanguageClient clientStub;

    @AfterEach
    void tearDown() {
        if (serverListening != null) serverListening.cancel(true);
        if (clientListening != null) clientListening.cancel(true);
        if (serverThread != null) serverThread.shutdownNow();
        if (clientThread != null) clientThread.shutdownNow();
    }

    @Test
    void completionRequestRoundTripsThroughTableCompletions() throws Exception {
        var catalog = new CompletionData(
            List.of(
                table("FILM"),
                table("ACTOR")
            ),
            List.of(),
            List.of()
        );
        var server = new GraphitronLanguageServer(new Workspace(catalog));
        var proxy = startServer(server);

        proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        String uri = "file:///schema.graphqls";
        String source = """
            type Foo @table(name: "") {
              bar: Int
            }
            """;
        var item = new TextDocumentItem(uri, "graphql", 1, source);
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));

        // Cursor inside the empty quoted argument value.
        int quoteCol = source.indexOf("\"") + 1;
        var params = new CompletionParams(
            new TextDocumentIdentifier(uri),
            new Position(0, quoteCol)
        );
        var result = proxy.getTextDocumentService().completion(params)
            .get(5, TimeUnit.SECONDS);

        assertThat(result.isLeft()).isTrue();
        var labels = result.getLeft().stream().map(c -> c.getLabel()).toList();
        assertThat(labels).containsExactly("FILM", "ACTOR");
    }

    @Test
    void completionAfterMultiByteDescriptionResolvesCorrectDirective() throws Exception {
        // Description on line 0 contains å (multi-byte UTF-8). The cursor
        // sits inside the @table empty-string argument on line 1; the
        // server must convert the LSP UTF-16 column to a UTF-8 byte
        // column before tree-sitter looks up the directive node.
        var catalog = new CompletionData(
            List.of(table("FILM"), table("ACTOR")),
            List.of(),
            List.of()
        );
        var server = new GraphitronLanguageServer(new Workspace(catalog));
        var proxy = startServer(server);
        proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        String uri = "file:///norsk.graphqls";
        String source = """
            "Tabell for å håndtere åremål"
            type Foo @table(name: "") {
              bar: Int
            }
            """;
        var item = new TextDocumentItem(uri, "graphql", 1, source);
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));

        // Line 1, column 23: just inside the empty @table(name: "") quotes.
        var params = new CompletionParams(
            new TextDocumentIdentifier(uri),
            new Position(1, 23)
        );
        var result = proxy.getTextDocumentService().completion(params)
            .get(5, TimeUnit.SECONDS);

        assertThat(result.isLeft()).isTrue();
        var labels = result.getLeft().stream().map(c -> c.getLabel()).toList();
        assertThat(labels).containsExactly("FILM", "ACTOR");
    }

    @Test
    void incrementalDidChangeUpdatesWorkspaceBuffer() throws Exception {
        var workspace = new Workspace();
        var server = new GraphitronLanguageServer(workspace);
        var proxy = startServer(server);

        proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        String uri = "file:///schema.graphqls";
        var item = new TextDocumentItem(uri, "graphql", 1, "type Foo { x: Int }\n");
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));

        var range = new org.eclipse.lsp4j.Range(new Position(0, 5), new Position(0, 8));
        var change = new TextDocumentContentChangeEvent(range, "Bar");
        proxy.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri, 2),
            List.of(change)
        ));

        // Notifications are fire-and-forget; round-trip a request to flush.
        proxy.getTextDocumentService().completion(new CompletionParams(
            new TextDocumentIdentifier(uri), new Position(0, 0)
        )).get(5, TimeUnit.SECONDS);

        var file = workspace.get(uri).orElseThrow();
        assertThat(new String(file.source())).startsWith("type Bar");
        assertThat(file.version()).isEqualTo(2);
    }

    @Test
    void didOpenPublishesDiagnosticsForUnknownTable() throws Exception {
        var catalog = new CompletionData(
            List.of(table("FILM")),
            List.of(),
            List.of()
        );
        var server = new GraphitronLanguageServer(new no.sikt.graphitron.lsp.state.Workspace(catalog));
        var proxy = startServer(server);
        proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        String uri = "file:///bad.graphqls";
        String source = """
            type Foo @table(name: "MISSING") { bar: Int }
            """;
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(uri, "graphql", 1, source)));

        // Notifications are fire-and-forget; round-trip a request to flush
        // the queued didOpen + diagnostic publish.
        proxy.getTextDocumentService().completion(new CompletionParams(
            new TextDocumentIdentifier(uri), new Position(0, 0))
        ).get(5, TimeUnit.SECONDS);

        var diagnostics = clientStub.latestDiagnostics.get(uri);
        assertThat(diagnostics).isNotNull();
        assertThat(diagnostics.getDiagnostics()).hasSize(1);
        assertThat(diagnostics.getDiagnostics().get(0).getMessage()).contains("MISSING");
    }

    @Test
    void hoverRequestRoundTripsCatalogMetadata() throws Exception {
        var catalog = new CompletionData(
            List.of(new CompletionData.Table(
                "film",
                "Movies the rental store carries",
                CompletionData.SourceLocation.UNKNOWN,
                List.of(),
                List.of()
            )),
            List.of(),
            List.of()
        );
        var server = new GraphitronLanguageServer(new no.sikt.graphitron.lsp.state.Workspace(catalog));
        var proxy = startServer(server);
        proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        String uri = "file:///hover.graphqls";
        String source = """
            type Foo @table(name: "film") { bar: Int }
            """;
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(uri, "graphql", 1, source)));

        // Cursor inside the "film" string value.
        int filmStart = source.indexOf("film");
        var hoverParams = new org.eclipse.lsp4j.HoverParams(
            new TextDocumentIdentifier(uri),
            new Position(0, filmStart + 1)
        );
        var hover = proxy.getTextDocumentService().hover(hoverParams).get(5, TimeUnit.SECONDS);

        assertThat(hover).isNotNull();
        var md = hover.getContents().getRight().getValue();
        assertThat(md).contains("**Table** `film`");
        assertThat(md).contains("Movies the rental store carries");
    }

    @Test
    void didCloseClearsDiagnosticsForFile() throws Exception {
        var server = new GraphitronLanguageServer(new no.sikt.graphitron.lsp.state.Workspace());
        var proxy = startServer(server);
        proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        String uri = "file:///clean.graphqls";
        proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(uri, "graphql", 1, "type Foo @table(name: \"MISSING\") { bar: Int }\n")));
        proxy.getTextDocumentService().completion(new CompletionParams(
            new TextDocumentIdentifier(uri), new Position(0, 0))
        ).get(5, TimeUnit.SECONDS);

        proxy.getTextDocumentService().didClose(new org.eclipse.lsp4j.DidCloseTextDocumentParams(
            new TextDocumentIdentifier(uri)));
        // Round-trip again to flush the close + cleared diagnostics.
        proxy.getTextDocumentService().completion(new CompletionParams(
            new TextDocumentIdentifier(uri), new Position(0, 0))
        ).get(5, TimeUnit.SECONDS);

        var diagnostics = clientStub.latestDiagnostics.get(uri);
        assertThat(diagnostics).isNotNull();
        assertThat(diagnostics.getDiagnostics()).isEmpty();
    }

    private LanguageServer startServer(GraphitronLanguageServer server) throws Exception {
        var clientToServer = new PipedOutputStream();
        var serverIn = new PipedInputStream(clientToServer, 1 << 16);
        var serverToClient = new PipedOutputStream();
        var clientIn = new PipedInputStream(serverToClient, 1 << 16);

        var serverLauncher = new Launcher.Builder<LanguageClient>()
            .setLocalService(server)
            .setRemoteInterface(LanguageClient.class)
            .setInput(serverIn)
            .setOutput(serverToClient)
            .create();
        server.connect(serverLauncher.getRemoteProxy());

        this.clientStub = new TestLanguageClient();
        var clientLauncher = new Launcher.Builder<LanguageServer>()
            .setLocalService(clientStub)
            .setRemoteInterface(LanguageServer.class)
            .setInput(clientIn)
            .setOutput(clientToServer)
            .create();

        serverThread = Executors.newSingleThreadExecutor();
        clientThread = Executors.newSingleThreadExecutor();
        serverListening = serverThread.submit(() -> { serverLauncher.startListening().get(); return null; });
        clientListening = clientThread.submit(() -> { clientLauncher.startListening().get(); return null; });

        return clientLauncher.getRemoteProxy();
    }

    private static CompletionData.Table table(String name) {
        return new CompletionData.Table(
            name,
            "",
            CompletionData.SourceLocation.UNKNOWN,
            List.of(),
            List.of()
        );
    }
}
