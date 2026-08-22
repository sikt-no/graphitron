package no.sikt.graphitron.lsp.server;

import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the workspace's recalculate slot across a connection ending. The {@code dev} goal shares
 * one workspace between editor connections, so the slot outlives whoever filled it.
 *
 * <p>Driven through the service's own register-and-clear path rather than by handing the
 * workspace test-authored listeners. The clear compares on identity, and a test that registers
 * its own lambda would compare that instance against itself and pass no matter what identity the
 * production registration uses, which is exactly the mistake worth pinning against.
 */
class ConnectionTeardownTest {

    private static final String URI = "file:///schema.graphqls";
    private static final String SOURCE = "type Query { film: Int }\n";

    @Test
    @DisplayName("teardown clears the slot the connection filled")
    void teardownClearsItsOwnRegistration() {
        var workspace = new Workspace();
        var client = new RecordingClient();
        var service = new GraphitronTextDocumentService(workspace);
        service.setClient(client);

        // The default drain executor is same-thread, so the publish is done when the mutator
        // returns and the assertions need no waiting.
        workspace.didOpen(URI, 1, SOURCE);
        assertThat(client.published())
            .as("the registration is live before teardown, so the pin can tell the two apart")
            .isNotEmpty();

        service.disconnect();
        client.forget();
        workspace.markAllForRecalculation();

        assertThat(client.published())
            .as("a mutation after teardown reaches nothing")
            .isEmpty();
    }

    @Test
    @DisplayName("teardown leaves a reconnect's registration alone")
    void teardownDoesNotClearASlotAReconnectTook() {
        var workspace = new Workspace();

        var departing = new RecordingClient();
        var departingService = new GraphitronTextDocumentService(workspace);
        departingService.setClient(departing);

        // The reconnect registers before the old connection's teardown runs, which is the
        // ordering an unconditional clear would break: it would silently stop diagnostics for
        // the live editor.
        var arriving = new RecordingClient();
        var arrivingService = new GraphitronTextDocumentService(workspace);
        arrivingService.setClient(arriving);

        departingService.disconnect();
        workspace.didOpen(URI, 1, SOURCE);

        assertThat(arriving.published())
            .as("the live connection still publishes")
            .isNotEmpty();
        assertThat(departing.published())
            .as("the departed connection was already out of the slot, so it hears nothing")
            .isEmpty();
    }

    /** Records the diagnostics pushed to one connection. */
    private static final class RecordingClient implements LanguageClient {
        private final List<String> published = new ArrayList<>();

        synchronized List<String> published() {
            return List.copyOf(published);
        }

        synchronized void forget() {
            published.clear();
        }

        @Override
        public synchronized void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            published.add(diagnostics.getUri());
        }

        @Override public void telemetryEvent(Object object) {}
        @Override public void showMessage(MessageParams params) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(
            ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) {}
    }
}
