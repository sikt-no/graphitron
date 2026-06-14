package no.sikt.graphitron.rewrite.model;

/**
 * The {@code mapping} dimension (R281): what domain object a field's value <em>is</em>. Total over
 * the classified output-field leaf set, materialised by {@link OutputField#mapping()}.
 *
 * <p>The catalog-vs-service split ({@link #Table} / {@link #TableConnection} / {@link #Column} on
 * the catalog side, {@link #Record} / {@link #Field} on the service side) is the mirror/reflect
 * distinction: catalog mappings mirror a query result, service mappings reflect a Java object.
 * {@code Table : Column :: Record : Field}.
 *
 * <p>Polymorphic (interface/union/node) results are <em>not</em> a distinct mapping value: every
 * participant is a {@code @table}/NodeType, so the value is catalog-bound and maps to {@link #Table}
 * ({@link #TableConnection} when paginated), with the polymorphic participant set living in a derived
 * slot. This matches the spec's grounding of "polymorphic resolution" as a slot, not an asserted axis
 * (R281 §"Grounding in the model's traits").
 */
public enum Mapping {
    /** A catalog table-bound result (single or list), including polymorphic results over participant tables. */
    Table,
    /** A catalog table-bound result wrapped in a Relay connection (pagination). */
    TableConnection,
    /** A single catalog column projected from a table-backed parent. */
    Column,
    /** A service {@code @record}/pojo object. */
    Record,
    /** A scalar reflected off a service {@code @record}/pojo parent. */
    Field
}
