package no.sikt.graphitron.rewrite.model;

import java.util.Optional;

/**
 * A multi-table polymorphic root whose ordering is lowered onto its {@code UNION ALL} branches:
 * {@code QueryInterfaceField} / {@code QueryUnionField}. Sibling capability to
 * {@link SqlGeneratingField} on the ordering axis, as {@link ParticipantFilterField} is on the
 * condition axis; consumers that read or count ordering surfaces pattern-match on this interface
 * instead of enumerating the two leaf records.
 *
 * <p>Deliberately not implemented by the child polymorphic leaves, which lower no ordering.
 */
public interface PolymorphicOrderingField {

    /**
     * The field's ordering, or empty when none applies: a single-valued read, where the ordering
     * resolver returns nothing to lower.
     */
    Optional<PolymorphicOrdering> ordering();
}
