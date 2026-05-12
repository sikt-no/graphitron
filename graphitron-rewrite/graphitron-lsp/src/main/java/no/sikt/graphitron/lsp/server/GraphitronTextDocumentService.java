package no.sikt.graphitron.lsp.server;

import no.sikt.graphitron.lsp.code_action.CodeActions;
import no.sikt.graphitron.lsp.completions.ArgNameCompletions;
import no.sikt.graphitron.lsp.completions.ClassNameCompletions;
import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.completions.MethodCompletions;
import no.sikt.graphitron.lsp.completions.ReferenceCompletions;
import no.sikt.graphitron.lsp.completions.ScalarTypeCompletions;
import no.sikt.graphitron.lsp.completions.TableCompletions;
import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.hover.Hovers;
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
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    private LanguageClient client;

    public GraphitronTextDocumentService(Workspace workspace) {
        this.workspace = workspace;
    }

    /**
     * Wired by {@link GraphitronLanguageServer#connect} once lsp4j has
     * exchanged capabilities and the client proxy exists. Until this
     * fires the service still works for tests that drive completions
     * without a paired client; diagnostic publishes are silently
     * skipped.
     */
    public void setClient(LanguageClient client) {
        this.client = client;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        var doc = params.getTextDocument();
        workspace.didOpen(doc.getUri(), doc.getVersion(), doc.getText());
        publishDiagnosticsForRecalculate();
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        var doc = params.getTextDocument();
        workspace.didChange(doc.getUri(), doc.getVersion(), params.getContentChanges());
        publishDiagnosticsForRecalculate();
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        workspace.didClose(uri);
        // Clear any diagnostics the client may still be holding for the
        // closed file. Other dependents recalculate as usual below.
        if (client != null) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
        }
        publishDiagnosticsForRecalculate();
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Save itself does not change buffer state for the LSP. The dev
        // goal's filesystem watcher drives regeneration off disk writes,
        // not off this notification.
    }

    /**
     * Drains the workspace's recalculation queue and publishes a fresh
     * diagnostic list for each touched file. No-op when the client has
     * not connected yet (test harnesses).
     */
    private void publishDiagnosticsForRecalculate() {
        if (client == null) return;
        for (String uri : workspace.drainRecalculate()) {
            workspace.get(uri).ifPresent(file -> {
                var diagnostics = Diagnostics.compute(
                    workspace.vocabulary(), file, workspace.catalog(), workspace.snapshot());
                client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
            });
        }
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFuture.supplyAsync(() -> CodeActions.compute(params, workspace));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var fileOpt = workspace.get(params.getTextDocument().getUri());
            if (fileOpt.isEmpty()) return Either.forLeft(List.of());
            var file = fileOpt.get();
            var pos = Positions.resolve(file.source(),
                params.getPosition().getLine(),
                params.getPosition().getCharacter()).tsPoint();
            return Definitions.compute(file, workspace.catalog(), pos)
                .map(loc -> Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(loc)))
                .orElseGet(() -> Either.forLeft(List.of()));
        });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var fileOpt = workspace.get(params.getTextDocument().getUri());
            if (fileOpt.isEmpty()) return null;
            var file = fileOpt.get();
            var pos = Positions.resolve(file.source(),
                params.getPosition().getLine(),
                params.getPosition().getCharacter()).tsPoint();
            return Hovers.compute(workspace.vocabulary(), file, workspace.catalog(), pos).orElse(null);
        });
    }

    /**
     * For {@code @service} / {@code @condition}, the cursor decides whether
     * we offer class-name FQNs (when in {@code <outer>.className}) or
     * method names off the sibling class (when in {@code <outer>.method}).
     * One of the two providers returns non-empty; the other returns
     * empty.
     */
    /**
     * Single-walk dispatch: every coordinate-driven completion provider
     * pattern-matches on the cursor's behavior arm. The first non-empty
     * list wins; behaviors are mutually exclusive at any one coordinate
     * so the order is incidental.
     */
    private static List<CompletionItem> coordinateBasedCompletions(
        no.sikt.graphitron.lsp.state.Workspace workspace,
        no.sikt.graphitron.lsp.parsing.Directives.Directive directive,
        io.github.treesitter.jtreesitter.Point pos,
        byte[] source
    ) {
        var data = workspace.catalog();
        var vocab = workspace.vocabulary();
        var classItems = ClassNameCompletions.generate(vocab, data, directive, pos, source);
        if (!classItems.isEmpty()) return classItems;
        var methodItems = MethodCompletions.generate(vocab, data, directive, pos, source);
        if (!methodItems.isEmpty()) return methodItems;
        var tableItems = TableCompletions.generate(vocab, data, directive, pos, source);
        if (!tableItems.isEmpty()) return tableItems;
        var fieldItems = FieldCompletions.generate(vocab, data, directive, pos, source);
        if (!fieldItems.isEmpty()) return fieldItems;
        var refItems = ReferenceCompletions.generate(vocab, data, directive, pos, source);
        if (!refItems.isEmpty()) return refItems;
        var scalarItems = ScalarTypeCompletions.generate(vocab, data, directive, pos, source);
        if (!scalarItems.isEmpty()) return scalarItems;
        // Arg-name fallback last: catalog-aware providers above return
        // value completions for known coordinates; this provider fires
        // when the cursor is at an arg-name slot rather than a value slot.
        var argNameItems = ArgNameCompletions.generate(vocab, directive, pos, source);
        if (!argNameItems.isEmpty()) return argNameItems;
        return List.of();
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var fileOpt = workspace.get(params.getTextDocument().getUri());
            if (fileOpt.isEmpty()) {
                return Either.forLeft(List.of());
            }
            var file = fileOpt.get();
            var pos = Positions.resolve(file.source(),
                params.getPosition().getLine(),
                params.getPosition().getCharacter()).tsPoint();
            var directiveOpt = Directives.findContaining(file.tree().getRootNode(), pos);
            if (directiveOpt.isEmpty()) {
                return Either.forLeft(List.of());
            }
            var directive = directiveOpt.get();
            return Either.forLeft(
                coordinateBasedCompletions(workspace, directive, pos, file.source()));
        });
    }
}
