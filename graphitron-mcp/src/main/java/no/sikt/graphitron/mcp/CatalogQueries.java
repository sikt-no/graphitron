package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.row;

/**
 * This module's own reads over the {@code sql_} catalog census, shaped by what the catalog tools
 * put on the wire.
 *
 * <p>Written here rather than reused from another consumer's readers, which is the arrangement the
 * store is for: what two modules share is the relation, and a Java row vocabulary crossing a module
 * boundary is the coupling the shared base exists to make unnecessary. The language server asks
 * whether a spelling lands anywhere, to decide a squiggle; these queries assemble a wire response.
 * The two overlap in {@code FROM} clause and nowhere else. Where a rule genuinely must be shared it
 * graduates to a store view instead, which is why nothing here re-implements one.
 *
 * <p>Every read is scoped through {@link StoreHandle#reads}, the {@code sql_} family being
 * source-keyed rather than graph-keyed: a query that forgets the predicate answers with a sibling
 * module's tables folded in, which reads as a workspace-wide census.
 */
final class CatalogQueries {

    private CatalogQueries() {}

    /**
     * One census row on the {@code catalog.tables} wire: the ordering pair the page is keyed by,
     * plus the database comment jOOQ codegen captured, {@code null} where it captured none.
     */
    record TableEntry(String schema, String name, String comment) {}

    /**
     * A page of census rows, the size of the filtered census it was drawn from, and the cursor for
     * the next page, absent on the last one.
     *
     * @param total the whole filtered census rather than what is left after the cursor, which is
     *     what the summary line has always reported and is what tells an agent whether paging is
     *     worth starting. A second aggregate over the same predicate rather than a window function
     *     over this page, a window count under a keyset predicate counting only the remainder
     */
    record TablePage(List<TableEntry> entries, int total, Optional<String> nextCursor) {}

    /**
     * The {@code catalog.tables} census: the graph's tables ordered by schema then table name,
     * optionally narrowed to one schema (exact, case-insensitive) and to a case-insensitive
     * substring of the SQL name, bounded by {@code limit} in SQL.
     *
     * <p>Paging is keyset rather than offset, and the ordering pair {@code (table_schema,
     * table_name)} is both the order and the cursor. An offset is only meaningful against a result
     * order that is stable between calls; keying the page by the ordering makes that structural
     * rather than a property the census has to promise. The pair identifies a row within a graph
     * because a graph's {@code sql_} sources are generated packages that partition by schema, so
     * two of them naming one table would mean one schema generated twice.
     *
     * <p>The bound is fetched as {@code limit + 1} rows so the last page is recognised by what came
     * back rather than by a second count, and the extra row is dropped before it reaches the wire.
     */
    static TablePage tables(
        StoreHandle store, Optional<String> schema, Optional<String> nameSubstring,
        Optional<String> cursor, int limit
    ) {
        var filters = new ArrayList<Condition>();
        filters.add(store.reads(SQL_TABLE.SOURCE_NAME));
        schema.ifPresent(s -> filters.add(SQL_TABLE.TABLE_SCHEMA.equalIgnoreCase(s)));
        nameSubstring.ifPresent(n -> filters.add(SQL_TABLE.TABLE_NAME.containsIgnoreCase(n)));

        int total = store.dsl().fetchCount(SQL_TABLE, filters);

        var page = new ArrayList<>(filters);
        McpWire.decodeKeysetCursor(cursor.orElse(null), 2).ifPresent(key -> page.add(
            row(SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME).gt(key.get(0), key.get(1))));

        var rows = store.dsl()
            .select(SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME, SQL_TABLE.DESCRIPTION)
            .from(SQL_TABLE)
            .where(page)
            .orderBy(SQL_TABLE.TABLE_SCHEMA.asc(), SQL_TABLE.TABLE_NAME.asc())
            .limit(limit + 1)
            .fetch();

        var entries = rows.stream()
            .limit(limit)
            .map(r -> new TableEntry(r.value1(), r.value2(), r.value3()))
            .toList();
        var nextCursor = rows.size() > entries.size() && !entries.isEmpty()
            ? Optional.of(McpWire.encodeKeysetCursor(
                List.of(entries.getLast().schema(), entries.getLast().name())))
            : Optional.<String>empty();
        return new TablePage(entries, total, nextCursor);
    }
}
