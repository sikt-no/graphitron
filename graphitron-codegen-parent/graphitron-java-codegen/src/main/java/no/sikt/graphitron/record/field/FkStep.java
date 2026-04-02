package no.sikt.graphitron.record.field;

import org.jooq.ForeignKey;

/**
 * A {@link ReferencePathElement} where a jOOQ {@link ForeignKey} was successfully resolved.
 *
 * <p>{@code key} is the resolved jOOQ FK instance, used at code-generation time to emit
 * {@code .onKey(key)} join clauses. Use {@code key.getName()} to recover the FK constant name.
 */
public record FkStep(ForeignKey<?, ?> key) implements ReferencePathElement {}
