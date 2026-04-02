package no.sikt.graphitron.record.field;

import java.util.List;

/**
 * The outcome of resolving a field-level {@code @condition} directive.
 *
 * <p>A field-level condition adds a {@code WHERE} (or {@code AND}) clause to the query generated
 * for that field, in contrast with reference-path conditions (see {@link ReferencePathElement})
 * which affect how tables are joined.
 *
 * <p>The {@code override} flag indicates that this condition should replace any inherited condition
 * rather than combine with it. {@code contextArgs} lists the names of context arguments whose
 * values are threaded through to the condition method.
 *
 * <ul>
 *   <li>{@link ResolvedFieldCondition} — the condition method was found via reflection
 *   <li>{@link UnresolvedFieldCondition} — the condition method could not be resolved;
 *       the {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error
 * </ul>
 */
public sealed interface FieldConditionStep
    permits FieldConditionStep.ResolvedFieldCondition, FieldConditionStep.UnresolvedFieldCondition {

    boolean override();
    List<String> contextArgs();

    /** The condition method was successfully resolved via reflection. */
    record ResolvedFieldCondition(
        MethodRef method,
        boolean override,
        List<String> contextArgs
    ) implements FieldConditionStep {}

    /** The condition method could not be resolved. {@code qualifiedName} is the raw value from the directive. */
    record UnresolvedFieldCondition(
        String qualifiedName,
        boolean override,
        List<String> contextArgs
    ) implements FieldConditionStep {}
}
