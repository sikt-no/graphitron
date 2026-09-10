package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.NodeIdDecodeCoordinate;
import no.sikt.graphitron.rewrite.model.ParticipantRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Threaded through {@link BuildContext#classifyInputField} to carry the structural facts a
 * classifier branch needs but a single recursive descent cannot recover from the local field
 * alone: the set of currently-expanding nested input types (for circularity detection), the
 * inherited {@code override} flag from the enclosing arg- or field-level {@code @condition}
 * cascade, the participant the consuming field is currently standing on, and the use site the
 * descent started from.
 *
 * <p>The classifier itself does not read {@link #enclosingOverride} to decide a variant
 * (column-miss always lifts to {@link no.sikt.graphitron.rewrite.model.InputField.UnboundField} regardless of cascade); the
 * record carries it for the future-growth axes the spec calls out so adding the
 * mutation-context lift or a nested-input cascade walker arm does not touch every call site.
 *
 * <p>{@link #participant} is non-{@code null} only under the per-participant lowering of a
 * multi-table interface / union field, where the same input surface is classified once per
 * participant with only the resolving table varying. It travels as the model's own
 * {@link ParticipantRef.TableBound} rather than a bare type-name String: the type name and the
 * table it rides beside are one fact, and two slots could disagree. The {@code @nodeId} decode
 * rail reads it to pick a per-participant {@code @referenceFor} route; every other classifier
 * branch ignores it.
 *
 * <p>{@link #useSite} is what makes a coordinate under this descent nameable at the grain the
 * decode-coverage rule keys on. One input type is consumed by however many arguments name it, and
 * a {@code @nodeId} under it is one authored instruction and as many coordinates: without the use
 * site a classifier branch can say which input field it is looking at and not which consumer is
 * asking, which is the difference between covering one use site's install and covering all of
 * them. Null where the caller genuinely stands at no use site.
 *
 * <p>Use {@link #root()} for the top-level entry and {@link #expanding(String)} for the
 * recursive descent through {@code NestingField}, pairing it with {@link #descending} so the use
 * site grows the step the descent just took. The {@code with*}-style helpers return a
 * new context so the record stays effectively immutable.
 */
public record ClassifyContext(Set<String> expandingTypes, boolean enclosingOverride,
                              ParticipantRef.TableBound participant, UseSite useSite) {

    /**
     * The consuming coordinate an input-surface descent hangs under, and how far down it has got.
     * The store states the same value relationally, as {@code intent_input_occurrence_path}'s three
     * root columns plus one ordinal-keyed row per step in its step child, which is why this carries
     * components rather than the serialized path that relation keys on: two spellings of one value
     * agree until one changes, and here a disagreement reads as a dropped instruction.
     *
     * @param rootTypeName     the object type declaring the consuming field
     * @param rootFieldName    the consuming field
     * @param rootArgumentName the argument whose input surface this descent is inside
     * @param descent          the input-field steps taken so far, outermost first
     */
    public record UseSite(String rootTypeName, String rootFieldName, String rootArgumentName,
                          List<NodeIdDecodeCoordinate.Step> descent) {

        public UseSite {
            descent = List.copyOf(descent);
        }

        /** The use site at the argument itself, before any input field is entered. */
        public static UseSite of(String rootTypeName, String rootFieldName, String rootArgumentName) {
            return new UseSite(rootTypeName, rootFieldName, rootArgumentName, List.of());
        }

        /** This use site one step deeper, having entered {@code fieldName} on {@code containerTypeName}. */
        public UseSite descending(String containerTypeName, String fieldName) {
            var steps = new ArrayList<>(descent);
            steps.add(new NodeIdDecodeCoordinate.Step(containerTypeName, fieldName));
            return new UseSite(rootTypeName, rootFieldName, rootArgumentName, steps);
        }

        /**
         * The coordinate of the input field this use site has already descended into. Defined only
         * where {@link #descent} is non-empty; a use site standing at the argument itself names an
         * argument coordinate rather than an input-field one.
         */
        public NodeIdDecodeCoordinate.InputField here() {
            return new NodeIdDecodeCoordinate.InputField(rootTypeName, rootFieldName,
                rootArgumentName, descent);
        }

        /** The coordinate of the input field {@code fieldName} declared on {@code containerTypeName}. */
        public NodeIdDecodeCoordinate.InputField at(String containerTypeName, String fieldName) {
            return new NodeIdDecodeCoordinate.InputField(rootTypeName, rootFieldName,
                rootArgumentName, descending(containerTypeName, fieldName).descent());
        }
    }

    public ClassifyContext {
        expandingTypes = Set.copyOf(expandingTypes);
    }

    public static ClassifyContext root() {
        return new ClassifyContext(Set.of(), false, null, null);
    }

    /** {@link #root()} standing at {@code useSite}. */
    public static ClassifyContext under(UseSite useSite) {
        return new ClassifyContext(Set.of(), false, null, useSite);
    }

    public static ClassifyContext withEnclosingOverride(boolean enclosingOverride) {
        return new ClassifyContext(Set.of(), enclosingOverride, null, null);
    }

    /** Root context for one participant of a multi-table interface / union consumer. */
    public static ClassifyContext forParticipant(boolean enclosingOverride,
                                                 ParticipantRef.TableBound participant,
                                                 UseSite useSite) {
        return new ClassifyContext(Set.of(), enclosingOverride, participant, useSite);
    }

    public ClassifyContext expanding(String typeName) {
        var s = new LinkedHashSet<>(expandingTypes);
        s.add(typeName);
        return new ClassifyContext(s, enclosingOverride, participant, useSite);
    }

    /**
     * This context having descended into {@code fieldName} on {@code containerTypeName}. Paired
     * with {@link #expanding(String)} at every recursion into a nested input type, so the use site
     * and the circularity guard advance together.
     */
    public ClassifyContext descending(String containerTypeName, String fieldName) {
        return useSite == null
            ? this
            : new ClassifyContext(expandingTypes, enclosingOverride, participant,
                useSite.descending(containerTypeName, fieldName));
    }

    /**
     * The coordinate of the input field {@code fieldName} on {@code containerTypeName} under this
     * descent, or null where this context stands at no use site.
     */
    public NodeIdDecodeCoordinate.InputField coordinateOf(String containerTypeName, String fieldName) {
        return useSite == null ? null : useSite.at(containerTypeName, fieldName);
    }

    public ClassifyContext withOverride(boolean enclosingOverride) {
        return new ClassifyContext(expandingTypes, enclosingOverride, participant, useSite);
    }

    public boolean isExpanding(String typeName) {
        return expandingTypes.contains(typeName);
    }
}
