package no.sikt.graphitron.lsp.server;

import no.sikt.graphitron.lsp.code_action.CodeActions;
import no.sikt.graphitron.lsp.completions.ArgMappingCompletions;
import no.sikt.graphitron.lsp.completions.ArgNameCompletions;
import no.sikt.graphitron.lsp.completions.ClassNameCompletions;
import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.ExternalFieldCompletions;
import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.completions.MethodCompletions;
import no.sikt.graphitron.lsp.completions.NodeTypeCompletions;
import no.sikt.graphitron.lsp.completions.ReferenceCompletions;
import no.sikt.graphitron.lsp.completions.ScalarTypeCompletions;
import no.sikt.graphitron.lsp.completions.TableCompletions;
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
            workspace.get(uri).ifPresent(file -> {
                var diagnostics = Diagnostics.compute(
                    workspace.vocabulary(), uri, file, workspace.catalog(),
                    workspace.snapshot(), workspace.validationReport());
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
            return Definitions.compute(workspace.vocabulary(), file, workspace.catalog(), workspace.snapshot(), pos)
                .or(() -> IntraSchemaDefinitions.compute(workspace, workspace.snapshot(), params.getTextDocument().getUri(), pos))
                .map(loc -> Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(loc)))
                .orElseGet(() -> Either.forLeft(List.of()));
        });
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        return CompletableFuture.supplyAsync(() -> {
            var fileOpt = workspace.get(params.getTextDocument().getUri());
            if (fileOpt.isEmpty()) return List.of();
            return InlayHints.compute(
                workspace.inlayHintConfig(), fileOpt.get(), workspace.snapshot(), params.getRange());
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
            return Hovers.compute(workspace.vocabulary(), file, workspace.catalog(),
                workspace.snapshot(), pos,
                workspace.inlayHintConfig().hoverClassification()).orElse(null);
        });
    }

    /**
     * Single-walk dispatch: {@link no.sikt.graphitron.lsp.parsing.LspVocabulary#locateAt}
     * runs once for the cursor and the coordinate-driven providers each
     * pattern-match on the behavior arm of the resulting coordinate. The
     * first non-empty list wins; behaviors are mutually exclusive at any
     * one coordinate so the order is incidental. Locating produces a
     * {@link CompletionContext} carrying the replace range so every value
     * item ships an explicit {@code TextEdit} (R153 — clients otherwise
     * apply per-client word-boundary heuristics to dotted candidates).
     *
     * <p>When {@code locateAt} is empty (cursor outside any directive
     * arg's value, on the arg-name side, or on whitespace inside an
     * {@code object_value}), the value providers are skipped entirely
     * and dispatch falls straight through to
     * {@link ArgNameCompletions}, which has its own walk for partial
     * arg-name identifiers and zero-width insertion points.
     */
    private static List<CompletionItem> coordinateBasedCompletions(
        no.sikt.graphitron.lsp.state.Workspace workspace,
        no.sikt.graphitron.lsp.parsing.Directives.Directive directive,
        io.github.treesitter.jtreesitter.Point pos,
        org.eclipse.lsp4j.Position lspPos,
        byte[] source
    ) {
        var data = workspace.catalog();
        var vocab = workspace.vocabulary();
        var locationOpt = vocab.locateAt(directive, pos, source);
        if (locationOpt.isPresent()) {
            var context = CompletionContext.from(locationOpt.get(), source);
            var classItems = ClassNameCompletions.generate(vocab, data, context);
            if (!classItems.isEmpty()) return classItems;
            // @externalField narrows the method list to Field-returning lifters;
            // runs ahead of the generic method provider so the narrowed list wins,
            // and falls through to it when the class exposes no matching method.
            var externalFieldItems = ExternalFieldCompletions.generate(vocab, data, context, directive, pos, source);
            if (!externalFieldItems.isEmpty()) return externalFieldItems;
            var methodItems = MethodCompletions.generate(vocab, data, context, directive, pos, source);
            if (!methodItems.isEmpty()) return methodItems;
            var tableItems = TableCompletions.generate(vocab, data, context);
            if (!tableItems.isEmpty()) return tableItems;
            var fieldItems = FieldCompletions.generate(vocab, data, workspace.snapshot(), context, directive, source);
            if (!fieldItems.isEmpty()) return fieldItems;
            var refItems = ReferenceCompletions.generate(vocab, data, workspace.snapshot(), context, directive, source);
            if (!refItems.isEmpty()) return refItems;
            var scalarItems = ScalarTypeCompletions.generate(vocab, data, context, directive, source);
            if (!scalarItems.isEmpty()) return scalarItems;
            var nodeTypeItems = NodeTypeCompletions.generate(vocab, data, context);
            if (!nodeTypeItems.isEmpty()) return nodeTypeItems;
            var argMappingItems = ArgMappingCompletions.generate(
                vocab, data, context, directive, pos, lspPos, source);
            if (!argMappingItems.isEmpty()) return argMappingItems;
        }
        // Arg-name fallback: fires both when locateAt is empty (cursor on
        // arg-name / whitespace) and when locateAt produced no value
        // matches above. Computes its own range independent of context.
        var argNameItems = ArgNameCompletions.generate(
            vocab, workspace.snapshot(), directive, pos, lspPos, source);
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
            return Either.forLeft(coordinateBasedCompletions(
                workspace, directive, pos, params.getPosition(), file.source()));
        });
    }
}
