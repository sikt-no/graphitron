package no.sikt.graphitron.rewrite.classifieddsl;

/**
 * One step of a field's {@code producer} pipeline (R281). A step names a row-source or a
 * query that produces the field's value; the empty pipeline (no steps) means the field inlines
 * into the existing query and correlates, with no new execution.
 *
 * <p>This is the throwaway-adapter's Java mirror of the SDL-side {@code ProducerStep} enum the
 * {@code @classified} directive carries. It is deliberately a flat enum: the producer axis is a
 * pipeline of these steps (see {@link DimensionTuple#producer()}), and the pipeline length is the
 * structure, not the step. See {@code roadmap/classification-test-dsl.md} §"The dimensional model".
 */
public enum ProducerStep {
    /** A new SQL query: the root query, a {@code @splitQuery} batch, a record-parent keyed load,
     *  a service re-query, or a DML follow-up SELECT. */
    Query,
    /** A developer {@code @service} method: produces rows from outside the catalog. */
    Service,
    /** A DML write ({@code @mutation} insert/update/delete/upsert): produces rows from a write. */
    Dml
}
