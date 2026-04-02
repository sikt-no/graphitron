package no.sikt.graphitron.record.field;

/**
 * A {@link ReferencePathElement} where both a key name and a condition method were specified in
 * the schema, but neither could be resolved.
 *
 * <p>{@code keyName} is the SQL name of the foreign key constant as written in the schema.
 * {@code conditionName} is the fully qualified condition method name.
 *
 * <p>The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports both failures as
 * separate errors — one for the unresolved key and one for the unresolved condition.
 */
public record UnresolvedKeyAndConditionStep(
    String keyName,
    String conditionName
) implements ReferencePathElement {}
