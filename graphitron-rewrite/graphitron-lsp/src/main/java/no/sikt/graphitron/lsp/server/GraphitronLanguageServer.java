package no.sikt.graphitron.lsp.server;

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
 * lsp4j entry point. Spike scope: advertises capabilities and stubs the
 * service surfaces. The real handlers (completion, hover, goto-definition)
 * land as the spike grows. Mirrors the {@code main_loop} structure in the
 * Rust LSP's {@code main.rs}, except that lsp4j drives the message loop
 * for us; we only register handlers.
 */
public class GraphitronLanguageServer implements LanguageServer, LanguageClientAware {

    private final TextDocumentService textService = new GraphitronTextDocumentService();
    private final WorkspaceService workspaceService = new GraphitronWorkspaceService();
    private LanguageClient client;

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        var capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        capabilities.setHoverProvider(true);
        capabilities.setCompletionProvider(new org.eclipse.lsp4j.CompletionOptions(false, null));
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
    }
}
