package no.sikt.graphitron.lsp;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.server.GraphitronTextDocumentService;
import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline-tier coverage of the build-trigger publish path. Drives a
 * {@code setBuildOutput} call against a wired {@link Workspace} +
 * {@link GraphitronTextDocumentService} pair and asserts that the captured {@link LanguageClient}
 * sees a fresh {@code publishDiagnostics} payload per touched URI, without waiting for a keystroke.
 * The bug it was written for (the listener seam absent) left the queue sitting until the next editor
 * event.
 *
 * <p>What a round found rides the store rather than the swap, so the case writes the round's findings
 * where the round writes them and then trips the swap, which is the order a dev round runs in.
 *
 * <p>The second case pins the other half of the same cadence: between rounds, an edit publishes
 * nothing. The two together are what "diagnostics ride the capture cadence" means on the wire.
 */
class BuildTriggerPublishesDiagnosticsTest {

    private static final String SDL = "type Foo { x: Int }\n";

    @TempDir
    Path tmp;

    @Test
    void setBuildOutputPublishesDiagnosticsForOpenFiles() {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            String uri = Path.of(fixture.sourceName()).toUri().toString();
            var workspace = new Workspace();
            workspace.setStore(new StoreAccess(fixture.reader(), StoreFixture.GRAPH));
            var service = new GraphitronTextDocumentService(workspace);
            var client = new RecordingClient();
            service.setClient(client);

            // 1. Open the file. didOpen flows through the listener, so the pre-build diagnostics
            //    (a graph whose build recorded nothing) ship as part of the open path itself.
            workspace.didOpen(uri, 1, SDL);
            assertThat(client.published).hasSize(1);
            assertThat(client.published.getFirst().getUri()).isEqualTo(uri);

            // 2. A round that refused the open file, then the swap. The listener fires from
            //    setBuildOutput -> markAllForRecalculation, drains the queue, and ships the finding
            //    to the client without waiting for another keystroke.
            fixture.withValidationErrors(List.of(new ValidationError("Foo.x",
                Rejection.structural("invalid type"),
                new SourceLocation(1, 1, fixture.sourceName()))));
            workspace.setBuildOutput(buildArtifacts());

            assertThat(client.published).hasSize(2);
            var afterError = client.published.get(1);
            assertThat(afterError.getUri()).isEqualTo(uri);
            assertThat(afterError.getDiagnostics())
                .as("the round's finding should be on the wire after setBuildOutput")
                .isNotEmpty();
            assertThat(afterError.getDiagnostics().getFirst().getMessage()).contains("invalid type");

            // 3. A round that found nothing. The wire-level "clear" signal arrives as an empty
            //    diagnostic list for the same URI; the squiggle goes away on save with no keystroke.
            fixture.withValidationErrors(List.of());
            workspace.setBuildOutput(buildArtifacts());

            assertThat(client.published).hasSize(3);
            var afterClear = client.published.get(2);
            assertThat(afterClear.getUri()).isEqualTo(uri);
            assertThat(afterClear.getDiagnostics())
                .as("a round that found nothing ships an empty list, clearing the previous error")
                .isEmpty();
        }
    }

    @Test
    void anEditPublishesNothingAndTheNextRoundClearsWithoutOne() {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            String uri = Path.of(fixture.sourceName()).toUri().toString();
            var workspace = new Workspace();
            workspace.setStore(new StoreAccess(fixture.reader(), StoreFixture.GRAPH));
            var service = new GraphitronTextDocumentService(workspace);
            var client = new RecordingClient();
            service.setClient(client);

            fixture.withValidationErrors(List.of(new ValidationError("Foo.x",
                Rejection.structural("invalid type"),
                new SourceLocation(1, 1, fixture.sourceName()))));
            workspace.didOpen(uri, 1, SDL);
            assertThat(client.published).hasSize(1);
            assertThat(client.published.getFirst().getDiagnostics()).isNotEmpty();

            // The author deletes the field the round refused. Nothing publishes: no capture has read
            // this text, and what the file shows until one does is what the graph last said about it.
            workspace.didChange(uri, 2,
                List.of(new TextDocumentContentChangeEvent("type Foo { y: Int }\n")));

            assertThat(client.published)
                .as("an edit is not a capture, so it puts nothing on the wire")
                .hasSize(1);

            // The save's round finds nothing, and the swap clears the squiggle, still with no keystroke.
            fixture.withValidationErrors(List.of());
            workspace.setBuildOutput(buildArtifacts());

            assertThat(client.published).hasSize(2);
            assertThat(client.published.get(1).getDiagnostics()).isEmpty();
        }
    }

    private static GraphQLRewriteGenerator.BuildArtifacts buildArtifacts() {
        return new GraphQLRewriteGenerator.BuildArtifacts(
            CompletionData.empty(),
            new LspSchemaSnapshot.Built());
    }

    private static final class RecordingClient implements LanguageClient {
        final List<PublishDiagnosticsParams> published = new ArrayList<>();

        @Override public void telemetryEvent(Object object) {}
        @Override public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            published.add(diagnostics);
        }
        @Override public void showMessage(MessageParams messageParams) {}
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams r) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void logMessage(MessageParams message) {}
    }
}
