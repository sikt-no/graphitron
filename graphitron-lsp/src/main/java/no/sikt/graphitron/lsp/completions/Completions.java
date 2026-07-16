package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.state.Workspace;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;

import java.util.List;

/**
 * Completion dispatch for one cursor. Runs {@code LspVocabulary.locateAt} once
 * to resolve the coordinate under the cursor, resolves its {@link Behavior}
 * once, and hands both to the providers {@link #providersFor} registers for
 * that arm; the first non-empty result wins. When no coordinate resolves (the
 * cursor is on the arg-name side or on whitespace) or no value provider matched,
 * dispatch falls through to {@link ArgNameCompletions}.
 *
 * <p>This replaces the hand-maintained 40-line waterfall that lived in
 * {@code GraphitronTextDocumentService}, where each arm called a
 * provider with its own positional signature and the load-bearing ordering
 * (e.g. {@code @externalField}'s narrowed method list ahead of the generic one)
 * survived only in a comment. The ordering is now data: the list order in
 * {@link #providersFor}. The exhaustive switch over the sealed {@link Behavior}
 * means a new behavior arm is a compile error here until it names its
 * provider(s), rather than silently completing nothing.
 */
public final class Completions {

    private Completions() {}

    public static List<CompletionItem> at(
        Workspace workspace, Directives.Directive directive, Point pos, Position lspPos, byte[] source
    ) {
        var vocab = workspace.vocabulary();
        var locationOpt = vocab.locateAt(directive, pos, source);
        if (locationOpt.isPresent()) {
            var context = CompletionContext.from(locationOpt.get(), source);
            var behaviorOpt = vocab.behaviorAt(context.coordinate());
            if (behaviorOpt.isPresent()) {
                var request = new CompletionRequest(
                    vocab, workspace.catalog(), workspace.sourceIndex(), workspace.snapshot(),
                    context, directive, pos, lspPos, source);
                for (var provider : providersFor(behaviorOpt.get())) {
                    var items = provider.complete(request);
                    if (!items.isEmpty()) return items;
                }
            }
        }
        // Arg-name fallback: fires both when locateAt is empty (cursor on the
        // arg-name side or whitespace) and when locateAt produced no value
        // matches above. Computes its own range independent of any coordinate.
        var argNameItems = ArgNameCompletions.generate(
            vocab, workspace.snapshot(), directive, pos, lspPos, source);
        if (!argNameItems.isEmpty()) return argNameItems;
        return List.of();
    }

    /**
     * The ordered providers for a behavior arm. Exhaustive over the sealed
     * {@link Behavior}; {@link Behavior.MethodNameBinding} carries two, the
     * {@code @externalField}-narrowing provider ahead of the generic method
     * provider so its narrowed list wins and falls through to the generic list
     * when the class exposes no matching method. Each entry adapts the shared
     * {@link CompletionRequest} to the provider's own {@code generate}
     * signature; the provider keeps its own arm guard so it stays independently
     * unit-testable, the guard being a cheap confirm of the arm this switch
     * already selected on.
     */
    static List<CompletionProvider> providersFor(Behavior behavior) {
        return switch (behavior) {
            case Behavior.ClassNameBinding ignored -> List.of(
                r -> ClassNameCompletions.generate(r.vocabulary(), r.data(), r.context()));
            case Behavior.MethodNameBinding ignored -> List.of(
                r -> ExternalFieldCompletions.generate(
                    r.vocabulary(), r.data(), r.context(), r.directive(), r.pos(), r.source()),
                r -> MethodCompletions.generate(
                    r.vocabulary(), r.data(), r.context(), r.directive(), r.pos(), r.source()));
            case Behavior.CatalogTableBinding ignored -> List.of(
                r -> TableCompletions.generate(r.vocabulary(), r.data(), r.sourceIndex(), r.context()));
            case Behavior.CatalogColumnBinding ignored -> List.of(
                r -> FieldCompletions.generate(
                    r.vocabulary(), r.data(), r.sourceIndex(), r.snapshot(), r.context(),
                    r.directive(), r.source()));
            case Behavior.CatalogFkBinding ignored -> List.of(
                r -> ReferenceCompletions.generate(
                    r.vocabulary(), r.data(), r.snapshot(), r.context(), r.directive(), r.source()));
            case Behavior.ArgMappingBinding ignored -> List.of(
                r -> ArgMappingCompletions.generate(
                    r.vocabulary(), r.data(), r.context(), r.directive(), r.pos(), r.lspPos(), r.source()));
            case Behavior.ScalarTypeBinding ignored -> List.of(
                r -> ScalarTypeCompletions.generate(
                    r.vocabulary(), r.data(), r.context(), r.directive(), r.source()));
            case Behavior.NodeTypeBinding ignored -> List.of(
                r -> NodeTypeCompletions.generate(r.vocabulary(), r.data(), r.context()));
        };
    }
}
