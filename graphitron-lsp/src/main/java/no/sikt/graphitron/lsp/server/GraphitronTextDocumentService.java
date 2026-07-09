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
        workspace.didOpen(doc.getUri(), doc.getVersion(), doc.getText());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        var doc = params.getTextDocument();
        workspace.didChange(doc.getUri(), doc.getVersion(), params.getContentChanges());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        // Clear any diagnostics the client may still be holding for the
        // closed file. Other dependents recalculate via the workspace's
        // recalculate listener as part of the didClose call below.
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
        workspace.didClose(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        onSchemaSaved.accept(params.getTextDocument().getUri());
    }

    /**
     * Drains the workspace's recalculation queue and publishes a fresh
     * diagnostic list for each touched file. No-op when the client has
     * not connected yet (test harnesses).
     */
    private void publishDiagnosticsForRecalculate() {
        if (client == null) return;
        for (String uri : workspace.drainRecalculate()) {
            var diagnostics = workspace.withView(uri, null, view ->
                Diagnostics.compute(
                    workspace.vocabulary(), uri, view, workspace.catalog(),
                    workspace.snapshot(), workspace.validationReport()));
            if (diagnostics != null) {
                client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
            }
        }
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFuture.supplyAsync(() -> CodeActions.compute(params, workspace));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            String uri = params.getTextDocument().getUri();
            Either<List<? extends Location>, List<? extends LocationLink>> result =
                workspace.withView(uri, null, file -> {
                    var pos = Positions.resolve(file.source(),
                        params.getPosition().getLine(),
                        params.getPosition().getCharacter()).tsPoint();
                    // IntraSchemaDefinitions takes its own withAllViews (the lock is
                    // released before this lambda runs, so that is not re-entrant); it
                    // returns a read-only Location, so a per-provider generation skew is
                    // harmless here in a way it would not be for a composed edit.
                    return Definitions.compute(workspace.vocabulary(), file, workspace.catalog(),
                            workspace.sourceIndex(), workspace.snapshot(), pos)
                        .or(() -> IntraSchemaDefinitions.compute(workspace, workspace.snapshot(), uri, pos))
                        .or(() -> DeclarationDefinitions.compute(file, workspace.catalog(),
                            workspace.sourceIndex(), workspace.snapshot(), pos))
                        .map(loc -> Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(loc)))
                        .orElseGet(() -> Either.forLeft(List.of()));
                });
            return result != null ? result : Either.forLeft(List.of());
        });
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        return CompletableFuture.supplyAsync(() ->
            workspace.withView(params.getTextDocument().getUri(), List.of(), file ->
                InlayHints.compute(
                    workspace.inlayHintConfig(), file, workspace.snapshot(), params.getRange())));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() ->
            workspace.withView(params.getTextDocument().getUri(), null, file -> {
                var pos = Positions.resolve(file.source(),
                    params.getPosition().getLine(),
                    params.getPosition().getCharacter()).tsPoint();
                return Hovers.compute(workspace.vocabulary(), file, workspace.catalog(),
                    workspace.sourceIndex(), workspace.snapshot(), pos,
                    workspace.inlayHintConfig().hoverClassification()).orElse(null);
            }));
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() ->
            workspace.withView(params.getTextDocument().getUri(),
                Either.<List<CompletionItem>, CompletionList>forLeft(List.of()), file -> {
                    // One snapshot feeds the position resolve, the directive scan, and
                    // Completions.at, so completion can no longer tear against an edit
                    // that lands between its own source and tree reads.
                    var pos = Positions.resolve(file.source(),
                        params.getPosition().getLine(),
                        params.getPosition().getCharacter()).tsPoint();
                    var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
                    if (directiveOpt.isEmpty()) {
                        return Either.forLeft(List.of());
                    }
                    return Either.forLeft(Completions.at(
                        workspace, directiveOpt.get(), pos, params.getPosition(), file.source()));
                }));
    }
}
