package no.sikt.graphitron.rewrite.field;

import no.sikt.graphitron.rewrite.JooqCatalog.ColumnEntry;

import java.util.List;

/**
 * One resolved step in a {@code @reference} path, corresponding to one {@code ReferenceElement}
 * in the schema.
 *
 * <p>All three variants are fully resolved — unresolvable path elements cause the containing
 * field to be classified as
 * {@link no.sikt.graphitron.rewrite.field.GraphitronField.UnclassifiedField} at build time,
 * before any field record is constructed.
 *
 * <ul>
 *   <li>{@link FkRef} — a jOOQ FK was resolved; no condition.</li>
 *   <li>{@link FkWithConditionRef} — a jOOQ FK was resolved; a condition method was also resolved.</li>
 *   <li>{@link ConditionOnlyRef} — a condition method was resolved; no FK (derived source conditions).</li>
 * </ul>
 */
public sealed interface ReferencePathElementRef
    permits ReferencePathElementRef.FkRef, ReferencePathElementRef.FkWithConditionRef,
            ReferencePathElementRef.ConditionOnlyRef {

    /**
     * A {@link ReferencePathElementRef} where a jOOQ foreign key was successfully resolved.
     *
     * <p>{@code fkName} is the SQL constraint name (e.g. {@code "film_language_id_fkey"}).
     * {@code keyTableSqlName} is the SQL name of the <em>referenced</em> (key-side) table
     * (e.g. {@code "language"}). {@code fkTableSqlName} is the SQL name of the <em>referencing</em>
     * (FK-side) table (e.g. {@code "film"}).
     *
     * <p>{@code keyColumnEntries} is the ordered list of pre-resolved column entries for the
     * <em>referenced</em> (key) side of the FK. {@code fkColumnEntries} is the ordered list for
     * the <em>referencing</em> side. Both are populated during schema building so that generators
     * never need reflection.
     */
    record FkRef(
        String fkName,
        String keyTableSqlName,
        String fkTableSqlName,
        List<ColumnEntry> keyColumnEntries,
        List<ColumnEntry> fkColumnEntries
    ) implements ReferencePathElementRef {}

    /**
     * A {@link ReferencePathElementRef} where both a jOOQ foreign key and a condition method
     * were successfully resolved.
     *
     * <p>See {@link FkRef} for the FK fields. {@code condition} is the resolved condition method
     * (see {@link ConditionOnlyRef}).
     */
    record FkWithConditionRef(
        String fkName,
        String keyTableSqlName,
        String fkTableSqlName,
        MethodRef condition,
        List<ColumnEntry> keyColumnEntries,
        List<ColumnEntry> fkColumnEntries
    ) implements ReferencePathElementRef {}

    /**
     * A {@link ReferencePathElementRef} where a condition method was successfully resolved and no
     * jOOQ FK is involved.
     *
     * <p>Used for derived source conditions on {@code @service} and {@code @externalField} fields,
     * where the condition method reconnects the result back to the parent table without a FK join.
     *
     * <p>{@code condition} is the resolved condition method; all fields on {@link MethodRef} are
     * guaranteed non-null.
     */
    record ConditionOnlyRef(MethodRef condition) implements ReferencePathElementRef {}
}
