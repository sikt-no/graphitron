package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The outcome of resolving a field-level {@code @condition} directive.
 *
 * <p>A field-level condition adds a {@code WHERE} (or {@code AND}) clause to the query generated
 * for that field, in contrast with reference-path conditions (see {@link JoinStep.FkJoin#whereFilter()} and
 * {@link JoinStep.ConditionJoin}) which affect how tables are joined.
 *
 * <p>The {@code override} flag (on {@link ResolvedFieldCondition}) indicates that this condition
 * should replace any inherited condition rather than combine with it. {@code contextArgs} lists
 * the names of context arguments whose values are threaded through to the condition method.
 *
 * <p>When a {@code @condition} method cannot be resolved via reflection the containing field is
 * classified as
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} at build time.
 *
 * <ul>
 *   <li>{@link NoFieldCondition} — no {@code @condition} directive is present on the field
 *   <li>{@link ResolvedFieldCondition} — the condition method was found via reflection
 * </ul>
 */
public sealed interface FieldConditionRef
    permits FieldConditionRef.NoFieldCondition,
            FieldConditionRef.ResolvedFieldCondition {

    /** No {@code @condition} directive is present on the field. */
    record NoFieldCondition() implements FieldConditionRef {}

    /** The condition method was successfully resolved via reflection. */
    record ResolvedFieldCondition(
        MethodRef method,
        boolean override,
        List<String> contextArgs
    ) implements FieldConditionRef {}
}
