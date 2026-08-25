package no.sikt.graphitron.lsp.references;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.Location;

import java.util.List;
import java.util.Optional;

/**
 * {@code textDocument/references}: the sites in the schema that use whatever the cursor is on.
 *
 * <p>The reverse of the definition surface, and the reverse in a specific sense worth stating
 * because the phrase "reverse of go-to-definition" admits a second reading. Definition leaves SDL:
 * from {@code @table(name: "film")} it lands in the generated {@code FilmTable}. The trip back from
 * a Java buffer into SDL would be a different feature with a different client registration, and is
 * not this one. This surface keeps the cursor where it is and fans out sideways: one SDL name, and
 * every other SDL site that uses it. So it returns a list where definition returns at most one, and
 * an empty list means nothing uses the name rather than that the name was not understood.
 *
 * <p>The composition point for the arms, one per cursor shape, in the order a cursor can satisfy
 * them. {@link TypeReferences} answers for a type name, whether the cursor is on the declaration or
 * on a use of it. The arms key off disjoint syntax, so the chain is an {@code or} over the first
 * that has something to say rather than a classification up front, which is the shape the
 * definition handler already has.
 */
public final class References {

    private References() {}

    /**
     * Every site using the name at {@code pos}. A session with no store answers with an empty list:
     * unlike the buffer-first half of goto-definition, no arm here can answer from open buffers
     * alone, since the population is workspace-wide and the buffers are whatever the author happens
     * to have open.
     *
     * @param includeDeclaration whether declaration sites of the named thing join the list, which
     *                           the editor states per request
     */
    public static List<Location> compute(
        LspVocabulary vocabulary, FileSnapshot file, Optional<StoreHandle> store, Point pos,
        boolean includeDeclaration
    ) {
        return store
            .map(handle -> compute(vocabulary, file, handle, pos, includeDeclaration))
            .orElseGet(List::of);
    }

    public static List<Location> compute(
        LspVocabulary vocabulary, FileSnapshot file, StoreHandle store, Point pos,
        boolean includeDeclaration
    ) {
        return TypeReferences.compute(file, store, pos, includeDeclaration);
    }
}
