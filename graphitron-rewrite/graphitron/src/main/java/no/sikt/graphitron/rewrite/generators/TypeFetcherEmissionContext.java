package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.CodeBlock;

import java.util.EnumSet;

/**
 * Per-class emission scratchpad for {@link TypeFetcherGenerator}. One instance lives for the
 * duration of a single {@code generateTypeSpec} call and accumulates the set of helpers any
 * emitted method body has requested. Class assembly drains the set at the end of
 * {@code generateTypeSpec} and emits the corresponding helper methods.
 *
 * <p>The carrier replaces a previous post-scan over emitted method bodies that string-grepped for
 * {@code graphitronContext(env)}. The bug class that motivated the carrier ; an emitter writes a
 * {@code graphitronContext(env)} call but the gating predicate doesn't enumerate that field
 * variant ; becomes structurally impossible: the call only exists as the return value of
 * {@link #graphitronContextCall()}, which records the dependency on the way out.
 */
final class TypeFetcherEmissionContext {

    /** Helpers a {@code *Fetchers} class may emit at assembly time. */
    enum HelperKind {
        /** {@code private static GraphitronContext graphitronContext(DataFetchingEnvironment env)}. */
        GRAPHITRON_CONTEXT
    }

    private final EnumSet<HelperKind> requested = EnumSet.noneOf(HelperKind.class);

    /**
     * Returns the literal {@code graphitronContext(env)} call expression and records that the
     * class needs the {@code graphitronContext} helper. Format-string callers should
     * interpolate the returned {@link CodeBlock} via {@code $L}.
     */
    CodeBlock graphitronContextCall() {
        requested.add(HelperKind.GRAPHITRON_CONTEXT);
        return CodeBlock.of("graphitronContext(env)");
    }

    boolean isRequested(HelperKind kind) {
        return requested.contains(kind);
    }
}
