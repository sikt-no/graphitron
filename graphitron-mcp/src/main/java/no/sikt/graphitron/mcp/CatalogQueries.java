package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_INDEX;
import static no.sikt.graphitron.model.Tables.SQL_INDEX_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.row;

/**
 * This module's own reads over the {@code sql_} catalog census, shaped by what the structured catalog
 * tools put on the wire. The semantic tool's whole-graph read is its own, beside the index that embeds
 * it: what the store shares is the relation, and a query belongs with the surface that shapes it.
 *
 * <p>Written here rather than reused from another consumer's readers, which is the arrangement the
 * store is for: what two modules share is the relation, and a Java row vocabulary crossing a module
 * boundary is the coupling the shared base exists to make unnecessary. The language server asks
 * whether a spelling lands anywhere, to decide a squiggle; these queries assemble a wire response.
 * The two overlap in {@code FROM} clause and nowhere else. Where a rule genuinely must be shared it
 * graduates to a store view instead, which is why nothing here re-implements one.
 *
 * <p>Every read reaches the census through {@link StoreHandle#reads}, the {@code sql_} family being
 * source-keyed rather than graph-keyed: a query that forgets the predicate answers with a sibling
 * module's tables folded in, which reads as a workspace-wide census. A read that starts from a table
 * already resolved through that predicate inherits the scope instead of repeating it, filtering on
 * the resolved table's whole key, which is strictly narrower.
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

    // ---- catalog.describe ----

    /**
     * {@code sql_constraint.constraint_type}'s two key forms. The relation's own {@code CHECK} closes
     * the domain over these and {@code FOREIGN KEY}, which the key reads exclude by naming these two
     * rather than by excluding that one: a type the census learns to write arrives as a row nobody
     * asked for instead of as a key nobody declared.
     */
    private static final String PRIMARY_KEY = "PRIMARY KEY";
    private static final String UNIQUE = "UNIQUE";

    /**
     * What a {@code catalog.describe} spelling resolved to. A spelling naming one table is described;
     * a bare one two schemas declare names the candidates instead, there being no basis in the census
     * for preferring either; anything else found nothing.
     */
    sealed interface TableResolution {

        /** Exactly one table matched, and the rest of the census has been read for it. */
        record Resolved(TableDetail table) implements TableResolution {}

        /** A bare spelling two or more schemas declare; {@code schemas} names them in order. */
        record Ambiguous(List<String> schemas) implements TableResolution {}

        /** No table matched, which includes a spelling the census could not have held. */
        record NotFound() implements TableResolution {}
    }

    /**
     * One table's description: everything the {@code sql_} family says about it, assembled inside one
     * read transaction so the columns of one capture cannot appear beside the keys of the next.
     *
     * @param comment the database comment, {@code null} where the table carries none
     */
    record TableDetail(
        String schema,
        String name,
        String comment,
        List<ColumnEntry> columns,
        Optional<KeyEntry> primaryKey,
        List<KeyEntry> uniqueKeys,
        List<IndexEntry> indexes,
        List<ForeignKeyEntry> outgoing,
        List<ForeignKeyEntry> incoming
    ) {

        /** The schema-qualified SQL name; the table id every catalog tool hands back. */
        String qualifiedName() {
            return schema + "." + name;
        }
    }

    /**
     * One column, in the position the table definition gives it.
     *
     * @param comment the database comment, {@code null} where the column carries none
     */
    record ColumnEntry(
        String sqlName, String javaName, String sqlType, boolean nullable, String comment
    ) {}

    /** A primary or unique key: its SQL constraint name and its columns in key order. */
    record KeyEntry(String constraintName, List<String> columns) {}

    /** An index: its SQL name and its columns in index order. */
    record IndexEntry(String name, List<String> columns) {}

    /**
     * One foreign key as one direction sees it.
     *
     * @param otherTable the neighbour at the far end, schema-qualified: the referenced table for an
     *     outgoing key and the declaring table for an incoming one, so one row shape serves both
     *     directions and the wire names the slot
     * @param columns the referencing table's columns, in the constraint's order
     * @param targetColumns the referenced constraint's own columns, paired to {@link #columns} by
     *     position
     */
    record ForeignKeyEntry(
        String constraintName, String otherTable, List<String> columns, List<String> targetColumns
    ) {}

    /**
     * Describes the table {@code tableArg} names, reading the census through {@code reader} inside one
     * transaction.
     *
     * <p>The reader rather than the handle the other tools query through, and that is the whole reason
     * this method takes one. An answer here is six queries, so on a shared connection it would see one
     * capture commit between its columns and its keys and report a table that never existed; the
     * handle's connection belongs to the session's writer, where a nested transaction is a savepoint
     * rather than a boundary. Reading through a second connection makes the consistency structural.
     * The reader is minted by the host and closed by the host, which is what keeps this module a store
     * client rather than a store owner.
     *
     * <p>A malformed spelling is answered before the transaction opens: it names nothing the census
     * could hold, so there is nothing to be consistent about.
     *
     * @param graphName the graph whose partition the answer is confined to, named by the handle the
     *     host gave this module alongside the reader. Passed rather than held because the scope is
     *     rebuilt per transaction: the {@code DSLContext} a read is handed is valid for that call only,
     *     so a handle built outside one would carry a query surface whose transaction has ended
     */
    static TableResolution describe(
        StoreReader reader, String graphName, String tableArg, Optional<String> schemaArg
    ) {
        var spelling = Spelling.parse(tableArg, schemaArg);
        if (spelling.isEmpty()) {
            return new TableResolution.NotFound();
        }
        return reader.read(dsl -> resolve(new StoreHandle(dsl, graphName), spelling.get()));
    }

    /**
     * Resolves the spelling against the census and, on a unique match, reads the rest for it.
     *
     * <p>Ambiguity is reported only for a bare spelling. A qualified one names a schema, and within a
     * graph a {@code (schema, table)} pair identifies one row, so a second match there would mean one
     * schema generated into two packages rather than a question about which table was meant.
     */
    private static TableResolution resolve(StoreHandle store, Spelling spelling) {
        var filters = new ArrayList<Condition>();
        filters.add(store.reads(SQL_TABLE.SOURCE_NAME));
        filters.add(SQL_TABLE.TABLE_NAME.equalIgnoreCase(spelling.table()));
        spelling.schema().ifPresent(s -> filters.add(SQL_TABLE.TABLE_SCHEMA.equalIgnoreCase(s)));

        var matches = store.dsl()
            .select(SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME,
                SQL_TABLE.DESCRIPTION)
            .from(SQL_TABLE)
            .where(filters)
            .orderBy(SQL_TABLE.TABLE_SCHEMA.asc())
            .fetch();

        if (matches.isEmpty()) {
            return new TableResolution.NotFound();
        }
        if (spelling.schema().isEmpty() && matches.size() > 1) {
            return new TableResolution.Ambiguous(
                List.copyOf(matches.getValues(SQL_TABLE.TABLE_SCHEMA)));
        }
        var row = matches.getFirst();
        var key = new TableKey(row.value1(), row.value2(), row.value3());
        var keys = keys(store, key);
        return new TableResolution.Resolved(new TableDetail(
            key.schema(), key.name(), row.value4(),
            columns(store, key),
            keys.primaryKey(), keys.uniqueKeys(),
            indexes(store, key),
            outgoing(store, key), incoming(store, key)));
    }

    /**
     * A resolved table's whole key, which is what the reads below filter on rather than its name. The
     * key came out of a scoped resolution, so it is already narrower than the graph predicate the
     * census read applies: matching it again through the semi-join would suggest the key were somebody
     * else's, and a same-named table in another schema is a different table rather than a looser match
     * on this one.
     */
    private record TableKey(String sourceName, String schema, String name) {}

    /** A table's keys, split by the relation that says which one is the primary. */
    private record Keys(Optional<KeyEntry> primaryKey, List<KeyEntry> uniqueKeys) {}

    /** The predicate binding one of the {@code sql_} relations to a resolved table. */
    private static Condition scopedTo(
        TableKey key, Field<String> source, Field<String> schema, Field<String> table
    ) {
        return source.eq(key.sourceName())
            .and(schema.eq(key.schema()))
            .and(table.eq(key.name()));
    }

    /**
     * The table's columns in {@code ordinal} order, which is the position {@code Table.fields()}
     * states and therefore the table definition's. The projection this replaced carried a reflective
     * field walk's order, which is documented as no order in particular.
     */
    private static List<ColumnEntry> columns(StoreHandle store, TableKey key) {
        return store.dsl()
            .select(SQL_COLUMN.COLUMN_NAME, SQL_COLUMN.JOOQ_NAME, SQL_COLUMN.SQL_TYPE,
                SQL_COLUMN.NULLABLE, SQL_COLUMN.DESCRIPTION)
            .from(SQL_COLUMN)
            .where(scopedTo(key, SQL_COLUMN.SOURCE_NAME, SQL_COLUMN.TABLE_SCHEMA,
                SQL_COLUMN.TABLE_NAME))
            .orderBy(SQL_COLUMN.ORDINAL.asc())
            .fetch(r -> new ColumnEntry(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
    }

    /**
     * The table's primary and unique keys with their columns, ordered by constraint name and within a
     * key by the constraint's own column position.
     *
     * <p>Which key is the primary comes from {@code sql_primary_key} rather than from the type
     * discriminator beside it, because that relation is keyed by the table and so answers with at most
     * one; reading the discriminator would leave a second {@code PRIMARY KEY} row to be picked between.
     * The join to it is outer for the same reason it is a join at all: a table need not have one.
     *
     * <p>Every unique constraint the database declares is reported, including one whose columns the
     * primary key already covers. Row-identity matching wants distinct column sets and dedups for
     * itself; a description that dedupped was applying that consumer's rule to everyone asking.
     */
    private static Keys keys(StoreHandle store, TableKey key) {
        var rows = store.dsl()
            .select(SQL_CONSTRAINT.CONSTRAINT_NAME, SQL_CONSTRAINT_COLUMN.COLUMN_NAME,
                SQL_PRIMARY_KEY.CONSTRAINT_NAME)
            .from(SQL_CONSTRAINT)
            .join(SQL_CONSTRAINT_COLUMN).on(
                SQL_CONSTRAINT_COLUMN.SOURCE_NAME.eq(SQL_CONSTRAINT.SOURCE_NAME)
                    .and(SQL_CONSTRAINT_COLUMN.TABLE_SCHEMA.eq(SQL_CONSTRAINT.TABLE_SCHEMA))
                    .and(SQL_CONSTRAINT_COLUMN.TABLE_NAME.eq(SQL_CONSTRAINT.TABLE_NAME))
                    .and(SQL_CONSTRAINT_COLUMN.CONSTRAINT_NAME.eq(SQL_CONSTRAINT.CONSTRAINT_NAME)))
            .leftJoin(SQL_PRIMARY_KEY).on(
                SQL_PRIMARY_KEY.SOURCE_NAME.eq(SQL_CONSTRAINT.SOURCE_NAME)
                    .and(SQL_PRIMARY_KEY.TABLE_SCHEMA.eq(SQL_CONSTRAINT.TABLE_SCHEMA))
                    .and(SQL_PRIMARY_KEY.TABLE_NAME.eq(SQL_CONSTRAINT.TABLE_NAME))
                    .and(SQL_PRIMARY_KEY.CONSTRAINT_NAME.eq(SQL_CONSTRAINT.CONSTRAINT_NAME)))
            .where(scopedTo(key, SQL_CONSTRAINT.SOURCE_NAME, SQL_CONSTRAINT.TABLE_SCHEMA,
                SQL_CONSTRAINT.TABLE_NAME))
            .and(SQL_CONSTRAINT.CONSTRAINT_TYPE.in(PRIMARY_KEY, UNIQUE))
            .orderBy(SQL_CONSTRAINT.CONSTRAINT_NAME.asc(), SQL_CONSTRAINT_COLUMN.POSITION.asc())
            .fetch();

        var columnsByConstraint = new LinkedHashMap<String, List<String>>();
        String primaryKeyName = null;
        for (var row : rows) {
            columnsByConstraint.computeIfAbsent(row.value1(), c -> new ArrayList<>()).add(row.value2());
            if (row.value3() != null) {
                primaryKeyName = row.value1();
            }
        }

        Optional<KeyEntry> primaryKey = Optional.empty();
        var uniqueKeys = new ArrayList<KeyEntry>();
        for (var constraint : columnsByConstraint.entrySet()) {
            var entry = new KeyEntry(constraint.getKey(), List.copyOf(constraint.getValue()));
            if (entry.constraintName().equals(primaryKeyName)) {
                primaryKey = Optional.of(entry);
            } else {
                uniqueKeys.add(entry);
            }
        }
        return new Keys(primaryKey, List.copyOf(uniqueKeys));
    }

    /**
     * The table's indexes with their columns, ordered by index name and within an index by position.
     *
     * <p>The column join is inner and stays inner: an index has columns, so a left join would widen
     * the shape to admit a row the census cannot write. What is genuinely absent here is the indexes
     * backing a primary key or unique constraint, which {@code sql_index}'s own documentation records
     * as excluded by what jOOQ reports; those appear among the keys above instead.
     */
    private static List<IndexEntry> indexes(StoreHandle store, TableKey key) {
        var rows = store.dsl()
            .select(SQL_INDEX.INDEX_NAME, SQL_INDEX_COLUMN.COLUMN_NAME)
            .from(SQL_INDEX)
            .join(SQL_INDEX_COLUMN).on(
                SQL_INDEX_COLUMN.SOURCE_NAME.eq(SQL_INDEX.SOURCE_NAME)
                    .and(SQL_INDEX_COLUMN.TABLE_SCHEMA.eq(SQL_INDEX.TABLE_SCHEMA))
                    .and(SQL_INDEX_COLUMN.TABLE_NAME.eq(SQL_INDEX.TABLE_NAME))
                    .and(SQL_INDEX_COLUMN.INDEX_NAME.eq(SQL_INDEX.INDEX_NAME)))
            .where(scopedTo(key, SQL_INDEX.SOURCE_NAME, SQL_INDEX.TABLE_SCHEMA, SQL_INDEX.TABLE_NAME))
            .orderBy(SQL_INDEX.INDEX_NAME.asc(), SQL_INDEX_COLUMN.POSITION.asc())
            .fetch();

        var columnsByIndex = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            columnsByIndex.computeIfAbsent(row.value1(), i -> new ArrayList<>()).add(row.value2());
        }
        return columnsByIndex.entrySet().stream()
            .map(index -> new IndexEntry(index.getKey(), List.copyOf(index.getValue())))
            .toList();
    }

    /** The foreign keys this table declares, reported by what each one references. */
    private static List<ForeignKeyEntry> outgoing(StoreHandle store, TableKey key) {
        return foreignKeys(store, scopedTo(key, SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME,
            SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA, SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME))
            .stream()
            .map(fk -> new ForeignKeyEntry(
                fk.constraintName(), fk.referencedTable(), fk.columns(), fk.targetColumns()))
            .toList();
    }

    /** The foreign keys other tables declare against this one, reported by what declares each. */
    private static List<ForeignKeyEntry> incoming(StoreHandle store, TableKey key) {
        return foreignKeys(store, scopedTo(key, SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SOURCE_NAME,
            SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA, SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE))
            .stream()
            .map(fk -> new ForeignKeyEntry(
                fk.constraintName(), fk.declaringTable(), fk.columns(), fk.targetColumns()))
            .toList();
    }

    /** One foreign key with both endpoints named, before a direction picks which end to report. */
    private record Fk(
        String constraintName, String declaringTable, String referencedTable,
        List<String> columns, List<String> targetColumns
    ) {}

    /** The identity of a foreign key: the declaring table's whole key plus the constraint name. */
    private record FkId(String source, String schema, String table, String name) {}

    /**
     * The foreign keys at {@code endpoint}, whichever end that binds, with both column lists paired.
     *
     * <p>The pairing is a join on position rather than a zip in Java. A foreign key's target columns
     * are the referenced constraint's own rows matched on position, which
     * {@code sql_referential_constraint} documents as guaranteed by SQL semantics and never copied
     * onto the referencing row; expressing that as {@code ON referenced.position =
     * referencing.position} makes a mispairing unrepresentable, where two lists assembled separately
     * and zipped afterwards can disagree in length or in order and still look like an answer.
     *
     * <p>Grouped by the declaring table's whole key rather than by the constraint name, which is
     * unique per table and not per schema. In the incoming direction two tables declaring a
     * same-named key against this one are two keys, and grouping by name would fold them into one
     * with both column lists concatenated.
     */
    private static List<Fk> foreignKeys(StoreHandle store, Condition endpoint) {
        var referencing = SQL_CONSTRAINT_COLUMN.as("referencing");
        var referenced = SQL_CONSTRAINT_COLUMN.as("referenced");

        var rows = store.dsl()
            .select(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME, SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA,
                SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME, SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME,
                SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA,
                SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE,
                referencing.COLUMN_NAME, referenced.COLUMN_NAME)
            .from(SQL_REFERENTIAL_CONSTRAINT)
            .join(referencing).on(
                referencing.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME)
                    .and(referencing.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA))
                    .and(referencing.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME))
                    .and(referencing.CONSTRAINT_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME)))
            .join(referenced).on(
                referenced.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SOURCE_NAME)
                    .and(referenced.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA))
                    .and(referenced.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE))
                    .and(referenced.CONSTRAINT_NAME
                        .eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_CONSTRAINT_NAME))
                    .and(referenced.POSITION.eq(referencing.POSITION)))
            .where(endpoint)
            .orderBy(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA.asc(),
                SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME.asc(),
                SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME.asc(), referencing.POSITION.asc())
            .fetch();

        // Accumulated with growable lists and rebuilt immutable below, one row per column position.
        var byKey = new LinkedHashMap<FkId, Fk>();
        for (var row : rows) {
            var id = new FkId(row.value1(), row.value2(), row.value3(), row.value4());
            var fk = byKey.computeIfAbsent(id, i -> new Fk(i.name(),
                i.schema() + "." + i.table(), row.value5() + "." + row.value6(),
                new ArrayList<>(), new ArrayList<>()));
            fk.columns().add(row.value7());
            fk.targetColumns().add(row.value8());
        }
        return byKey.values().stream()
            .map(fk -> new Fk(fk.constraintName(), fk.declaringTable(), fk.referencedTable(),
                List.copyOf(fk.columns()), List.copyOf(fk.targetColumns())))
            .toList();
    }

    /**
     * A {@code catalog.describe} table argument parsed into the pair it names: a schema when the
     * spelling qualifies itself or the separate argument supplies one, and the table name.
     *
     * <p>Split on the first dot, as the generator's own {@code @table(name:)} resolver splits it, so
     * an id this tool hands out is an id it accepts back. Inline qualification wins over the separate
     * argument, that being the more specific of the two. A spelling with an empty half
     * ({@code "film."}, {@code ".film"}) parses as nothing rather than as a name with a stray dot: it
     * names no table the census could hold.
     */
    private record Spelling(Optional<String> schema, String table) {

        static Optional<Spelling> parse(String tableArg, Optional<String> schemaArg) {
            if (tableArg == null || tableArg.isBlank()) {
                return Optional.empty();
            }
            int dot = tableArg.indexOf('.');
            if (dot < 0) {
                return Optional.of(new Spelling(schemaArg.filter(s -> !s.isBlank()), tableArg));
            }
            String schema = tableArg.substring(0, dot);
            String table = tableArg.substring(dot + 1);
            if (schema.isBlank() || table.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Spelling(Optional.of(schema), table));
        }
    }
}
