package no.sikt.graphitron.lsp.definition;

import no.sikt.graphitron.rewrite.catalog.CompletionData;

/**
 * Typed outcome of resolving a service-half goto-definition request (a
 * {@code @service} / {@code @condition} / {@code @externalField} class or
 * method reference) against the LSP-owned source-position index.
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
 * <p>A same-arity overload collision is no longer an outcome here: rather than
 * declining, {@link Definitions#methodTarget} falls back to the never-dropped
 * name-level view and resolves to {@link Located} on a declaration adjacent to
 * the overload set.
 */
public sealed interface DefinitionTarget {

    /** A resolved declaration position to jump to. */
    record Located(CompletionData.SourceLocation location) implements DefinitionTarget {}

    /**
     * The reference is known (the catalog carries it) but no source position
     * is indexed for it: a binary-only dependency with no {@code .java}, or a
     * module whose source root the LSP is not walking. A correct no-jump, but
     * one worth signalling rather than swallowing, since the recoverable
     * "source exists but isn't on a watched root" case lands here too.
     */
    record SourceAbsent() implements DefinitionTarget {}
}
