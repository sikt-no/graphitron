package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The emitted-carrier producer capability: the {@link ProducerBinding} arms whose observation
 * grounds a directiveless single-record carrier payload against a producer's emitted row shape
 * ({@link ProducerBinding.DmlEmitted}, {@link ProducerBinding.ServiceEmitted},
 * {@link ProducerBinding.RoutineEmitted}). Strip the per-arm provenance and the three are one
 * consumer-facing shape; this interface is that shape, so consumers that read "is an
 * emitted-carrier binding bound to this SDL type" or "which columns correlate the data field's
 * re-fetch" ask one question instead of maintaining a hand-written disjunction over the arms
 * (the {@code activeChannel} gate in {@code FieldBuilder.transportForParent} is the
 * load-bearing consumer: a missed arm there silently binds the carrier's {@code errors} field
 * to the one transport that cannot work).
 */
public sealed interface EmittedCarrierBinding
    permits ProducerBinding.DmlEmitted, ProducerBinding.ServiceEmitted,
            ProducerBinding.RoutineEmitted {

    /** The inner {@code @table} the carrier's data field re-projects. */
    TableRef tableRef();

    /** The arrival cardinality the producer's emitted shape carries. */
    Arity arrival();

    /**
     * The read-side correlation columns: the target table's primary-key columns, uniformly on
     * every arm, because that is the one meaning the data field's hop-less
     * {@code ParentCorrelation.OnLiftedSlots} correlation consumes. Total by definition (a
     * default over {@link #tableRef()}), so
     * {@code FieldBuilder.buildPayloadCarrierBatchedTableField} reads it without forking on
     * "does this binding carry key columns". The routine arm's distinct fact, the name-matched
     * pairs whose source side step 1 projects, travels as
     * {@link ProducerBinding.RoutineEmitted#capturedPairs()} on that arm alone; it is a
     * different grain and never overloads this accessor.
     */
    default List<ColumnRef> correlationColumns() {
        return tableRef().primaryKeyColumns();
    }
}
