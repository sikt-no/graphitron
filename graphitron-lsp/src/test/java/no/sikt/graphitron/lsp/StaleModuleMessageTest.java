package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.server.GraphitronTextDocumentService;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.config.WorkspaceModule;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workspace module the graph's run read stale reaches the editor as a message, a stale module
 * being located in no schema file, and is said once rather than on every drain.
 */
class StaleModuleMessageTest {

    private static final String SDL = "type Foo { x: Int }\n";

    @TempDir
    Path tmp;

    @Test
    void aModuleReadFromItsInstalledCopyIsSaidOnce() throws IOException {
        Path installed = Files.createDirectories(tmp.resolve("repo")).resolve("service-1.0-SNAPSHOT.jar");
        try (OutputStream out = Files.newOutputStream(installed); var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/"));
            zip.closeEntry();
        }
        var module = WorkspaceModule.conventional("ex:service", tmp.resolve("service"));
        var entry = new ClasspathEntry(installed, ClasspathEntry.Origin.REACTOR, "ex:service", null,
            ClasspathEntry.Kind.SNAPSHOT, module);

        try (var fixture = StoreFixture.of(tmp.resolve("graph"), SDL, List.of(entry))) {
            String uri = Path.of(fixture.sourceName()).toUri().toString();
            var workspace = new Workspace();
            workspace.setStore(fixture.access());
            var service = new GraphitronTextDocumentService(workspace, ignored -> {}, Runnable::run);
            var client = new RecordingClient();
            service.setClient(client);

            workspace.didOpen(uri, 1, SDL);
            assertThat(client.shown).singleElement().satisfies(message -> {
                assertThat(message.getType()).isEqualTo(MessageType.Warning);
                assertThat(message.getMessage()).startsWith("ex:service has no compiled output")
                    .endsWith("mvn -pl :service compile");
            });

            workspace.markAllForRecalculation();
            assertThat(client.shown).as("a module that stays stale is not said again").hasSize(1);
        }
    }

    private static final class RecordingClient implements LanguageClient {
        final List<MessageParams> shown = new ArrayList<>();

        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}
        @Override public void showMessage(MessageParams messageParams) {
            shown.add(messageParams);
        }
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams r) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) {}
    }
}
