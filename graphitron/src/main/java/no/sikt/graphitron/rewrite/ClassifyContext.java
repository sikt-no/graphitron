package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ParticipantRef;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Threaded through {@link BuildContext#classifyInputField} to carry the structural facts a
 * classifier branch needs but a single recursive descent cannot recover from the local field
 * alone: the set of currently-expanding nested input types (for circularity detection), the
 * inherited {@code override} flag from the enclosing arg- or field-level {@code @condition}
 * cascade, and the participant the consuming field is currently standing on.
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
 * <p>Use {@link #root()} for the top-level entry and {@link #expanding(String)} for the
 * recursive descent through {@code NestingField}. The {@code with*}-style helpers return a
 * new context so the record stays effectively immutable.
 */
public record ClassifyContext(Set<String> expandingTypes, boolean enclosingOverride,
                              ParticipantRef.TableBound participant) {

    public ClassifyContext {
        expandingTypes = Set.copyOf(expandingTypes);
    }

    public static ClassifyContext root() {
        return new ClassifyContext(Set.of(), false, null);
    }

    public static ClassifyContext withEnclosingOverride(boolean enclosingOverride) {
        return new ClassifyContext(Set.of(), enclosingOverride, null);
    }

    /** Root context for one participant of a multi-table interface / union consumer. */
    public static ClassifyContext forParticipant(boolean enclosingOverride,
                                                 ParticipantRef.TableBound participant) {
        return new ClassifyContext(Set.of(), enclosingOverride, participant);
    }

    public ClassifyContext expanding(String typeName) {
        var s = new LinkedHashSet<>(expandingTypes);
        s.add(typeName);
        return new ClassifyContext(s, enclosingOverride, participant);
    }

    public ClassifyContext withOverride(boolean enclosingOverride) {
        return new ClassifyContext(expandingTypes, enclosingOverride, participant);
    }

    public boolean isExpanding(String typeName) {
        return expandingTypes.contains(typeName);
    }
}
