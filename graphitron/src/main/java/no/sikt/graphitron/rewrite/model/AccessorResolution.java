package no.sikt.graphitron.rewrite.model;

import java.lang.reflect.Method;

/**
 * Outcome of resolving a class-backed SDL field's accessor against the parent's backing
 * Java class. Produced at classify time by {@link no.sikt.graphitron.rewrite.ClassAccessorResolver};
 * consumed by {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} (to surface
 * {@link Rejected} as a {@code ValidationError}) and by the fetcher emitter (to read the
 * pre-resolved {@link Method} handle on each {@link Resolved} arm).
 *
 * <p>Two-layer sealing where the inner layer carries per-arm data: the outer
 * {@code AccessorResolution} discriminates resolved-vs-rejected; {@link Resolved} discriminates
 * which kind of member the resolver matched. Each {@link Resolved} arm carries exactly the data it
 * uses, so a future arm (a record's own getter, should one be read) extends {@link Resolved}'s permit
 * list and the emitter's exhaustive switch breaks at compile time rather than silently falling
 * through.
 */
public sealed interface AccessorResolution permits AccessorResolution.Resolved, AccessorResolution.Rejected {

    /**
     * The accessor was resolved against the backing class. Sub-arms identify which kind of member
     * matched: a getter on a class that is no record, or a record's component.
     */
    sealed interface Resolved extends AccessorResolution permits GetterPrefixed, BareName {}

    /** Bean-style {@code get<Name>} or {@code is<Name>} accessor. */
    record GetterPrefixed(Method method) implements Resolved {}

    /** Bare-name accessor: a Java record's component. */
    record BareName(Method method) implements Resolved {}

    /**
     * Resolution failed: no member on the backing class matched the SDL field's
     * required name, parameter shape, and return type. The {@code reason} is the actionable
     * diagnostic body — the validator wraps it with the parent + field context and an
     * {@code @field(name:)} override hint.
     */
    record Rejected(String reason) implements AccessorResolution {}
}
