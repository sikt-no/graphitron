package no.sikt.graphitron.record.field;

/**
 * A {@link ReferencePathElement} where a condition method was specified in the schema but could
 * not be resolved via reflection.
 *
 * <p>{@code qualifiedName} is the fully qualified method name as written in the schema
 * (e.g. {@code "com.example.Conditions.activeCustomers"}). The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports this as an error.
 */
public record UnresolvedConditionStep(String qualifiedName) implements ReferencePathElement {}
