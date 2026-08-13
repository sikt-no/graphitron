package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Everything a coordinate-driven completion provider may need at one cursor,
 * resolved once by {@link Completions} and carried through the
 * {@link CompletionProvider} seam. Each registered provider (a lambda in
 * {@link Completions#providersFor}) pulls only the fields its {@code generate}
 * method takes; the record is the union of those bespoke tuples, so the seam is
 * one type rather than ten positional signatures.
 *
 * <p>{@link #store} is the arm the projections are being replaced by, and the union above is
 * mid-migration: an arm reading it takes no {@link CompletionData}, and the three projection fields
 * shrink out of this record as the remaining arms move. Empty means this session has no facts for
 * this document, for any of three reasons {@code Workspace.answering} deliberately does not
 * distinguish.
 *
 * <p>The one non-participant is {@link ArgNameCompletions}: it fires on the
 * arg-name side where {@link LspVocabulary#locateAt} yields no coordinate (hence
 * no {@code context}), so {@link Completions} calls it directly as the fallback
 * rather than through this request.
 */
public record CompletionRequest(
    LspVocabulary vocabulary,
    Optional<StoreHandle> store,
    CompletionData data,
    SourceWalker.Index sourceIndex,
    LspSchemaSnapshot snapshot,
    CompletionContext context,
    Directives.Directive directive,
    Point pos,
    Position lspPos,
    byte[] source
) {

    /**
     * Runs a store-backed provider, or offers nothing when there are no facts for this document.
     * Absence is an answer here and never an error: a document no capture has read has no census to
     * complete against, and saying so with an empty popup is what the author sees either way.
     */
    public List<CompletionItem> fromStore(Function<StoreHandle, List<CompletionItem>> query) {
        return store.map(query).orElseGet(List::of);
    }
}
