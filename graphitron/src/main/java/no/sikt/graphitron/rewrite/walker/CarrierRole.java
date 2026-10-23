package no.sikt.graphitron.rewrite.walker;

/**
 * What kind of thing a column-bearing DML input field points at, which is what decides how its
 * columns partition. Sealed on the three answers the classifier can give, so each walker's
 * partition switch is exhaustive and a fourth carrier shape breaks it at compile time. Shared by
 * {@link UpdateRowsWalker} and {@link DeleteRowsWalker} so both classify a carrier the same way;
 * how a role's columns sit against the matched key is {@link KeyMembership}'s answer, and what that
 * answer means is each walker's own verdict.
 *
 * <p>Replaces a {@code boolean selfReference} flag. The flag answered two of the three cases and
 * left the third (own columns versus a cross-table pointer) to be re-derived, which mattered once
 * the two stopped sharing a disposition. Nullability is not an arm's component: the rule for what
 * an explicit null means is uniform over the three, so it rides on the walker's contribution
 * instead.
 */
sealed interface CarrierRole {

    /** The field carries the row's <em>own</em> columns: a plain {@code @field}, or a same-table
     *  composite {@code @nodeId} with no {@code @reference}. These columns are this row's identity
     *  where they are in the key, so on UPDATE a straddle means moving the row and rejects. The
     *  exception is a carrier covering the whole matched key plus more
     *  ({@link KeyMembership.CoversKeyPlus}): the matched key alone identifies the row, and the
     *  carrier's other columns are dropped rather than filtered on or written. */
    record OwnColumns() implements CarrierRole {}

    /** A self-FK {@code @nodeId @reference}: the lifted columns are a pointer to a sibling row of
     *  the same table, never this row's identity. On UPDATE they route wholly to SET regardless of
     *  key membership and never count toward key coverage. Nullability is not consulted: an omitted
     *  or null self-FK simply drops out under PATCH semantics, writing no half-key, because the
     *  whole foreign key is on the SET side. */
    record SelfFk() implements CarrierRole {}

    /** A cross-table FK {@code @nodeId} reference: a pointer at another table's row, whose lifted
     *  child columns can legitimately include this row's own identity. On UPDATE it partitions per
     *  column, whatever its spelling; a nullable one is measured against the settled WHERE partition
     *  and refused only where it is the sole contributor to a matched-key column. */
    record CrossTableFk() implements CarrierRole {}
}
