package no.sikt.graphitron.lsp;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Minimal client stub for {@link TextDocumentServiceTest}. Captures the
 * latest {@code publishDiagnostics} payload per URI so tests that care
 * about diagnostic flow can assert on it; everything else is swallowed.
 */
class TestLanguageClient implements LanguageClient {

    final ConcurrentMap<String, PublishDiagnosticsParams> latestDiagnostics = new ConcurrentHashMap<>();

    @Override public void telemetryEvent(Object object) {}
    @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        latestDiagnostics.put(diagnostics.getUri(), diagnostics);
    }
    @Override public void showMessage(MessageParams messageParams) {}
    @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
    }
    @Override public void logMessage(MessageParams message) {}
}
