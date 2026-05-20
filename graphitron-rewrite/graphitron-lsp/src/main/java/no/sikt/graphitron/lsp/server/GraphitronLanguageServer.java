package no.sikt.graphitron.lsp.server;

import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * lsp4j entry point. Holds a single {@link Workspace} per server instance
 * (one server per editor connection); the workspace owns parsed files plus
 * the catalog. The {@code dev} Mojo (slice 2) constructs the catalog from
 * the rewrite generator and passes it in here.
 *
 * <p>The {@code onSchemaSaved} listener fires from {@code didSave}; the
 * dev Mojo wires it to the debounced regen trigger, so editor saves drive
 * regeneration directly rather than waiting for the filesystem watcher.
 * Headless LSP-only use sites (no dev Mojo) pass no listener and
 * {@code didSave} is a no-op.
 */
public class GraphitronLanguageServer implements LanguageServer, LanguageClientAware {

    private final Workspace workspace;
    private final GraphitronTextDocumentService textService;
    private final WorkspaceService workspaceService;
    private LanguageClient client;

    public GraphitronLanguageServer() {
        this(new Workspace(), uri -> {});
    }

    public GraphitronLanguageServer(Workspace workspace) {
        this(workspace, uri -> {});
    }

    public GraphitronLanguageServer(Workspace workspace, Consumer<String> onSchemaSaved) {
        this.workspace = workspace;
        this.textService = new GraphitronTextDocumentService(workspace, onSchemaSaved);
        this.workspaceService = new GraphitronWorkspaceService(workspace);
    }

    public Workspace workspace() {
        return workspace;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        var capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        capabilities.setHoverProvider(true);
        capabilities.setCompletionProvider(new CompletionOptions(false, null));
        capabilities.setDefinitionProvider(true);
        capabilities.setCodeActionProvider(true);
        // R160 — advertise the inlay-hint capability so editors that opt in via
        // graphitron.inlayHints.* config keys receive the inferred-directive and
        // classification hint surface. The handler is a no-op when all toggles default off.
        capabilities.setInlayHintProvider(true);
        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // lsp4j drives process lifetime; nothing to clean up in the spike.
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        textService.setClient(client);
    }
}
