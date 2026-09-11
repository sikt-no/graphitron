package no.sikt.graphitron.model.capture.jooq;

import no.sikt.graphitron.model.sink.RowChunks;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Rows;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.UniqueKey;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static no.sikt.graphitron.model.Tables.SQL_INDEX;
import static no.sikt.graphitron.model.Tables.SQL_INDEX_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes the consumer's database as the generated jOOQ classes describe it.
 *
 * <p>The {@code sql_} family and nothing else: one source of one shape behind one entry point.
 *
 * <p>Keyed on the source rather than on a graph, because a jOOQ package describes one database and
 * several graphs may read it. The sweep is scoped the same way, so a source that has left the build
 * keeps its rows until the registry drops it.
 *
 * <p>Reads {@link JooqCatalog} directly rather than a consumer-shaped view of it. A view built for
 * one reader carries that reader's narrowings, and a narrowing landing here would become a fact
 * about the consumer's database.
 */
public final class JooqFactCapture {

    private JooqFactCapture() {}

    /** Makes the {@code sql_} rows of every source {@code jooq} describes be what it now says. */
    public static void capture(DSLContext dsl, JooqCatalog jooq, LocalDateTime touchedAt) {
        if (jooq == null) {
            return;
        }
        List<TableAt> tables = tablesOf(jooq);
        if (tables.isEmpty()) {
            return;
        }
        // Parents before children, which the sweep then takes in reverse.
        sources(dsl, tables, touchedAt);
        schemas(dsl, jooq, tables, touchedAt);
        tables(dsl, tables, touchedAt);
        columns(dsl, jooq, tables, touchedAt);
        List<ConstraintAt> constraints = constraintsOf(jooq, tables);
        constraints(dsl, constraints, touchedAt);
        constraintColumns(dsl, constraints, touchedAt);
        primaryKeys(dsl, tables, touchedAt);
        referentialConstraints(dsl, jooq, tables, touchedAt);
        indexes(dsl, jooq, tables, touchedAt);
        indexColumns(dsl, jooq, tables, touchedAt);
        nodeMetadata(dsl, jooq, tables, touchedAt);
        nodeKeyColumns(dsl, jooq, tables, touchedAt);
        routines(dsl, jooq, tables, touchedAt);
        routineParameters(dsl, jooq, tables, touchedAt);
        enumBindings(dsl, tables, touchedAt);
        sweep(dsl, sourceNames(tables), touchedAt);
    }

    // --------------------------------------------------------------- what a row is written about

    /** One table of the generated model, with the parts every row about it repeats. */
    private record TableAt(String source, String schema, String name, Table<?> table,
                           String jooqName) {}

    /** One constraint of one table, and the columns it names in key order. */
    private record ConstraintAt(TableAt at, String name, String type, String jooqName,
                                Integer keyPosition, List<String> columns) {}

    /** A value at its place in a parent's ordered list. */
    private record PositionAt<P>(P parent, int position, String value) {}

    // ------------------------------------------------------------------------- the sql_ relations

    /**
     * The registry rows every {@code sql_} row hangs its source on. A jOOQ package is a source the
     * store read, on the same terms as a schema file.
     */
    private static void sources(DSLContext dsl, List<TableAt> tables, LocalDateTime touchedAt) {
        var t = STORE_SOURCE;
        var rows = sourceNames(tables).stream().collect(Rows.toRowList(
            source -> val(source, t.SOURCE_NAME),
            source -> val("JOOQ_SCHEMA", t.SOURCE_KIND),
            source -> val(touchedAt, t.LAST_SEEN),
            source -> val(touchedAt, t.READ_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.SOURCE_KIND, t.LAST_SEEN, t.READ_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.LAST_SEEN, excluded(t.LAST_SEEN))
                .set(t.READ_AT, excluded(t.READ_AT)));
    }

    private static void schemas(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                LocalDateTime touchedAt) {
        var t = SQL_SCHEMA;
        var seen = schemasOf(tables);
        if (seen.isEmpty()) {
            return;
        }
        var rows = seen.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(jooq.keysClassFqn(at.table().getSchema()).orElse(null), t.KEYS_CLASS_FQN),
            at -> val(jooq.tablesClassFqn(at.table().getSchema()).orElse(null), t.TABLES_CLASS_FQN),
            at -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.KEYS_CLASS_FQN, t.TABLES_CLASS_FQN,
                    t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.KEYS_CLASS_FQN, excluded(t.KEYS_CLASS_FQN))
                .set(t.TABLES_CLASS_FQN, excluded(t.TABLES_CLASS_FQN))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void tables(DSLContext dsl, List<TableAt> tables, LocalDateTime touchedAt) {
        var t = SQL_TABLE;
        var rows = tables.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(at.name(), t.TABLE_NAME),
            at -> val(at.table().getTableType().name(), t.TABLE_TYPE),
            at -> val(at.jooqName(), t.JOOQ_NAME),
            at -> val(at.table().getClass().getName(), t.CLASS_FQN),
            at -> val(at.table().getRecordType().getName(), t.RECORD_CLASS_FQN),
            at -> val(nullIfBlank(at.table().getComment()), t.DESCRIPTION),
            at -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TABLE_TYPE, t.JOOQ_NAME,
                    t.CLASS_FQN, t.RECORD_CLASS_FQN, t.DESCRIPTION, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TABLE_TYPE, excluded(t.TABLE_TYPE))
                .set(t.JOOQ_NAME, excluded(t.JOOQ_NAME))
                .set(t.CLASS_FQN, excluded(t.CLASS_FQN))
                .set(t.RECORD_CLASS_FQN, excluded(t.RECORD_CLASS_FQN))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * The ordinal is the table definition's own field order, not the walk's. A generated field the
     * table does not declare has no position the definition gives it, and is left out rather than
     * given one this reading invented.
     */
    private static void columns(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                LocalDateTime touchedAt) {
        var t = SQL_COLUMN;
        List<PositionAt<TableAt>> named = new ArrayList<>();
        List<JooqCatalog.ColumnFacts> facts = new ArrayList<>();
        for (TableAt at : tables) {
            Map<String, Integer> positions = declaredPositions(at.table());
            for (JooqCatalog.ColumnFacts column : jooq.columnFactsOf(at.table())) {
                Integer ordinal = positions.get(column.sqlName());
                if (ordinal == null) {
                    continue;
                }
                named.add(new PositionAt<>(at, ordinal, column.sqlName()));
                facts.add(column);
            }
        }
        if (named.isEmpty()) {
            return;
        }
        var indexes = indices(named.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(named.get(i).parent().source(), t.SOURCE_NAME),
            i -> val(named.get(i).parent().schema(), t.TABLE_SCHEMA),
            i -> val(named.get(i).parent().name(), t.TABLE_NAME),
            i -> val(named.get(i).value(), t.COLUMN_NAME),
            i -> val(named.get(i).position(), t.ORDINAL),
            i -> val(facts.get(i).javaName(), t.JOOQ_NAME),
            i -> val(facts.get(i).sqlType(), t.SQL_TYPE),
            i -> val(facts.get(i).bindingType(), t.BINDING_TYPE),
            i -> val(facts.get(i).nullable(), t.NULLABLE),
            i -> val(nullIfBlank(facts.get(i).comment()), t.DESCRIPTION),
            i -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.COLUMN_NAME, t.ORDINAL,
                    t.JOOQ_NAME, t.SQL_TYPE, t.BINDING_TYPE, t.NULLABLE, t.DESCRIPTION, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.ORDINAL, excluded(t.ORDINAL))
                .set(t.JOOQ_NAME, excluded(t.JOOQ_NAME))
                .set(t.SQL_TYPE, excluded(t.SQL_TYPE))
                .set(t.BINDING_TYPE, excluded(t.BINDING_TYPE))
                .set(t.NULLABLE, excluded(t.NULLABLE))
                .set(t.DESCRIPTION, excluded(t.DESCRIPTION))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void constraints(DSLContext dsl, List<ConstraintAt> constraints,
                                    LocalDateTime touchedAt) {
        var t = SQL_CONSTRAINT;
        if (constraints.isEmpty()) {
            return;
        }
        var rows = constraints.stream().collect(Rows.toRowList(
            c -> val(c.at().source(), t.SOURCE_NAME),
            c -> val(c.at().schema(), t.TABLE_SCHEMA),
            c -> val(c.at().name(), t.TABLE_NAME),
            c -> val(c.name(), t.CONSTRAINT_NAME),
            c -> val(c.type(), t.CONSTRAINT_TYPE),
            c -> val(c.jooqName(), t.JOOQ_NAME),
            c -> val(c.keyPosition(), t.KEY_POSITION),
            c -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME,
                    t.CONSTRAINT_TYPE, t.JOOQ_NAME, t.KEY_POSITION, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.CONSTRAINT_TYPE, excluded(t.CONSTRAINT_TYPE))
                .set(t.JOOQ_NAME, excluded(t.JOOQ_NAME))
                .set(t.KEY_POSITION, excluded(t.KEY_POSITION))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void constraintColumns(DSLContext dsl, List<ConstraintAt> constraints,
                                          LocalDateTime touchedAt) {
        var t = SQL_CONSTRAINT_COLUMN;
        List<PositionAt<ConstraintAt>> named = new ArrayList<>();
        for (ConstraintAt c : constraints) {
            for (int i = 0; i < c.columns().size(); i++) {
                named.add(new PositionAt<>(c, i, c.columns().get(i)));
            }
        }
        if (named.isEmpty()) {
            return;
        }
        var rows = named.stream().collect(Rows.toRowList(
            at -> val(at.parent().at().source(), t.SOURCE_NAME),
            at -> val(at.parent().at().schema(), t.TABLE_SCHEMA),
            at -> val(at.parent().at().name(), t.TABLE_NAME),
            at -> val(at.parent().name(), t.CONSTRAINT_NAME),
            at -> val(at.position(), t.POSITION),
            at -> val(at.value(), t.COLUMN_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME,
                    t.POSITION, t.COLUMN_NAME, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void primaryKeys(DSLContext dsl, List<TableAt> tables, LocalDateTime touchedAt) {
        var t = SQL_PRIMARY_KEY;
        var withKey = tables.stream().filter(at -> at.table().getPrimaryKey() != null).toList();
        if (withKey.isEmpty()) {
            return;
        }
        var rows = withKey.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(at.name(), t.TABLE_NAME),
            at -> val(at.table().getPrimaryKey().getName(), t.CONSTRAINT_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.CONSTRAINT_NAME,
                    t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.CONSTRAINT_NAME, excluded(t.CONSTRAINT_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /**
     * The referential half of a foreign key. The target columns are deliberately not copied here:
     * they are the referenced constraint's own {@code sql_constraint_column} rows matched on
     * position, which is what SQL guarantees.
     */
    private static void referentialConstraints(DSLContext dsl, JooqCatalog jooq,
                                               List<TableAt> tables, LocalDateTime touchedAt) {
        var t = SQL_REFERENTIAL_CONSTRAINT;
        Map<String, String> sourceByTable = sourceByTable(tables);
        List<TableAt> owners = new ArrayList<>();
        List<JooqCatalog.ForeignKeyFacts> keys = new ArrayList<>();
        for (TableAt at : tables) {
            for (JooqCatalog.ForeignKeyFacts key : jooq.foreignKeyFactsOf(at.table())) {
                owners.add(at);
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        var indexes = indices(keys.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(owners.get(i).source(), t.SOURCE_NAME),
            i -> val(sourceByTable.getOrDefault(keys.get(i).targetTable(), owners.get(i).source()),
                t.REFERENCED_SOURCE_NAME),
            i -> val(owners.get(i).schema(), t.TABLE_SCHEMA),
            i -> val(owners.get(i).name(), t.TABLE_NAME),
            i -> val(keys.get(i).constraintName(), t.CONSTRAINT_NAME),
            i -> val(split(keys.get(i).targetTable())[0], t.REFERENCED_SCHEMA),
            i -> val(split(keys.get(i).targetTable())[1], t.REFERENCED_TABLE),
            i -> val(keys.get(i).referencedConstraintName(), t.REFERENCED_CONSTRAINT_NAME),
            i -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.REFERENCED_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME,
                    t.CONSTRAINT_NAME, t.REFERENCED_SCHEMA, t.REFERENCED_TABLE,
                    t.REFERENCED_CONSTRAINT_NAME, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.REFERENCED_SOURCE_NAME, excluded(t.REFERENCED_SOURCE_NAME))
                .set(t.REFERENCED_SCHEMA, excluded(t.REFERENCED_SCHEMA))
                .set(t.REFERENCED_TABLE, excluded(t.REFERENCED_TABLE))
                .set(t.REFERENCED_CONSTRAINT_NAME, excluded(t.REFERENCED_CONSTRAINT_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void indexes(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                LocalDateTime touchedAt) {
        var t = SQL_INDEX;
        List<TableAt> owners = new ArrayList<>();
        List<JooqCatalog.IndexFacts> found = new ArrayList<>();
        for (TableAt at : tables) {
            for (JooqCatalog.IndexFacts index : jooq.indexFactsOf(at.table())) {
                owners.add(at);
                found.add(index);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var indexes = indices(found.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(owners.get(i).source(), t.SOURCE_NAME),
            i -> val(owners.get(i).schema(), t.TABLE_SCHEMA),
            i -> val(owners.get(i).name(), t.TABLE_NAME),
            i -> val(found.get(i).name(), t.INDEX_NAME),
            i -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.INDEX_NAME, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void indexColumns(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                     LocalDateTime touchedAt) {
        var t = SQL_INDEX_COLUMN;
        List<PositionAt<TableAt>> named = new ArrayList<>();
        List<String> indexNames = new ArrayList<>();
        for (TableAt at : tables) {
            for (JooqCatalog.IndexFacts index : jooq.indexFactsOf(at.table())) {
                for (int i = 0; i < index.columns().size(); i++) {
                    named.add(new PositionAt<>(at, i, index.columns().get(i)));
                    indexNames.add(index.name());
                }
            }
        }
        if (named.isEmpty()) {
            return;
        }
        var indexes = indices(named.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(named.get(i).parent().source(), t.SOURCE_NAME),
            i -> val(named.get(i).parent().schema(), t.TABLE_SCHEMA),
            i -> val(named.get(i).parent().name(), t.TABLE_NAME),
            i -> val(indexNames.get(i), t.INDEX_NAME),
            i -> val(named.get(i).position(), t.POSITION),
            i -> val(named.get(i).value(), t.COLUMN_NAME),
            i -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.INDEX_NAME, t.POSITION,
                    t.COLUMN_NAME, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void nodeMetadata(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                     LocalDateTime touchedAt) {
        var t = SQL_NODE_METADATA;
        List<TableAt> owners = new ArrayList<>();
        List<JooqCatalog.NodeMetadataFacts> found = new ArrayList<>();
        for (TableAt at : tables) {
            jooq.nodeMetadataFactsOf(at.table()).ifPresent(facts -> {
                owners.add(at);
                found.add(facts);
            });
        }
        if (found.isEmpty()) {
            return;
        }
        var indexes = indices(found.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(owners.get(i).source(), t.SOURCE_NAME),
            i -> val(owners.get(i).schema(), t.TABLE_SCHEMA),
            i -> val(owners.get(i).name(), t.TABLE_NAME),
            i -> val(found.get(i).typeIdForm().name(), t.TYPE_ID_FORM),
            i -> val(found.get(i).typeId(), t.TYPE_ID),
            i -> val(found.get(i).typeIdClass(), t.TYPE_ID_CLASS),
            i -> val(found.get(i).keyColumnsForm().name(), t.KEY_COLUMNS_FORM),
            i -> val(found.get(i).keyColumnsClass(), t.KEY_COLUMNS_CLASS),
            i -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TYPE_ID_FORM, t.TYPE_ID,
                    t.TYPE_ID_CLASS, t.KEY_COLUMNS_FORM, t.KEY_COLUMNS_CLASS, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TYPE_ID_FORM, excluded(t.TYPE_ID_FORM))
                .set(t.TYPE_ID, excluded(t.TYPE_ID))
                .set(t.TYPE_ID_CLASS, excluded(t.TYPE_ID_CLASS))
                .set(t.KEY_COLUMNS_FORM, excluded(t.KEY_COLUMNS_FORM))
                .set(t.KEY_COLUMNS_CLASS, excluded(t.KEY_COLUMNS_CLASS))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void nodeKeyColumns(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                       LocalDateTime touchedAt) {
        var t = SQL_NODE_KEY_COLUMN;
        List<PositionAt<TableAt>> named = new ArrayList<>();
        for (TableAt at : tables) {
            jooq.nodeMetadataFactsOf(at.table()).ifPresent(facts -> {
                for (int i = 0; i < facts.keyColumnNames().size(); i++) {
                    named.add(new PositionAt<>(at, i, facts.keyColumnNames().get(i)));
                }
            });
        }
        if (named.isEmpty()) {
            return;
        }
        var rows = named.stream().collect(Rows.toRowList(
            at -> val(at.parent().source(), t.SOURCE_NAME),
            at -> val(at.parent().schema(), t.TABLE_SCHEMA),
            at -> val(at.parent().name(), t.TABLE_NAME),
            at -> val(at.position(), t.POSITION),
            at -> val(at.value(), t.COLUMN_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.POSITION, t.COLUMN_NAME,
                    t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.COLUMN_NAME, excluded(t.COLUMN_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void routines(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                 LocalDateTime touchedAt) {
        var t = SQL_ROUTINE;
        var functions = functionsOf(tables);
        if (functions.isEmpty()) {
            return;
        }
        var rows = functions.stream().collect(Rows.toRowList(
            at -> val(at.source(), t.SOURCE_NAME),
            at -> val(at.schema(), t.TABLE_SCHEMA),
            at -> val(at.name(), t.ROUTINE_NAME),
            at -> val("FUNCTION", t.ROUTINE_TYPE),
            at -> val(jooq.routineCallFactsOf(at.table()).routinesClassFqn(), t.ROUTINES_CLASS_FQN),
            at -> val(jooq.routineCallFactsOf(at.table()).methodName(), t.ROUTINES_METHOD_NAME),
            at -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.ROUTINE_NAME, t.ROUTINE_TYPE,
                    t.ROUTINES_CLASS_FQN, t.ROUTINES_METHOD_NAME, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.ROUTINE_TYPE, excluded(t.ROUTINE_TYPE))
                .set(t.ROUTINES_CLASS_FQN, excluded(t.ROUTINES_CLASS_FQN))
                .set(t.ROUTINES_METHOD_NAME, excluded(t.ROUTINES_METHOD_NAME))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    private static void routineParameters(DSLContext dsl, JooqCatalog jooq, List<TableAt> tables,
                                          LocalDateTime touchedAt) {
        var t = SQL_ROUTINE_PARAMETER;
        List<TableAt> owners = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        List<JooqCatalog.RoutineParamFacts> found = new ArrayList<>();
        for (TableAt at : functionsOf(tables)) {
            var parameters = jooq.routineCallFactsOf(at.table()).parameters();
            for (int i = 0; i < parameters.size(); i++) {
                owners.add(at);
                positions.add(i);
                found.add(parameters.get(i));
            }
        }
        if (found.isEmpty()) {
            return;
        }
        var indexes = indices(found.size());
        var rows = indexes.stream().collect(Rows.toRowList(
            i -> val(owners.get(i).source(), t.SOURCE_NAME),
            i -> val(owners.get(i).schema(), t.TABLE_SCHEMA),
            i -> val(owners.get(i).name(), t.ROUTINE_NAME),
            i -> val(positions.get(i), t.POSITION),
            i -> val(found.get(i).javaName(), t.JOOQ_NAME),
            i -> val(found.get(i).bindingType(), t.BINDING_TYPE),
            i -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.TABLE_SCHEMA, t.ROUTINE_NAME, t.POSITION, t.JOOQ_NAME,
                    t.BINDING_TYPE, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.JOOQ_NAME, excluded(t.JOOQ_NAME))
                .set(t.BINDING_TYPE, excluded(t.BINDING_TYPE))
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    /** The Java enum a column binds to, which is a fact about the generated code and not the table. */
    private static void enumBindings(DSLContext dsl, List<TableAt> tables, LocalDateTime touchedAt) {
        var t = SQL_ENUM_BINDING;
        Map<String, TableAt> byClass = new LinkedHashMap<>();
        Map<String, Field<?>> fieldByClass = new LinkedHashMap<>();
        for (TableAt at : tables) {
            for (Field<?> field : at.table().fields()) {
                if (field.getType().isEnum()) {
                    String key = at.source() + " " + field.getType().getName();
                    byClass.putIfAbsent(key, at);
                    fieldByClass.putIfAbsent(key, field);
                }
            }
        }
        if (byClass.isEmpty()) {
            return;
        }
        var keys = new ArrayList<>(byClass.keySet());
        var rows = keys.stream().collect(Rows.toRowList(
            key -> val(byClass.get(key).source(), t.SOURCE_NAME),
            key -> val(fieldByClass.get(key).getType().getName(), t.CLASS_FQN),
            key -> val(null, t.TABLE_SCHEMA),
            key -> val(null, t.TYPE_NAME),
            key -> val(touchedAt, t.TOUCHED_AT)));
        RowChunks.execute(rows, chunk ->
            dsl.insertInto(t, t.SOURCE_NAME, t.CLASS_FQN, t.TABLE_SCHEMA, t.TYPE_NAME, t.TOUCHED_AT)
                .valuesOfRows(chunk)
                .onDuplicateKeyUpdate()
                .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT)));
    }

    // ------------------------------------------------------------------------------ mark and sweep

    /**
     * Every relation this gatherer writes, parents first. Listed rather than found by prefix: a
     * relation added above and not here would keep its stale rows silently.
     */
    private static final List<Table<?>> TABLES_TO_SWEEP = List.of(
        SQL_SCHEMA, SQL_TABLE, SQL_COLUMN, SQL_CONSTRAINT, SQL_CONSTRAINT_COLUMN, SQL_PRIMARY_KEY,
        SQL_REFERENTIAL_CONSTRAINT, SQL_INDEX, SQL_INDEX_COLUMN, SQL_NODE_METADATA,
        SQL_NODE_KEY_COLUMN, SQL_ROUTINE, SQL_ROUTINE_PARAMETER, SQL_ENUM_BINDING);

    /**
     * Deletes the rows of the sources this reading walked that it did not touch, which are the
     * tables, columns and keys the consumer's database no longer has.
     *
     * <p>Reversed, because these relations reference one another: a table deleted before its
     * columns is a foreign key violation, and the same list read backwards cannot produce one.
     */
    private static void sweep(DSLContext dsl, Set<String> sources, LocalDateTime touchedAt) {
        for (Table<?> table : TABLES_TO_SWEEP.reversed()) {
            // Table.field(Field) is a lookup by name returning this table's own typed column.
            var named = SQL_TABLE;
            dsl.deleteFrom(table)
                .where(table.field(named.SOURCE_NAME).in(sources))
                .and(table.field(named.TOUCHED_AT).isNull()
                    .or(table.field(named.TOUCHED_AT).ne(touchedAt)))
                .execute();
        }
    }

    // ----------------------------------------------------------------- reading the catalog's parts

    private static List<TableAt> tablesOf(JooqCatalog jooq) {
        List<TableAt> tables = new ArrayList<>();
        for (JooqCatalog.TableEntry entry : jooq.allTableEntries()) {
            Table<?> table = entry.table();
            tables.add(new TableAt(packageOf(table), schemaOf(table), table.getName(), table,
                entry.javaFieldName()));
        }
        return tables;
    }

    /**
     * Every unique key of every table as a constraint, then every foreign key. One relation with
     * two sources, which is the grammar of a constraint rather than a merge of two ideas.
     */
    private static List<ConstraintAt> constraintsOf(JooqCatalog jooq, List<TableAt> tables) {
        List<ConstraintAt> constraints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TableAt at : tables) {
            UniqueKey<?> primary = at.table().getPrimaryKey();
            var keys = new LinkedHashSet<UniqueKey<?>>(at.table().getKeys());
            if (primary != null) {
                keys.add(primary);
            }
            int keyPosition = 0;
            for (UniqueKey<?> key : keys) {
                if (!seen.add(at.source() + " " + at.schema() + " " + at.name() + " " + key.getName())) {
                    continue;
                }
                constraints.add(new ConstraintAt(at, key.getName(),
                    key.equals(primary) ? "PRIMARY KEY" : "UNIQUE",
                    jooq.keyJavaConstantName(key).orElse(null), keyPosition++,
                    key.getFields().stream().map(Field::getName).toList()));
            }
            for (JooqCatalog.ForeignKeyFacts key : jooq.foreignKeyFactsOf(at.table())) {
                if (!seen.add(at.source() + " " + at.schema() + " " + at.name() + " " + key.constraintName())) {
                    continue;
                }
                constraints.add(new ConstraintAt(at, key.constraintName(), "FOREIGN KEY",
                    key.jooqName(), null, key.columns()));
            }
        }
        return constraints;
    }

    private static List<TableAt> schemasOf(List<TableAt> tables) {
        Map<String, TableAt> byKey = new LinkedHashMap<>();
        for (TableAt at : tables) {
            if (at.table().getSchema() != null) {
                byKey.putIfAbsent(at.source() + " " + at.schema(), at);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static List<TableAt> functionsOf(List<TableAt> tables) {
        return tables.stream().filter(at -> at.table().getTableType().isFunction()).toList();
    }

    private static Set<String> sourceNames(List<TableAt> tables) {
        Set<String> sources = new LinkedHashSet<>();
        tables.forEach(at -> sources.add(at.source()));
        return sources;
    }

    private static Map<String, String> sourceByTable(List<TableAt> tables) {
        Map<String, String> byTable = new LinkedHashMap<>();
        tables.forEach(at -> byTable.put(at.schema() + "." + at.name(), at.source()));
        return byTable;
    }

    private static Map<String, Integer> declaredPositions(Table<?> table) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        Field<?>[] declared = table.fields();
        for (int i = 0; i < declared.length; i++) {
            positions.put(declared[i].getName(), i);
        }
        return positions;
    }

    /** The generated table class's package, which is the source a jOOQ-described row belongs to. */
    private static String packageOf(Table<?> table) {
        String fqn = table.getClass().getName();
        int lastDot = fqn.lastIndexOf('.');
        String pkg = lastDot < 0 ? "" : fqn.substring(0, lastDot);
        return pkg.endsWith(".tables") ? pkg.substring(0, pkg.length() - ".tables".length()) : pkg;
    }

    private static String schemaOf(Table<?> table) {
        return table.getSchema() == null ? "" : table.getSchema().getName();
    }

    /** A qualified table name as its schema and its table, the way the catalog spells it. */
    private static String[] split(String qualified) {
        int dot = qualified.indexOf('.');
        return dot < 0 ? new String[] {"", qualified}
            : new String[] {qualified.substring(0, dot), qualified.substring(dot + 1)};
    }

    private static List<Integer> indices(int size) {
        List<Integer> indexes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            indexes.add(i);
        }
        return indexes;
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
