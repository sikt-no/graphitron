package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.GraphitronSchema;

import java.util.Objects;

/**
 * Everything the capture-and-detect pass transcribes from the walked model: the coordinates the
 * classification walk registered and the backing class it resolved for each. One value rather than
 * one parameter per relation, so the {@code walk_} family can grow a grain without widening the
 * capture entry point every time, and so all of it is projected at one site.
 *
 * <p>Each component's own type carries its rationale and removal criterion. Their reified forms
 * drain on separate clocks, which is the reason the family's relations carry no foreign keys among
 * themselves.
 */
public record WalkReach(ClaimDomain domain, TypeBackingClasses backingClasses) {

    public WalkReach {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(backingClasses, "backingClasses");
    }

    /** The walked model projected onto everything the family transcribes. */
    public static WalkReach of(GraphitronSchema schema) {
        return new WalkReach(ClaimDomain.of(schema), TypeBackingClasses.of(schema));
    }
}
