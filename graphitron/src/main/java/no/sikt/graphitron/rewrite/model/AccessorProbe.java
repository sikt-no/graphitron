package no.sikt.graphitron.rewrite.model;

import java.lang.reflect.Member;
import java.lang.reflect.Type;

/**
 * Outcome of the discovery-direction accessor probe
 * ({@link no.sikt.graphitron.rewrite.ClassAccessorResolver#probe}): "which member does this SDL
 * field read on the parent's backing class, given the field's argument shape but <em>no</em>
 * expected return type?". Consumed by the R96 binding walk
 * ({@link no.sikt.graphitron.rewrite.RecordBindingResolver}) to ground child backing classes and to
 * name the resolved accessor on {@link ProducerBinding.ParentAccessor}, and by the R329 carrier
 * discrimination as a pure presence probe.
 *
 * <p>A distinct sub-taxonomy from {@link AccessorResolution}, not a reuse of it: the probe has no
 * expected-return input and no arm-kind discrimination for the walk to switch on (the walk peels the
 * generic return type itself and does not care whether the member was a bare accessor, a getter, or a
 * field), so reusing {@link AccessorResolution} would carry arms the consumer cannot receive.
 */
public sealed interface AccessorProbe permits AccessorProbe.Grounded, AccessorProbe.NoMatch {

    /**
     * The probe grounded on a member. {@code member} is the resolved {@link java.lang.reflect.Method}
     * or {@link java.lang.reflect.Field}; {@code memberName} is its name (the value the walk carries
     * onto {@link ProducerBinding.ParentAccessor#accessorName}); {@code genericReturnType} is the
     * member's generic return / field type (the walk peels its container to find the child element
     * class).
     */
    record Grounded(Member member, String memberName, Type genericReturnType) implements AccessorProbe {}

    /**
     * No member matched the SDL field name under the unified rules. {@code reason} names the closest
     * gated near-miss when one exists (a member that name-matched but failed the arity,
     * boolean-{@code is}, or field-fallback-with-arguments gate), so a sole-producer type whose only
     * grounding was removed by a walk tightening surfaces an accessor-gate diagnostic rather than a
     * generic no-producer cascade. {@code gatedNearMiss} is {@code true} exactly when such a gated
     * member existed; {@code false} for a plain name-absence, which the walk leaves on the ordinary
     * no-producer path rather than the accessor-gate reason ledger.
     */
    record NoMatch(String reason, boolean gatedNearMiss) implements AccessorProbe {}
}
