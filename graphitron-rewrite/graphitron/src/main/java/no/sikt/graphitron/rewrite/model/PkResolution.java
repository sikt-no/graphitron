package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Optional;

/**
 * Model-facing per-field projection produced by the DELETE carrier walk and carried on
 * {@link ChildField.SingleRecordTableFieldFromReturning} to the emitter (R156).
 *
 * <p>Two arms — one for each emission case the emitter has to handle. By construction, the only
 * producer is {@code BuildContext.classifyDeleteTableProjection}, which rejects the carrier
 * before constructing any {@code PkResolution} when any element-type field classifies into a
 * rejection arm of the builder-internal {@code PerFieldOutcome} (the four-arm classifier
 * outcome). The emitter's sealed switch on {@code PkResolution} is therefore exhaustive over its
 * two arms with no "unreachable arm" defensive default — the type system carries the certainty
 * that the rejection arms cannot appear here.
 *
 * <p>The projection list rides on the per-field
 * {@link ChildField.SingleRecordTableFieldFromReturning} permit — not on
 * {@link SingleRecordCarrierShape} and not on {@link DataElement.Table} — because it is data only
 * the new DELETE-specific child carrier consumes. Placing it on the shape or on the element
 * record would force every non-DELETE consumer of those sealed types to ignore a slot it has no
 * story for, exactly the "narrow component types over broad interfaces" smell.
 */
public sealed interface PkResolution {

    /** The SDL field name on the element type. */
    String fieldName();

    /**
     * Field resolves to a PK column set on the input {@code @table}; the resolver reads the
     * column(s) off the source {@code Record}.
     *
     * <p>Single-column PK: {@code columns} has size 1, {@code encode} is empty. Composite PK or
     * {@code @nodeId}-over-PK (including the SDL {@code id} alias on a {@code @node}-backed
     * element type whose backing PK is the input {@code @table}'s PK): {@code columns} carries
     * the PK column set in declaration order, {@code encode} carries the NodeId encoder.
     *
     * <p>The classifier produces this arm for plain {@code ColumnField} over PK, for
     * {@code CompositeColumnField} via {@code @nodeId} over PK, and for the SDL {@code id}-alias
     * case. The three SDL shapes share the same emitter dispatch (read column(s), optionally
     * encode), so they share one arm. A future emitter that genuinely needs to split (e.g. a
     * faster path for the single-column no-encode case) lifts at that point.
     */
    record PkRead(String fieldName, List<ColumnRef> columns, Optional<HelperRef.Encode> encode)
            implements PkResolution {
        public PkRead {
            columns = List.copyOf(columns);
        }
    }

    /** Field maps to a non-PK column. Nullable in SDL; emitter emits a constant-null fetcher. */
    record NonPkNullable(String fieldName) implements PkResolution {}
}
