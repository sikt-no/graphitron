package no.sikt.graphitron.rewrite.model;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * How a payload class is constructed by the generated fetcher. Two structurally distinct shapes
 * the emitter forks on: the canonical all-fields constructor (records always present this shape;
 * hand-rolled POJOs may too), and the Java-bean shape (no-arg constructor plus per-SDL-field
 * setter methods). The shape is resolved once at classify time and carried on the assembly
 * carrier ({@link ErrorChannel}); each emit site dispatches on the arm directly, with no
 * per-instance branching.
 *
 * <p>Variant identity tracks construction shape, per the rule applied repeatedly elsewhere in
 * the model: two structurally distinct ways of building the same artifact are modelled as
 * a sealed sub-taxonomy where the variant identifier carries the shape, not a flag or a nullable
 * component. The all-fields-ctor path and the mutable-bean path have different mechanical
 * contracts ({@code new Payload(...)} vs. {@code var p = new Payload(); p.setX(...); ... return
 * p;}), different slot-identification rules (parameter index vs. setter method by SDL field
 * name), and different ambiguity modes.
 *
 * <p>The predicates run in order: {@link AllFieldsCtor} first, then {@link MutableBean}. When
 * both match {@code AllFieldsCtor} wins (canonical-over-bridge precedence: records always
 * present the all-fields ctor; the setter shape is a legacy bridge from
 * {@code graphitron-codegen-parent}). Both shapes yield equivalent payload instances; there's
 * no construction drift to surface. Consumers who want the setter shape exclusively drop the
 * all-fields ctor from their class.
 *
 * <p><b>{@code @field(name:)} on payload fields.</b> A setter or constructor parameter name
 * matches the {@code @field(name:)} value when the directive is present, the SDL field name
 * otherwise, mirroring the read side. The directive is load-bearing at different points on each
 * arm: on the ctor arm it names the errors-slot parameter only (arity selection is
 * name-independent, so data-field directives are inert there), while on the bean arm it also
 * participates in the shape's existence check (a setter must exist for every SDL field under the
 * resolved base, so a data-field directive naming a member the class does not expose rejects).
 * A present-but-blank {@code @field(name: "")} on any payload field rejects the channel.
 */
public sealed interface PayloadConstructionShape
        permits PayloadConstructionShape.AllFieldsCtor, PayloadConstructionShape.MutableBean {

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

    /**
     * Java-bean shape: a public no-arg constructor plus a Java-bean setter (e.g. {@code setX} for
     * SDL field {@code x}, {@code xRating} → {@code setXRating}) for every SDL field on the
     * payload type, with parameter assignability matching the SDL-derived Java type (or
     * {@code Optional<T>} of it).
     *
     * <p>Predicate operates on SDL field name as the join key, not constructor parameter order.
     * SDL field order is irrelevant on this arm because emit walks the SDL field list and looks
     * up each binding by name. This means the setter shape is robust against SDL field
     * reordering in a way the all-fields-ctor shape isn't.
     *
     * @param noArgCtor the public no-arg constructor
     * @param bindings  one {@link SetterBinding} per SDL field, in SDL declaration order
     */
    record MutableBean(
        Constructor<?> noArgCtor,
        List<SetterBinding> bindings
    ) implements PayloadConstructionShape {
        public MutableBean {
            if (noArgCtor == null) {
                throw new IllegalArgumentException(
                    "PayloadConstructionShape.MutableBean: noArgCtor must be non-null");
            }
            if (noArgCtor.getParameterCount() != 0) {
                throw new IllegalArgumentException(
                    "PayloadConstructionShape.MutableBean: noArgCtor must have zero parameters; got "
                        + noArgCtor.getParameterCount());
            }
            bindings = List.copyOf(bindings);
        }
    }

    /**
     * One per-SDL-field binding on the setter shape: the SDL field name, the resolved Java-bean
     * setter, and a flag for whether the setter accepts {@code Optional<T>} (lifted verbatim
     * from the legacy {@code ReflectionHelpers.setterAcceptsOptional} rule).
     *
     * @param sdlFieldName    the wire identity, e.g. {@code "rating"} (kept even when the setter
     *                        is resolved from a divergent {@code @field(name:)} base, so
     *                        diagnostics quote the SDL name)
     * @param setter          e.g. {@code setRating(Integer)}; resolved by Java-bean name match on
     *                        the {@code @field(name:)} base when present, the SDL field name
     *                        otherwise
     * @param acceptsOptional true when the setter's parameter erasure is {@code Optional<T>}
     */
    record SetterBinding(
        String sdlFieldName,
        Method setter,
        boolean acceptsOptional
    ) {
        public SetterBinding {
            if (sdlFieldName == null || sdlFieldName.isEmpty()) {
                throw new IllegalArgumentException(
                    "SetterBinding: sdlFieldName must be non-empty");
            }
            if (setter == null) {
                throw new IllegalArgumentException(
                    "SetterBinding: setter must be non-null (sdlFieldName=" + sdlFieldName + ")");
            }
        }
    }
}
