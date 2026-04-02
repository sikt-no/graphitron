package no.sikt.graphitron.record.field;

/**
 * A {@link ReferencePathElement} where a condition method was successfully resolved and no
 * jOOQ FK is involved.
 *
 * <p>Used for lift conditions on {@code @service} and {@code @computed} fields, where the
 * condition method reconnects the result back to the parent table without a FK join.
 *
 * <p>{@code condition} is the resolved condition method; all fields on {@link MethodRef} are
 * guaranteed non-null.
 */
public record ConditionOnlyStep(MethodRef condition) implements ReferencePathElement {}
