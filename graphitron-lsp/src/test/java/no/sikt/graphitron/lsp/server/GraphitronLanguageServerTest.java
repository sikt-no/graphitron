package no.sikt.graphitron.lsp.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the initialisation-time {@code workspace/configuration} pull path. The
 * spec commits to both push (via {@code workspace/didChangeConfiguration}) AND pull (via
 * {@code workspace/configuration}) for the three inlay-hint / hover toggles; this test
 * pins the pull half so clients that don't push on init still see the right state on
 * first request.
 */
class GraphitronLanguageServerTest {

    @Test
    void initialisedHandlerPullsGraphitronConfigAndAppliesIt() {
        var workspace = new Workspace();
        var server = new GraphitronLanguageServer(workspace);

        // Pulled section the client returns when asked for "graphitron".
        var inlay = new JsonObject();
        inlay.add("inferredDirectives", new JsonPrimitive(true));
        inlay.add("classification", new JsonPrimitive(true));
        var hover = new JsonObject();
        hover.add("classification", new JsonPrimitive(true));
        var graphitronSection = new JsonObject();
        graphitronSection.add("inlayHints", inlay);
        graphitronSection.add("hover", hover);

        var requestCapture = new AtomicReference<ConfigurationParams>();
        var client = new RecordingLanguageClient(requestCapture, graphitronSection);
        server.connect(client);

        server.initialized(new InitializedParams());

        assertThat(requestCapture.get()).isNotNull();
        assertThat(requestCapture.get().getItems()).hasSize(1);
        assertThat(requestCapture.get().getItems().get(0).getSection()).isEqualTo("graphitron");
        assertThat(workspace.inlayHintConfig().inferredDirectives()).isTrue();
        assertThat(workspace.inlayHintConfig().classification()).isTrue();
        assertThat(workspace.inlayHintConfig().hoverClassification()).isTrue();
    }

    @Test
    void initialisedHandlerIsNoOpWhenClientNotYetConnected() {
        // Test harnesses may drive initialized() without first calling connect(); the
        // server should silently no-op rather than NPE. Workspace state stays default.
        var workspace = new Workspace();
        var server = new GraphitronLanguageServer(workspace);
        server.initialized(new InitializedParams());
        assertThat(workspace.inlayHintConfig().anyEnabled()).isFalse();
    }

    @Test
    void initialisedHandlerHandlesClientThatReturnsNullForSection() {
        // Some LSP clients respond to workspace/configuration with a list of nulls when
        // the requested section is absent from their settings. The handler swallows this
        // and leaves the workspace at defaults.
        var workspace = new Workspace();
        var server = new GraphitronLanguageServer(workspace);
        var client = new RecordingLanguageClient(new AtomicReference<>(), null);
        server.connect(client);
        server.initialized(new InitializedParams());
        assertThat(workspace.inlayHintConfig().anyEnabled()).isFalse();
    }

    private static final class RecordingLanguageClient implements LanguageClient {
        private final AtomicReference<ConfigurationParams> requestCapture;
        private final Object sectionResponse;

        RecordingLanguageClient(AtomicReference<ConfigurationParams> requestCapture,
                                Object sectionResponse) {
            this.requestCapture = requestCapture;
            this.sectionResponse = sectionResponse;
        }

        @Override
        public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
            requestCapture.set(params);
            return CompletableFuture.completedFuture(java.util.Collections.singletonList(sectionResponse));
        }

        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
        @Override public void showMessage(MessageParams messageParams) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) {}
        @Override public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
