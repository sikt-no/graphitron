package no.sikt.graphitron.lsp.completions;

import org.eclipse.lsp4j.CompletionItem;

import java.util.List;

/**
 * A coordinate-driven completion source over the shared {@link CompletionRequest}.
 * {@link Completions#providersFor} maps each
 * {@link no.sikt.graphitron.lsp.parsing.Behavior} arm to the ordered list of
 * providers that fire for it (each a lambda adapting the request to a value
 * provider's own {@code generate} signature), and {@link Completions} runs them
 * in that order, first non-empty result winning.
 *
 * <p>A provider returns an empty list to fall through to the next provider
 * registered for the same arm, e.g. {@code @externalField}'s narrowed method
 * list falling through to the generic method list when the class exposes no
 * matching method.
 */
@FunctionalInterface
public interface CompletionProvider {

    List<CompletionItem> complete(CompletionRequest request);
}
