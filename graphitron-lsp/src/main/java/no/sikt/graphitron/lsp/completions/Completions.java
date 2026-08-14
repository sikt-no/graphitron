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
        Workspace workspace, String uri, Directives.Directive directive, Point pos, Position lspPos,
        byte[] source
    ) {
        var vocab = workspace.vocabulary();
        // Resolved before the store read rather than inside it: this is tree work over the buffer,
        // it reads no rows, and keeping it out holds the read transaction to the queries that need it.
        var locationOpt = vocab.locateAt(directive, pos, source);
        // One read transaction around the whole answer, the fallback included. A popup assembled
        // from two snapshots could offer a class from before a capture and its methods from after.
        return workspace.answering(uri, store -> {
            if (locationOpt.isPresent()) {
                var context = CompletionContext.from(locationOpt.get(), source);
                var behaviorOpt = vocab.behaviorAt(context.coordinate());
                if (behaviorOpt.isPresent()) {
                    var request = new CompletionRequest(
                        vocab, store, workspace.snapshot(), context,
                        directive, pos, lspPos, source);
                    for (var provider : providersFor(behaviorOpt.get())) {
                        var items = provider.complete(request);
                        if (!items.isEmpty()) return items;
                    }
                }
            }
            // Arg-name fallback: fires both when locateAt is empty (cursor on the
            // arg-name side or whitespace) and when locateAt produced no value
            // matches above. Computes its own range independent of any coordinate.
            return store.map(s -> ArgNameCompletions.generate(vocab, s, directive, pos, lspPos, source))
                .orElseGet(List::of);
        });
    }

    /**
     * The ordered providers for a behavior arm. Exhaustive over the sealed
     * {@link Behavior}. Each entry adapts the shared {@link CompletionRequest} to the provider's own
     * {@code generate} signature; the provider keeps its own arm guard so it stays independently
     * unit-testable, the guard being a cheap confirm of the arm this switch
     * already selected on.
     *
     * <p>Every arm holds one provider today. {@link Behavior.MethodNameBinding} used to hold two,
     * the {@code @externalField}-narrowing one ahead of the generic method list so its narrowed set
     * won and fell through when the class exposed no match. Both read the same census, so the
     * ordering was carrying a rule that belonged inside the arm; the list is still ordered and the
     * first non-empty result still wins, because an arm may yet have two genuinely different
     * sources to try.
     */
    static List<CompletionProvider> providersFor(Behavior behavior) {
        return switch (behavior) {
            case Behavior.ClassNameBinding ignored -> List.of(
                r -> r.fromStore(s -> ClassNameCompletions.generate(r.vocabulary(), s, r.context())));
            case Behavior.MethodNameBinding ignored -> List.of(
                r -> r.fromStore(s -> MethodCompletions.generate(
                    r.vocabulary(), s, r.context(), r.directive(), r.pos(), r.source())));
            case Behavior.CatalogTableBinding ignored -> List.of(
                r -> r.fromStore(s -> TableCompletions.generate(r.vocabulary(), s, r.context())));
            case Behavior.CatalogColumnBinding ignored -> List.of(
                r -> r.fromStore(s -> FieldCompletions.generate(
                    r.vocabulary(), s, r.snapshot(), r.context(), r.directive(), r.source())));
            case Behavior.CatalogFkBinding ignored -> List.of(
                r -> r.fromStore(s -> ReferenceCompletions.generate(
                    r.vocabulary(), s, r.context(), r.directive(), r.source())));
            case Behavior.ArgMappingBinding ignored -> List.of(
                r -> r.fromStore(s -> ArgMappingCompletions.generate(
                    r.vocabulary(), s, r.context(), r.directive(), r.pos(), r.lspPos(), r.source())));
            case Behavior.ScalarTypeBinding ignored -> List.of(
                r -> r.fromStore(s -> ScalarTypeCompletions.generate(
                    r.vocabulary(), s, r.context(), r.directive(), r.source())));
            case Behavior.NodeTypeBinding ignored -> List.of(
                r -> r.fromStore(s -> NodeTypeCompletions.generate(r.vocabulary(), s, r.context())));
        };
    }
}
