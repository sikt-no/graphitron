package no.sikt.graphitron.lsp;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.concurrent.CompletableFuture;

/**
 * Minimal client stub for {@link TextDocumentServiceTest}. Swallows
 * server-initiated notifications; tests assert on responses to client
 * requests, not on these.
 */
class TestLanguageClient implements LanguageClient {

    @Override public void telemetryEvent(Object object) {}
    @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
    @Override public void showMessage(MessageParams messageParams) {}
    @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
    }
    @Override public void logMessage(MessageParams message) {}
}
