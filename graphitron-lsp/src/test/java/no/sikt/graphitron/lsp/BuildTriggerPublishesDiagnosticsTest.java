package no.sikt.graphitron.lsp;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.server.GraphitronTextDocumentService;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline-tier coverage of the build-trigger publish path.
 * Drives a {@code setBuildOutput} call against a wired
 * {@link Workspace} + {@link GraphitronTextDocumentService} pair and
 * asserts that the captured {@link LanguageClient} sees a fresh
 * {@code publishDiagnostics} payload per touched URI. Today's bug
 * (the listener seam absent) failed this test by sitting on the queue
 * until the next editor event; the listener wire-up makes it pass.
 */
class BuildTriggerPublishesDiagnosticsTest {

    private static final String URI = "file:///a.graphqls";

    @Test
    void setBuildOutputPublishesDiagnosticsForOpenFiles() {
        var workspace = new Workspace();
        var service = new GraphitronTextDocumentService(workspace);
        var client = new RecordingClient();
        service.setClient(client);

        // 1. Open the file. didOpen flows through the listener now, so the
        //    pre-build diagnostics (empty report, Unavailable snapshot) ship
        //    on the wire as part of the open path itself.
        workspace.didOpen(URI, 1, "type Foo { x: Int }\n");
        assertThat(client.published).hasSize(1);
        assertThat(client.published.get(0).getUri()).isEqualTo(URI);

        // 2. Build trigger with a validator error on the open file. The
        //    listener fires from setBuildOutput -> markAllForRecalculation,
        //    drains the queue, and ships the error to the client without
        //    waiting for another keystroke. Before the listener seam this hung
        //    until the user typed.
        String path = pathFromUri(URI);
        var error = new ValidationError(
            "Foo.x",
            Rejection.structural("invalid type"),
            new SourceLocation(1, 1, path));
        var reportWithError = ValidationReport.from(List.of(error), List.of());
        workspace.setBuildOutput(buildArtifacts(), reportWithError);

        assertThat(client.published).hasSize(2);
        var afterError = client.published.get(1);
        assertThat(afterError.getUri()).isEqualTo(URI);
        assertThat(afterError.getDiagnostics())
            .as("validator error should be on the wire after setBuildOutput")
            .isNotEmpty();
        assertThat(afterError.getDiagnostics().get(0).getMessage()).contains("invalid type");

        // 3. Build trigger with empty report. The wire-level "clear" signal
        //    arrives as an empty diagnostic list for the same URI; the
        //    editor's squiggle goes away on save without a keystroke.
        workspace.setBuildOutput(buildArtifacts(), ValidationReport.empty());

        assertThat(client.published).hasSize(3);
        var afterClear = client.published.get(2);
        assertThat(afterClear.getUri()).isEqualTo(URI);
        assertThat(afterClear.getDiagnostics())
            .as("empty report should ship an empty diagnostic list, clearing the previous error")
            .isEmpty();
    }

    private static GraphQLRewriteGenerator.BuildArtifacts buildArtifacts() {
        return new GraphQLRewriteGenerator.BuildArtifacts(
            CompletionData.empty(),
            new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of()));
    }

    private static String pathFromUri(String uri) {
        // The validator's SourceLocation.sourceName is an absolute path; the
        // LSP filter canonicalises it back through ValidationReport.canonicalUri
        // to a file:// URI. Hand back the path form so the canonical form
        // matches the open file's URI.
        return java.nio.file.Path.of(java.net.URI.create(uri)).toString();
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
