package no.sikt.graphitron.rewrite.model;

import java.lang.reflect.Constructor;

/**
 * How a payload class is constructed by the generated fetcher. Two structurally distinct shapes
 * the emitter forks on: the canonical all-fields constructor (records always present this shape;
 * hand-rolled POJOs may too), and the Java-bean shape (no-arg constructor plus per-SDL-field
 * setter methods). The shape is resolved once at classify time and carried on the assembly
 * carriers ({@link ErrorChannel}, {@link ResultAssembly}, {@link PayloadAssembly}); each emit
 * site dispatches on the arm directly, with no per-instance branching.
 *
 * <p>Variant identity tracks construction shape, per the rule applied four times already (R61 /
 * R70 / R71 / R74): two structurally distinct ways of building the same artifact are modelled as
 * a sealed sub-taxonomy where the variant identifier carries the shape, not a flag or a nullable
 * component. The all-fields-ctor path and the mutable-bean path have different mechanical
 * contracts ({@code new Payload(...)} vs. {@code var p = new Payload(); p.setX(...); ... return
 * p;}), different slot-identification rules (parameter index vs. setter method by SDL field
 * name), and different ambiguity modes.
 *
 * <p>The single-permit phase 1 shape declares only {@link AllFieldsCtor}; phase 2 adds
 * {@code MutableBean} as the second permit and the predicate that admits it. The seal is the
 * value-add even with one permit today: it carries the construction contract explicitly (rather
 * than implicitly inside {@code findCanonicalCtor}'s {@code Constructor<?>} return) and gives
 * {@code javac} the lever to enforce exhaustiveness when the second arm lands.
 */
public sealed interface PayloadConstructionShape
        permits PayloadConstructionShape.AllFieldsCtor {

    /**
     * Canonical all-fields constructor; today's contract. Records always present this shape.
     * Hand-rolled POJOs match when their declared constructor list disambiguates to a single
     * canonical (all-fields) ctor (typically by parameter count vs. SDL field count).
     *
     * @param ctor the canonical constructor, with parameters aligned positionally to SDL field
     *             declaration order
     */
    record AllFieldsCtor(Constructor<?> ctor) implements PayloadConstructionShape {
        public AllFieldsCtor {
            if (ctor == null) {
                throw new IllegalArgumentException(
                    "PayloadConstructionShape.AllFieldsCtor: ctor must be non-null");
            }
        }
    }
}
