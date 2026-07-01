package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import org.eclipse.lsp4j.Position;

/**
 * Everything a coordinate-driven completion provider may need at one cursor,
 * resolved once by {@link Completions} and carried through the
 * {@link CompletionProvider} seam. Each registered provider (a lambda in
 * {@link Completions#providersFor}) pulls only the fields its {@code generate}
 * method takes; the record is the union of those bespoke tuples, so the seam is
 * one type rather than ten positional signatures.
 *
 * <p>The one non-participant is {@link ArgNameCompletions}: it fires on the
 * arg-name side where {@link LspVocabulary#locateAt} yields no coordinate (hence
 * no {@code context}), so {@link Completions} calls it directly as the fallback
 * rather than through this request.
 */
public record CompletionRequest(
    LspVocabulary vocabulary,
    CompletionData data,
    SourceWalker.Index sourceIndex,
    LspSchemaSnapshot snapshot,
    CompletionContext context,
    Directives.Directive directive,
    Point pos,
    Position lspPos,
    byte[] source
) {
}
