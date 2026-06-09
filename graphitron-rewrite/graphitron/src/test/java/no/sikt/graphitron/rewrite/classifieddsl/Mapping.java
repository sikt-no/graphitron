package no.sikt.graphitron.rewrite.classifieddsl;

/**
 * The {@code mapping} dimension (R281): what domain object a field's value <em>is</em>. Total over
 * the classified output-field leaf set.
 *
 * <p>The catalog-vs-service split ({@link #Table} / {@link #TableConnection} / {@link #Column} on
 * the catalog side, {@link #Record} / {@link #Field} on the service side) is the mirror/reflect
 * distinction: catalog mappings mirror a query result, service mappings reflect a Java object.
 * {@code Table : Column :: Record : Field}.
 *
 * <p>{@link #Polymorphic} is the open value-set question slice 1 surfaces: the multi-table
 * interface/union and node leaves resolve to a UNION ALL over participant tables, not a single
 * {@code Table} projection, so folding them into {@link #Table} would misstate the verdict. Whether
 * this stays a distinct value or collapses is a slice-1 decision; see
 * {@code roadmap/classification-test-dsl.md} §"Open value-set questions".
 */
public enum Mapping {
    /** A catalog table-bound result (single or list). */
    Table,
    /** A catalog table-bound result wrapped in a Relay connection (pagination). */
    TableConnection,
    /** A single catalog column projected from a table-backed parent. */
    Column,
    /** A service {@code @record}/pojo object. */
    Record,
    /** A scalar reflected off a service {@code @record}/pojo parent. */
    Field,
    /** A polymorphic (interface/union/node) result resolved across multiple participant tables.
     *  Provisional value pending the slice-1 value-set decision. */
    Polymorphic
}
