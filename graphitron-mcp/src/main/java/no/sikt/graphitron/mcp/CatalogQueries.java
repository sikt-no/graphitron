package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Records;
import org.jooq.Select;

import java.util.ArrayList;
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
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

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
     * One table's description: everything the {@code sql_} family says about it, projected in one
     * statement at the table's own grain, so the columns of one capture cannot appear beside the keys
     * of the next.
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
     * @param columnPairs the constraint's columns in its own order, each paired with the referenced
     *     column at the same position. One list of pairs rather than two parallel lists, because the
     *     pairing is what the read guarantees: two lists can disagree in length or in order and still
     *     look like an answer, where a pair cannot. The wire's two arrays are the transposition of
     *     this, taken where the contract asks for them
     */
    record ForeignKeyEntry(String constraintName, String otherTable, List<ColumnPair> columnPairs) {}

    /**
     * One position of a foreign key: the referencing column and the referenced column that sits at the
     * same position in the referenced constraint.
     */
    record ColumnPair(String column, String targetColumn) {}

    /**
     * Describes the table {@code tableArg} names, reading the census through {@code reader} inside one
     * transaction.
     *
     * <p>The reader rather than the handle the other tools query through, and that is the whole reason
     * this method takes one. An answer here is two statements, one resolving the spelling and one
     * projecting the table, so on a shared connection a capture commit could land between them and the
     * description would come back empty for a table the resolution had just found; the handle's
     * connection belongs to the session's writer, where a nested transaction is a savepoint rather than
     * a boundary. Reading through a second connection makes the consistency structural. What can no
     * longer happen at all is a description tearing internally, the whole of it being one statement.
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
    static StoreAnswer<TableResolution> describe(
        StoreReader reader, String graphName, String tableArg, Optional<String> schemaArg
    ) {
        var spelling = Spelling.parse(tableArg, schemaArg);
        if (spelling.isEmpty()) {
            // No statement is issued, so there is no budget to overrun: a malformed spelling is an
            // answer this method reached on its own.
            return new StoreAnswer.Answered<>(new TableResolution.NotFound());
        }
        return reader.read(dsl -> resolve(new StoreHandle(dsl, graphName), spelling.get()));
    }

    /**
     * Resolves the spelling against the census and, on a unique match, reads the rest for it.
     *
     * <p>Ambiguity is reported only for a bare spelling. A qualified one names a schema, and within a
     * graph a {@code (schema, table)} pair identifies one row, so a second match there would mean one
     * schema generated into two packages rather than a question about which table was meant.
     *
     * <p>A read of its own rather than a grain of the description, which is where the one-projection
     * rule draws its own boundary. Whether a spelling names one table, two or none decides between
     * describing a table, naming candidate schemas and reporting nothing found: a different question
     * from "describe this table", and the answer to it is what says whether the other question is worth
     * asking. Folding the two would project a whole description per candidate to discover that the
     * spelling was ambiguous.
     */
    private static TableResolution resolve(StoreHandle store, Spelling spelling) {
        var filters = new ArrayList<Condition>();
        filters.add(store.reads(SQL_TABLE.SOURCE_NAME));
        filters.add(SQL_TABLE.TABLE_NAME.equalIgnoreCase(spelling.table()));
        spelling.schema().ifPresent(s -> filters.add(SQL_TABLE.TABLE_SCHEMA.equalIgnoreCase(s)));

        var matches = store.dsl()
            .select(SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME)
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
        return new TableResolution.Resolved(
            describeTable(store, new TableKey(row.value1(), row.value2(), row.value3())));
    }

    /**
     * The whole description of one resolved table, projected in one statement at the table's own grain.
     *
     * <p>Every child list is a {@code MULTISET} correlated to the table row by the foreign key it
     * already carries, and the two lists that are themselves lists of lists (a key's or an index's
     * columns) nest a second {@code MULTISET} inside the first. Nothing is grouped afterwards, which is
     * what removes the three ways a folded read goes wrong: a grouping key invented in Java can be
     * invented wrong, a consistency the statement holds needs no argument, and a child list that
     * multiplies the parent row count cannot arise from a projection that never joins siblings
     * together.
     *
     * <p>{@code fetchSingle} rather than a fetch that tolerates nothing coming back: the key was
     * resolved against this same census inside this same transaction, so no row here would mean the
     * transaction is not the one the resolution ran in.
     */
    private static TableDetail describeTable(StoreHandle store, TableKey key) {
        return store.dsl()
            .select(
                SQL_TABLE.TABLE_SCHEMA,
                SQL_TABLE.TABLE_NAME,
                SQL_TABLE.DESCRIPTION,
                columns(),
                primaryKey(),
                uniqueKeys(),
                indexes(),
                foreignKeys(
                    childOf(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME,
                        SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA,
                        SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME),
                    qualified(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA,
                        SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE)),
                foreignKeys(
                    childOf(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SOURCE_NAME,
                        SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA,
                        SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE),
                    qualified(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA,
                        SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME)))
            .from(SQL_TABLE)
            .where(scopedTo(key, SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME))
            .fetchSingle(Records.mapping(TableDetail::new));
    }

    /**
     * A resolved table's whole key, which is what the reads below filter on rather than its name. The
     * key came out of a scoped resolution, so it is already narrower than the graph predicate the
     * census read applies: matching it again through the semi-join would suggest the key were somebody
     * else's, and a same-named table in another schema is a different table rather than a looser match
     * on this one.
     */
    private record TableKey(String sourceName, String schema, String name) {}

    /** The predicate binding one of the {@code sql_} relations to a resolved table. */
    private static Condition scopedTo(
        TableKey key, Field<String> source, Field<String> schema, Field<String> table
    ) {
        return source.eq(key.sourceName())
            .and(schema.eq(key.schema()))
            .and(table.eq(key.name()));
    }

    /**
     * The predicate correlating a child relation to the table row being described, which is the
     * foreign key that relation declares against {@code sql_table} spelled as a join. Every nested
     * list below hangs off this rather than off the key's values: a correlation the census guarantees
     * cannot pair a child with the wrong parent, where a predicate over copied-down values is one
     * transcription away from doing exactly that.
     */
    private static Condition childOf(
        Field<String> source, Field<String> schema, Field<String> table
    ) {
        return source.eq(SQL_TABLE.SOURCE_NAME)
            .and(schema.eq(SQL_TABLE.TABLE_SCHEMA))
            .and(table.eq(SQL_TABLE.TABLE_NAME));
    }

    /** A schema-qualified table name composed in SQL, the form every catalog tool hands back. */
    private static Field<String> qualified(Field<String> schema, Field<String> table) {
        return concat(schema, inline("."), table);
    }

    /**
     * The table's columns in {@code ordinal} order, which is the position {@code Table.fields()}
     * states and therefore the table definition's. The projection this replaced carried a reflective
     * field walk's order, which is documented as no order in particular.
     */
    private static Field<List<ColumnEntry>> columns() {
        return multiset(
            select(SQL_COLUMN.COLUMN_NAME, SQL_COLUMN.JOOQ_NAME, SQL_COLUMN.SQL_TYPE,
                SQL_COLUMN.NULLABLE, SQL_COLUMN.DESCRIPTION)
                .from(SQL_COLUMN)
                .where(childOf(SQL_COLUMN.SOURCE_NAME, SQL_COLUMN.TABLE_SCHEMA,
                    SQL_COLUMN.TABLE_NAME))
                .orderBy(SQL_COLUMN.ORDINAL.asc()))
            .convertFrom(r -> r.map(Records.mapping(ColumnEntry::new)));
    }

    /**
     * The table's primary key, which is at most one constraint and so arrives as an {@code Optional}
     * taken off a list the census can only fill with one row.
     *
     * <p>Which key is the primary comes from {@code sql_primary_key} rather than from the type
     * discriminator beside it, because that relation is keyed by the table and so answers with at most
     * one; reading the discriminator would leave a second {@code PRIMARY KEY} row to be picked between.
     */
    private static Field<Optional<KeyEntry>> primaryKey() {
        return multiset(keyConstraints(exists(primaryKeyRow())))
            .convertFrom(r -> r.map(Records.mapping(KeyEntry::new)).stream().findFirst());
    }

    /**
     * The table's unique constraints: every key the database declares except the one
     * {@code sql_primary_key} names, which the complementary predicate takes out rather than a Java
     * pass over one list.
     *
     * <p>Every unique constraint is reported, including one whose columns the primary key already
     * covers. Row-identity matching wants distinct column sets and dedups for itself; a description
     * that dedupped was applying that consumer's rule to everyone asking.
     */
    private static Field<List<KeyEntry>> uniqueKeys() {
        return multiset(keyConstraints(notExists(primaryKeyRow())))
            .convertFrom(r -> r.map(Records.mapping(KeyEntry::new)));
    }

    /**
     * The table's primary and unique keys narrowed by {@code primaryness}, ordered by constraint name,
     * each carrying its own columns in the constraint's column order.
     *
     * <p>The type predicate names {@code PRIMARY KEY} and {@code UNIQUE} rather than excluding
     * {@code FOREIGN KEY}, so a type the census learns to write arrives as a row nobody asked for
     * instead of as a key nobody declared.
     */
    private static Select<Record2<String, List<String>>> keyConstraints(Condition primaryness) {
        return select(SQL_CONSTRAINT.CONSTRAINT_NAME, constraintColumns())
            .from(SQL_CONSTRAINT)
            .where(childOf(SQL_CONSTRAINT.SOURCE_NAME, SQL_CONSTRAINT.TABLE_SCHEMA,
                SQL_CONSTRAINT.TABLE_NAME))
            .and(SQL_CONSTRAINT.CONSTRAINT_TYPE.in(PRIMARY_KEY, UNIQUE))
            .and(primaryness)
            .orderBy(SQL_CONSTRAINT.CONSTRAINT_NAME.asc());
    }

    /** The {@code sql_primary_key} row for the constraint being projected, if it is the primary key. */
    private static Select<?> primaryKeyRow() {
        return selectOne()
            .from(SQL_PRIMARY_KEY)
            .where(SQL_PRIMARY_KEY.SOURCE_NAME.eq(SQL_CONSTRAINT.SOURCE_NAME)
                .and(SQL_PRIMARY_KEY.TABLE_SCHEMA.eq(SQL_CONSTRAINT.TABLE_SCHEMA))
                .and(SQL_PRIMARY_KEY.TABLE_NAME.eq(SQL_CONSTRAINT.TABLE_NAME))
                .and(SQL_PRIMARY_KEY.CONSTRAINT_NAME.eq(SQL_CONSTRAINT.CONSTRAINT_NAME)));
    }

    /** One constraint's columns in its own column order, correlated to the constraint being projected. */
    private static Field<List<String>> constraintColumns() {
        return multiset(
            select(SQL_CONSTRAINT_COLUMN.COLUMN_NAME)
                .from(SQL_CONSTRAINT_COLUMN)
                .where(SQL_CONSTRAINT_COLUMN.SOURCE_NAME.eq(SQL_CONSTRAINT.SOURCE_NAME)
                    .and(SQL_CONSTRAINT_COLUMN.TABLE_SCHEMA.eq(SQL_CONSTRAINT.TABLE_SCHEMA))
                    .and(SQL_CONSTRAINT_COLUMN.TABLE_NAME.eq(SQL_CONSTRAINT.TABLE_NAME))
                    .and(SQL_CONSTRAINT_COLUMN.CONSTRAINT_NAME.eq(SQL_CONSTRAINT.CONSTRAINT_NAME)))
                .orderBy(SQL_CONSTRAINT_COLUMN.POSITION.asc()))
            .convertFrom(r -> r.map(Record1::value1));
    }

    /**
     * The table's indexes with their columns, ordered by index name and within an index by position.
     *
     * <p>What is genuinely absent here is the indexes backing a primary key or unique constraint,
     * which {@code sql_index}'s own documentation records as excluded by what jOOQ reports; those
     * appear among the keys instead.
     */
    private static Field<List<IndexEntry>> indexes() {
        return multiset(
            select(SQL_INDEX.INDEX_NAME, indexColumns())
                .from(SQL_INDEX)
                .where(childOf(SQL_INDEX.SOURCE_NAME, SQL_INDEX.TABLE_SCHEMA, SQL_INDEX.TABLE_NAME))
                .orderBy(SQL_INDEX.INDEX_NAME.asc()))
            .convertFrom(r -> r.map(Records.mapping(IndexEntry::new)));
    }

    /** One index's columns in index order, correlated to the index being projected. */
    private static Field<List<String>> indexColumns() {
        return multiset(
            select(SQL_INDEX_COLUMN.COLUMN_NAME)
                .from(SQL_INDEX_COLUMN)
                .where(SQL_INDEX_COLUMN.SOURCE_NAME.eq(SQL_INDEX.SOURCE_NAME)
                    .and(SQL_INDEX_COLUMN.TABLE_SCHEMA.eq(SQL_INDEX.TABLE_SCHEMA))
                    .and(SQL_INDEX_COLUMN.TABLE_NAME.eq(SQL_INDEX.TABLE_NAME))
                    .and(SQL_INDEX_COLUMN.INDEX_NAME.eq(SQL_INDEX.INDEX_NAME)))
                .orderBy(SQL_INDEX_COLUMN.POSITION.asc()))
            .convertFrom(r -> r.map(Record1::value1));
    }

    /**
     * The foreign keys whose {@code endpoint} end is this table, each naming its {@code neighbour} at
     * the far end and carrying its column pairs.
     *
     * <p>One field per direction rather than one read that reports both, because the two ends are two
     * lists on the wire and the predicate that selects each is the whole difference between them. Order
     * is by the declaring table then the constraint name, which for the outgoing direction is the
     * constraint name alone, this table being the only declarer there.
     *
     * <p>Never grouped by the constraint name, which is unique per table and not per schema: in the
     * incoming direction two tables declaring a same-named key against this one are two keys, and a
     * fold keyed by name would concatenate their column lists into one. The projection is keyed by the
     * relation's own key, so the question does not arise.
     */
    private static Field<List<ForeignKeyEntry>> foreignKeys(
        Condition endpoint, Field<String> neighbour
    ) {
        return multiset(
            select(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME, neighbour, columnPairs())
                .from(SQL_REFERENTIAL_CONSTRAINT)
                .where(endpoint)
                .orderBy(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA.asc(),
                    SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME.asc(),
                    SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME.asc()))
            .convertFrom(r -> r.map(Records.mapping(ForeignKeyEntry::new)));
    }

    /**
     * One foreign key's columns paired with the columns they reference, in the constraint's own order.
     *
     * <p>The pairing is a join on position. A foreign key's target columns are the referenced
     * constraint's own rows matched on position, which {@code sql_referential_constraint} documents as
     * guaranteed by SQL semantics and never copied onto the referencing row; expressing that as
     * {@code ON referenced.position = referencing.position} makes a mispairing unrepresentable, and
     * projecting the result as pairs carries that guarantee out of the query rather than dropping it at
     * the boundary.
     */
    private static Field<List<ColumnPair>> columnPairs() {
        var referencing = SQL_CONSTRAINT_COLUMN.as("referencing");
        var referenced = SQL_CONSTRAINT_COLUMN.as("referenced");

        return multiset(
            select(referencing.COLUMN_NAME, referenced.COLUMN_NAME)
                .from(referencing)
                .join(referenced).on(
                    referenced.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SOURCE_NAME)
                        .and(referenced.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA))
                        .and(referenced.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE))
                        .and(referenced.CONSTRAINT_NAME
                            .eq(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_CONSTRAINT_NAME))
                        .and(referenced.POSITION.eq(referencing.POSITION)))
                .where(referencing.SOURCE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME)
                    .and(referencing.TABLE_SCHEMA.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA))
                    .and(referencing.TABLE_NAME.eq(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME))
                    .and(referencing.CONSTRAINT_NAME
                        .eq(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME)))
                .orderBy(referencing.POSITION.asc()))
            .convertFrom(r -> r.map(Records.mapping(ColumnPair::new)));
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
