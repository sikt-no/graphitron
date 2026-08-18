package no.sikt.graphitron.lsp.server;

import no.sikt.graphitron.lsp.code_action.CodeActions;
import no.sikt.graphitron.lsp.completions.Completions;
import no.sikt.graphitron.lsp.definition.DeclarationDefinitions;
import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.definition.IntraSchemaDefinitions;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.hover.Hovers;
import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.lsp.trace.LspTrace;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Text-document handlers backed by a {@link Workspace}.
 *
 * <p>Lifecycle notifications populate / mutate the workspace and then
 * publish diagnostics for any files the workspace flagged for
 * recalculation; the completion request resolves the directive at the
 * cursor and dispatches to the matching per-directive completion
 * provider.
 */
public class GraphitronTextDocumentService implements TextDocumentService {

    private final Workspace workspace;
    private final Consumer<String> onSchemaSaved;
    private LanguageClient client;

    public GraphitronTextDocumentService(Workspace workspace) {
        this(workspace, uri -> {});
    }

    public GraphitronTextDocumentService(Workspace workspace, Consumer<String> onSchemaSaved) {
        this.workspace = workspace;
        this.onSchemaSaved = onSchemaSaved;
    }

    /**
     * Wired by {@link GraphitronLanguageServer#connect} once lsp4j has
     * exchanged capabilities and the client proxy exists. Until this
     * fires the service still works for tests that drive completions
     * without a paired client; diagnostic publishes are silently
     * skipped. Also registers the workspace's recalculate listener so
     * every queue-mutating workspace method (editor events plus the
     * build-trigger paths from {@code DevMojo}) drains and publishes
     * diagnostics through the same seam.
     */
    public void setClient(LanguageClient client) {
        this.client = client;
        workspace.setRecalculateListener(this::publishDiagnosticsForRecalculate);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var doc = params.getTextDocument();
        try (var span = LspTrace.span("didOpen")) {
            span.detail("uri", doc.getUri()).detail("chars", doc.getText().length());
            workspace.didOpen(doc.getUri(), doc.getVersion(), doc.getText());
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        var doc = params.getTextDocument();
        try (var span = LspTrace.span("didChange")) {
            span.detail("uri", doc.getUri())
                .detail("version", doc.getVersion())
                .detail("changes", params.getContentChanges().size());
            workspace.didChange(doc.getUri(), doc.getVersion(), params.getContentChanges());
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        try (var span = LspTrace.span("didClose")) {
            span.detail("uri", uri);
            // Clear any diagnostics the client may still be holding for the
            // closed file. Other dependents recalculate via the workspace's
            // recalculate listener as part of the didClose call below.
            if (client != null) {
                client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
            }
            workspace.didClose(uri);
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        try (var span = LspTrace.span("didSave")) {
            span.detail("uri", params.getTextDocument().getUri());
            onSchemaSaved.accept(params.getTextDocument().getUri());
        }
    }

    /**
     * Drains the workspace's recalculation queue and publishes a fresh
     * diagnostic list for each touched file. No-op when the client has
     * not connected yet (test harnesses).
     */
    private void publishDiagnosticsForRecalculate() {
        if (client == null) return;
        var queued = workspace.drainRecalculate();
        // Traced as one span around the whole drain: it runs inline on the caller's thread, so the
        // outer duration is what a caller pays for its mutation. The breakdown inside it follows the
        // stages rather than the files, which is what separates "many files" from "one slow file"
        // now that the read is not per file: one walk span per file, all of them CPU over a tree, and
        // the store's single answer inside the one transaction below.
        try (var drainSpan = LspTrace.span("publishDiagnostics.drain")) {
            drainSpan.detail("files", queued.size());
            // Every queued file is walked first, which reads nothing, so the whole drain's questions
            // are known before any of them is resolved. A file with no open view is never added and
            // so is never published for, which is what the per-file null check used to say.
            var batch = new Diagnostics.Batch(
                workspace.vocabulary(), workspace.snapshot(), workspace.validationReport());
            var walked = new ArrayList<String>(queued.size());
            for (String uri : queued) {
                Boolean added = workspace.withView(uri, null, view -> {
                    batch.add(uri, view);
                    return Boolean.TRUE;
                });
                if (added != null) {
                    walked.add(uri);
                }
            }
            if (walked.isEmpty()) {
                return;
            }
            // One read transaction around the whole drain, so no two files are diagnosed from two
            // sides of a capture, and one statement per graph inside it rather than one per file.
            var byUri = workspace.answeringAll(walked, batch::judgeAll);
            for (String uri : walked) {
                var diagnostics = byUri.get(uri);
                if (diagnostics == null) continue;
                try (var fileSpan = LspTrace.span("publishDiagnostics.file")) {
                    fileSpan.detail("uri", uri).detail("diagnostics", diagnostics.size());
                    client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
                }
            }
        }
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try (var span = LspTrace.span("codeAction")) {
                span.detail("uri", params.getTextDocument().getUri());
                var actions = CodeActions.compute(params, workspace);
                span.detail("actions", actions.size());
                return actions;
            }
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            try (var span = LspTrace.span("definition")) {
                span.detail("uri", uri);
                Either<List<? extends Location>, List<? extends LocationLink>> result =
                    workspace.withView(uri, null, file -> {
                        var pos = Positions.resolve(file.source(),
                            params.getPosition().getLine(),
                            params.getPosition().getCharacter()).tsPoint();
                        // IntraSchemaDefinitions takes its own withAllViews (the lock is
                        // released before this lambda runs, so that is not re-entrant); it
                        // returns a read-only Location, so a per-provider generation skew is
                        // harmless here in a way it would not be for a composed edit. All three
                        // providers share one read transaction for the same reason hover takes
                        // one: a chain that fell through to a second read could decline on a
                        // declaration the first read positioned.
                        return workspace.answering(uri, store ->
                            Definitions.compute(workspace.vocabulary(), file, store, pos)
                                .or(() -> IntraSchemaDefinitions.compute(workspace, store, uri, pos))
                                .or(() -> DeclarationDefinitions.compute(
                                    file, store, workspace.snapshot(), pos)))
                            .map(loc -> Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(loc)))
                            .orElseGet(() -> Either.forLeft(List.of()));
                    });
                return result != null ? result : Either.forLeft(List.of());
            }
        });
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try (var span = LspTrace.span("inlayHint")) {
                span.detail("uri", params.getTextDocument().getUri());
                String uri = params.getTextDocument().getUri();
                // One read transaction around the whole region, as hover takes: two declarations
                // annotated from either side of a capture would disagree about the same schema.
                var hints = workspace.withView(uri, List.<InlayHint>of(), file ->
                    workspace.answering(uri, store ->
                        InlayHints.compute(workspace.inlayHintConfig(), file, store,
                            workspace.snapshot(), params.getRange())));
                span.detail("hints", hints.size());
                return hints;
            }
        });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try (var span = LspTrace.span("hover")) {
                span.detail("uri", params.getTextDocument().getUri());
                String uri = params.getTextDocument().getUri();
                return workspace.withView(uri, null, file -> {
                    var pos = Positions.resolve(file.source(),
                        params.getPosition().getLine(),
                        params.getPosition().getCharacter()).tsPoint();
                    // One read transaction around the whole popup, as completion takes: a hover
                    // assembled from two snapshots could name a class from before a capture and
                    // describe it from after.
                    return workspace.answering(uri, store ->
                        Hovers.compute(workspace.vocabulary(), file, workspace.catalog(), store,
                            workspace.snapshot(), pos,
                            workspace.inlayHintConfig().hoverClassification())).orElse(null);
                });
            }
        });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try (var span = LspTrace.span("completion")) {
                span.detail("uri", params.getTextDocument().getUri());
                return workspace.withView(params.getTextDocument().getUri(),
                    Either.<List<CompletionItem>, CompletionList>forLeft(List.of()), file -> {
                        // One snapshot feeds the position resolve, the directive scan, and
                        // Completions.at, so completion can no longer tear against an edit
                        // that lands between its own source and tree reads.
                        var pos = Positions.resolve(file.source(),
                            params.getPosition().getLine(),
                            params.getPosition().getCharacter()).tsPoint();
                        var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
                        if (directiveOpt.isEmpty()) {
                            span.detail("directive", "none");
                            return Either.forLeft(List.of());
                        }
                        var items = Completions.at(
                            workspace, params.getTextDocument().getUri(), directiveOpt.get(), pos,
                            params.getPosition(), file.source());
                        span.detail("items", items.size());
                        return Either.forLeft(items);
                    });
            }
        });
    }
}
