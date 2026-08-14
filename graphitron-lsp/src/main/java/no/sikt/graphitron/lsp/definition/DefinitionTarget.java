package no.sikt.graphitron.lsp.definition;

import org.eclipse.lsp4j.Location;

/**
 * Typed outcome of resolving a goto-definition request for a named Java
 * declaration (a {@code @service} / {@code @condition} / {@code @externalField}
 * class or method, a generated table class or column, or the declaration an SDL
 * name binds to) against the fact store's java-source family.
 *
 * <p>Replaces the former {@code CompletionData.SourceLocation.UNKNOWN}
 * sentinel, which collapsed distinct outcomes behind one empty value
 * (uri {@code ""}, line {@code 0}): source genuinely absent, source present
 * but not indexed, and overload-ambiguous. The sentinel made the recoverable
 * "not indexed yet" case indistinguishable from the correct no-jumps, so
 * the bug it caused was silent. The producer ({@link Definitions}) decides one
 * of these arms once; the consumer switches on it exhaustively rather than
 * re-testing {@code uri().isEmpty()}.
 *
 * <p>A same-arity overload collision is not an outcome here, and no longer even a
 * case to handle: the family holds every declaration under its own ordinal, so
 * {@link no.sikt.graphitron.lsp.facts.SourceDeclarations#methodLocation} picks one
 * of a same-arity pair rather than having to fall back from a key it could not form.
 */
public sealed interface DefinitionTarget {

    /**
     * A resolved declaration position to jump to, in the editor's own coordinates.
     * Converted from the parse's convention by the store reader, so nothing
     * downstream of here has a coordinate base to get wrong.
     */
    record Located(Location location) implements DefinitionTarget {}

    /**
     * The reference is known (the catalog carries it) but the java-source family
     * positions no declaration for it: a binary-only dependency with no
     * {@code .java}, or a module whose source root the dev session is not walking.
     * A correct no-jump, but one worth signalling rather than swallowing, since the
     * recoverable "source exists but isn't on a watched root" case lands here too.
     */
    record SourceAbsent() implements DefinitionTarget {}
}
