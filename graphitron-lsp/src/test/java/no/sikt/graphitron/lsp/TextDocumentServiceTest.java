package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.server.GraphitronLanguageServer;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.read.SourceUri;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
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
import org.junit.jupiter.api.io.TempDir;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    void completionRequestRoundTripsThroughTableCompletions(@TempDir Path tmp) throws Exception {
        String source = """
            type Foo @table(name: "") {
              bar: Int
            }
            """;
        // Cursor inside the empty quoted argument value.
        var cursor = new Position(0, source.indexOf('"') + 1);

        try (var fixture = StoreFixture.ofCatalog(tmp, "type Query { x: Int }\n");
             var access = fixture.access()) {
            assertThat(labelsAt(access, fixture, source, cursor)).contains("film", "actor");
        }
    }

    @Test
    void completionAfterMultiByteDescriptionResolvesCorrectDirective(@TempDir Path tmp)
        throws Exception {
        // Description on line 0 contains å (multi-byte UTF-8). The cursor
        // sits inside the @table empty-string argument on line 1; the
        // server must convert the LSP UTF-16 column to a UTF-8 byte
        // column before tree-sitter looks up the directive node.
        String source = """
            "Tabell for å håndtere åremål"
            type Foo @table(name: "") {
              bar: Int
            }
            """;
        // Line 1, column 23: just inside the empty @table(name: "") quotes.
        var cursor = new Position(1, 23);

        try (var fixture = StoreFixture.ofCatalog(tmp, "type Query { x: Int }\n");
             var access = fixture.access()) {
            assertThat(labelsAt(access, fixture, source, cursor)).contains("film", "actor");
        }
    }

    /**
     * Opens {@code source} as the fixture's own schema file and asks for completions at
     * {@code cursor} over the wire. The document is opened under the captured file's URI because
     * that is how the request boundary resolves an open buffer to the graph whose census answers
     * for it; the buffer's text is the test's own and need not be what was captured, which is the
     * ordinary state of a document being edited.
     */
    private List<String> labelsAt(
        StoreAccess access, StoreFixture fixture, String source, Position cursor
    ) throws Exception {
        var workspace = new Workspace();
        workspace.setStore(access);
        var proxy = startServer(new GraphitronLanguageServer(workspace));
        proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

        String uri = SourceUri.of(fixture.sourceName());
        proxy.getTextDocumentService()
            .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "graphql", 1, source)));

        var result = proxy.getTextDocumentService()
            .completion(new CompletionParams(new TextDocumentIdentifier(uri), cursor))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.isLeft()).isTrue();
        return result.getLeft().stream().map(CompletionItem::getLabel).toList();
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

        String content = workspace.withView(uri, null, v -> new String(v.source()));
        int version = workspace.withView(uri, -1, FileSnapshot::version);
        assertThat(content).startsWith("type Bar");
        assertThat(version).isEqualTo(2);
    }

    /**
      * The diagnostic publish end to end, over the wire and through the read transaction the document
      * service opens for it. The table arm answers from the graph's catalog census, so the document is
      * opened under the captured file's URI, which is what resolves an open buffer to the graph whose
      * facts answer for it.
      */
    @Test
    void didOpenPublishesDiagnosticsForUnknownTable(@TempDir Path tmp) throws Exception {
        try (var fixture = StoreFixture.ofCatalog(tmp, "type Query { x: Int }\n");
             var access = fixture.access()) {
            var workspace = new no.sikt.graphitron.lsp.state.Workspace();
            workspace.setStore(access);
            var proxy = startServer(new GraphitronLanguageServer(workspace));
            proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

            String uri = SourceUri.of(fixture.sourceName());
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
    }

    /**
     * The hover request end to end, over the wire and through the read transaction the document
     * service opens for it. The table arm answers from the graph's catalog census, so the document
     * is opened under the captured file's URI: that is what resolves an open buffer to the graph
     * whose facts answer for it.
     */
    @Test
    void hoverRequestRoundTripsCatalogMetadata(@TempDir Path tmp) throws Exception {
        String source = """
            type Foo @table(name: "film") { bar: Int }
            """;

        try (var fixture = StoreFixture.ofCatalog(tmp, "type Query { x: Int }\n");
             var access = fixture.access()) {
            var workspace = new no.sikt.graphitron.lsp.state.Workspace();
            workspace.setStore(access);
            var proxy = startServer(new GraphitronLanguageServer(workspace));
            proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

            String uri = SourceUri.of(fixture.sourceName());
            proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "graphql", 1, source)));

            // Cursor inside the "film" string value.
            var hoverParams = new org.eclipse.lsp4j.HoverParams(
                new TextDocumentIdentifier(uri), new Position(0, source.indexOf("film") + 1));
            var hover = proxy.getTextDocumentService().hover(hoverParams).get(5, TimeUnit.SECONDS);

            assertThat(hover).isNotNull();
            var md = hover.getContents().getRight().getValue();
            assertThat(md).contains("**Table** `film`");
            assertThat(md).containsPattern("\\d+ columns, \\d+ references\\.");
        }
    }

    /**
     * The definition request end to end, over the wire and through the read transaction the document
     * service opens for it. The catalog census carries the table's {@code classFqn} and the store's
     * java-source family the declaration that FQN names, so the document is opened under the captured
     * file's URI for the same reason hover's is: that is what resolves an open buffer to the graph
     * whose facts answer for it.
     */
    @Test
    void definitionRequestRoundTripsToTheParsedDeclaration(@TempDir Path tmp) throws Exception {
        try (var fixture = StoreFixture.ofCatalog(tmp, "type Query { x: Int }\n");
             var access = fixture.access()) {
            String filmFqn = fixture.tableClassFqn("film");
            fixture.withJavaSource(tmp, filmFqn, """
                public class Film {
                }
                """);
            var workspace = new no.sikt.graphitron.lsp.state.Workspace();
            workspace.setStore(access);
            var proxy = startServer(new GraphitronLanguageServer(workspace));
            proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);

            String uri = SourceUri.of(fixture.sourceName());
            String source = "type Foo @table(name: \"film\") { bar: Int }\n";
            proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "graphql", 1, source)));

            var defParams = new org.eclipse.lsp4j.DefinitionParams(
                new TextDocumentIdentifier(uri),
                new Position(0, source.indexOf("film") + 1)
            );
            var result = proxy.getTextDocumentService().definition(defParams).get(5, TimeUnit.SECONDS);

            assertThat(result.isLeft()).isTrue();
            assertThat(result.getLeft()).hasSize(1);
            // The class is declared on the second line of the source written above, the package
            // declaration taking the first.
            assertThat(result.getLeft().get(0).getUri()).endsWith("Film.java");
            assertThat(result.getLeft().get(0).getRange().getStart().getLine()).isEqualTo(1);
        }
    }

    /**
     * The references request end to end: the capability is advertised at initialize, the handler is
     * reachable over the wire, and the {@code includeDeclaration} flag the editor sets arrives where
     * it decides something. The schema opened here is the one the fixture captured, so the sites
     * come back against the file the request named.
     */
    @Test
    void referencesRequestRoundTripsToTheCapturedUses(@TempDir Path tmp) throws Exception {
        String sdl = """
            type Query {
              films: [Film!]!
            }

            type Film {
              title: String
            }
            """;
        try (var fixture = StoreFixture.of(tmp, sdl);
             var access = fixture.access()) {
            var workspace = new no.sikt.graphitron.lsp.state.Workspace();
            workspace.setStore(access);
            var proxy = startServer(new GraphitronLanguageServer(workspace));
            var initialized = proxy.initialize(new InitializeParams()).get(5, TimeUnit.SECONDS);
            assertThat(initialized.getCapabilities().getReferencesProvider())
                .as("an editor only sends the request to a server that advertises it")
                .isNotNull();

            String uri = SourceUri.of(fixture.sourceName());
            proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "graphql", 1, sdl)));

            // Cursor inside the "Film" of "type Film", the declaration name.
            var position = new Position(4, 7);
            var uses = references(proxy, uri, position, false);
            assertThat(uses).extracting(use -> use.getRange().getStart().getLine())
                .as("the one use is the films field on line 1")
                .containsExactly(1);

            assertThat(references(proxy, uri, position, true))
                .extracting(use -> use.getRange().getStart().getLine())
                .as("with includeDeclaration the type's own site joins")
                .containsExactly(1, 4);
        }
    }

    private static java.util.List<? extends org.eclipse.lsp4j.Location> references(
        org.eclipse.lsp4j.services.LanguageServer proxy, String uri, Position position,
        boolean includeDeclaration
    ) throws Exception {
        var params = new org.eclipse.lsp4j.ReferenceParams(
            new TextDocumentIdentifier(uri), position,
            new org.eclipse.lsp4j.ReferenceContext(includeDeclaration));
        return proxy.getTextDocumentService().references(params).get(5, TimeUnit.SECONDS);
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

    @Test
    void didSave_invokesListenerWithUri() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> seen = new AtomicReference<>();
        var server = new GraphitronLanguageServer(new Workspace(), uri -> {
            calls.incrementAndGet();
            seen.set(uri);
        });

        String uri = "file:///x.graphqls";
        server.getTextDocumentService().didSave(new DidSaveTextDocumentParams(
            new TextDocumentIdentifier(uri)));

        assertThat(calls).hasValue(1);
        assertThat(seen.get()).isEqualTo(uri);
    }

    @Test
    void didSave_noopWhenListenerAbsent() {
        var server = new GraphitronLanguageServer();

        // Single-arg listener-absent form must not throw and must not require
        // a connected client. Pins the headless / LSP-only-use contract.
        server.getTextDocumentService().didSave(new DidSaveTextDocumentParams(
            new TextDocumentIdentifier("file:///y.graphqls")));
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
}
