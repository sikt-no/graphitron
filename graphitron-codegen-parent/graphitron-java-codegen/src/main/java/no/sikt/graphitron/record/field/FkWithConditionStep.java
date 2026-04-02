package no.sikt.graphitron.record.field;

import org.jooq.ForeignKey;

/**
 * A {@link ReferencePathElement} where both a jOOQ {@link ForeignKey} and a condition method
 * were successfully resolved.
 *
 * <p>{@code key} is the resolved jOOQ FK instance (see {@link FkStep}).
 * {@code condition} is the resolved condition method (see {@link ConditionOnlyStep}).
 */
public record FkWithConditionStep(
    ForeignKey<?, ?> key,
    MethodRef condition
) implements ReferencePathElement {}
