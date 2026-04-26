package no.sikt.graphitron.lsp.server;

import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.completions.ReferenceCompletions;
import no.sikt.graphitron.lsp.completions.TableCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Text-document handlers backed by a {@link Workspace}.
 *
 * <p>Lifecycle notifications populate / mutate the workspace; the
 * completion request resolves the directive at the cursor and dispatches
 * to the matching per-directive completion provider. Only {@code @table}
 * is wired in slice 1; {@code @field}, {@code @reference}, etc. land in
 * Phase 3 and slot in here behind the same dispatch.
 */
public class GraphitronTextDocumentService implements TextDocumentService {

    private final Workspace workspace;

    public GraphitronTextDocumentService(Workspace workspace) {
        this.workspace = workspace;
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
        workspace.didClose(params.getTextDocument().getUri());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Save itself does not change buffer state for the LSP. The dev
        // goal's filesystem watcher (Phase 1, slice 2) drives regeneration
        // off disk writes, not off this notification.
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
            var directiveName = Nodes.text(directive.nameNode(), file.source());
            List<CompletionItem> items = switch (directiveName) {
                case "table" -> TableCompletions.generate(workspace.catalog(), directive, pos, file.source());
                case "field" -> FieldCompletions.generate(workspace.catalog(), directive, pos, file.source());
                case "reference" -> ReferenceCompletions.generate(workspace.catalog(), directive, pos, file.source());
                default -> List.of();
            };
            return Either.forLeft(items);
        });
    }
}
