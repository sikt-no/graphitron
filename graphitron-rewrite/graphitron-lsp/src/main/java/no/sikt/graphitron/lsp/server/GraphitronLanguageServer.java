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

/**
 * lsp4j entry point. Holds a single {@link Workspace} per server instance
 * (one server per editor connection); the workspace owns parsed files plus
 * the catalog. The {@code dev} Mojo (slice 2) constructs the catalog from
 * the rewrite generator and passes it in here.
 */
public class GraphitronLanguageServer implements LanguageServer, LanguageClientAware {

    private final Workspace workspace;
    private final GraphitronTextDocumentService textService;
    private final WorkspaceService workspaceService = new GraphitronWorkspaceService();
    private LanguageClient client;

    public GraphitronLanguageServer() {
        this(new Workspace());
    }

    public GraphitronLanguageServer(Workspace workspace) {
        this.workspace = workspace;
        this.textService = new GraphitronTextDocumentService(workspace);
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
