package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_TYPE_ID;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_TYPE_BINDING;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.rewrite.model.ColumnRef.decodeBindingType;

/**
 * A node type's emission facts, assembled from the store: the {@link TableRef} its decode
 * materialises a record of, the ordered key columns the decode loads, and the wire type id it
 * matches. The first store-sourced producer of a {@link TableRef}, which until now only the live
 * catalog built.
 *
 * <p>That is the whole point rather than an incidental convenience. A producer reads facts; the
 * walked model is not a fact source, so a plan-tier join against it leaves the walk alive one tier
 * further in. Every component below is a captured fact and none is inferred: the table's own names
 * from {@code sql_table}, its columns from {@code sql_column}, its primary key through
 * {@code sql_primary_key}, the per-schema {@code Tables} constants class from
 * {@code sql_schema.tables_class_fqn}, the key list from {@code intent_resolved_node_key_column} and
 * the wire id from {@code intent_resolved_node_type_id}.
 *
 * <p>The constants class is the component worth naming, because its absence is what kept table
 * references walk-side. It is per schema and reachable only by loading it off the codegen classpath,
 * so deriving it from the table class by stripping a suffix is the guess
 * {@code sql_schema.keys_class_fqn}'s own comment forbids: the guess and the fact diverge under
 * multi-schema layouts, where each schema's class sits in that schema's own package. Capture records
 * it; this reads it.
 *
 * <p>A column's javapoet type is decoded from its captured binding type through
 * {@link ColumnRef#decodeBindingType}, which handles the array descriptor a scalar decode would
 * crash on. That matters even though nothing on the projection path reads the type, the emission
 * naming a column by its {@code javaName} alone: the refs assembled here are ordinary
 * {@link ColumnRef} values that any later consumer may read, and a partially-decoded one is a trap
 * laid for whoever reads it next rather than a saving here.
 */
public final class StoreNodeTables {

    private StoreNodeTables() {}

    /**
     * One node type's emission facts. Absent from {@link Tables#byNodeTypeName} exactly when the
     * store cannot assemble them, which for a node type means its table binding is ambiguous or its
     * schema publishes no {@code Tables} class; the consumer decides what that is worth, and for the
     * key projection it is a build failure naming both sides.
     */
    public record NodeTable(String nodeTypeName, String typeId, TableRef table,
                            List<ColumnRef> keyColumns) {

        public NodeTable {
            keyColumns = List.copyOf(keyColumns);
        }
    }

    /** The pass's typed product, keyed by node type name. */
    public record Tables(Map<String, NodeTable> byNodeTypeName) {

        public Tables {
            byNodeTypeName = Map.copyOf(byNodeTypeName);
        }

        /** The empty product, for callers producing a plan with no store behind it. */
        public static Tables empty() {
            return new Tables(Map.of());
        }

        /** The facts for one node type, absent when the store could not assemble them. */
        public Optional<NodeTable> get(String nodeTypeName) {
            return Optional.ofNullable(byNodeTypeName.get(nodeTypeName));
        }
    }

    /**
     * Assembles every node type of {@code graphName} whose facts the store can supply. Reads the
     * whole population rather than a requested subset: the query is one pass per relation either way,
     * and a keyed read would make the result depend on the caller's order of asking.
     */
    public static Tables read(DSLContext dsl, String graphName) {
        var out = new LinkedHashMap<String, NodeTable>();
        for (var binding : bindings(dsl, graphName)) {
            var table = tableRef(dsl, binding);
            if (table.isEmpty()) {
                continue;
            }
            var keyColumns = keyColumns(dsl, graphName, binding.nodeTypeName(), table.get());
            if (keyColumns.isEmpty()) {
                continue;
            }
            out.put(binding.nodeTypeName(),
                new NodeTable(binding.nodeTypeName(), binding.typeId(), table.get(), keyColumns));
        }
        return new Tables(out);
    }

    /**
     * A node type's unambiguous table binding plus its resolved wire id, which is the pair every
     * other read below is scoped by. Ambiguity drops the type here rather than downstream: two
     * candidate tables are two different record classes and two different key tuples, and picking one
     * would encode ids against a table the author never named.
     */
    private record Binding(String nodeTypeName, String typeId, String sourceName,
                           String tableSchema, String tableName) {}

    private static List<Binding> bindings(DSLContext dsl, String graphName) {
        var b = INTENT_RESOLVED_TYPE_BINDING;
        var i = INTENT_RESOLVED_NODE_TYPE_ID;
        return dsl.select(i.TYPE_NAME, i.TYPE_ID, b.TABLE_SOURCE_NAME, b.TABLE_SCHEMA, b.TABLE_NAME)
            .from(i)
            .join(b).on(b.GRAPH_NAME.eq(i.GRAPH_NAME), b.TYPE_NAME.eq(i.TYPE_NAME),
                b.CANDIDATES.eq(1))
            .where(i.GRAPH_NAME.eq(graphName))
            .orderBy(i.TYPE_NAME)
            .fetch(row -> new Binding(row.value1(), row.value2(), row.value3(), row.value4(),
                row.value5()));
    }

    /**
     * The bound table as a {@link TableRef}: its SQL name, its generated field name, the three
     * generated classes, its primary key in key order and its columns in declaration order. Absent
     * when the schema publishes no {@code Tables} class, since a ref without a constants class cannot
     * emit a column reference and a partial one is a trap for whoever reads it next.
     */
    private static Optional<TableRef> tableRef(DSLContext dsl, Binding binding) {
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var row = dsl.select(t.TABLE_NAME, t.JOOQ_NAME, t.CLASS_FQN, t.RECORD_CLASS_FQN,
                s.TABLES_CLASS_FQN)
            .from(t)
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(t.SOURCE_NAME.eq(binding.sourceName()),
                t.TABLE_SCHEMA.eq(binding.tableSchema()),
                t.TABLE_NAME.eq(binding.tableName()))
            .fetchOne();
        if (row == null || row.value5() == null) {
            return Optional.empty();
        }
        return Optional.of(new TableRef(
            row.value1(),
            row.value2(),
            ClassName.bestGuess(row.value3()),
            ClassName.bestGuess(row.value4()),
            ClassName.bestGuess(row.value5()),
            primaryKeyColumns(dsl, binding),
            allColumns(dsl, binding)));
    }

    /** Every column of the bound table, in the declaration order {@code sql_column.ordinal} states. */
    private static List<ColumnRef> allColumns(DSLContext dsl, Binding binding) {
        var c = SQL_COLUMN;
        return dsl.select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE)
            .from(c)
            .where(c.SOURCE_NAME.eq(binding.sourceName()),
                c.TABLE_SCHEMA.eq(binding.tableSchema()),
                c.TABLE_NAME.eq(binding.tableName()))
            .orderBy(c.ORDINAL)
            .fetch(StoreNodeTables::columnOf);
    }

    /** The bound table's primary key in key-field order; empty where the table has none. */
    private static List<ColumnRef> primaryKeyColumns(DSLContext dsl, Binding binding) {
        var pk = SQL_PRIMARY_KEY;
        var cc = SQL_CONSTRAINT_COLUMN;
        var c = SQL_COLUMN;
        return dsl.select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE)
            .from(pk)
            .join(cc).on(cc.SOURCE_NAME.eq(pk.SOURCE_NAME), cc.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA),
                cc.TABLE_NAME.eq(pk.TABLE_NAME), cc.CONSTRAINT_NAME.eq(pk.CONSTRAINT_NAME))
            .join(c).on(c.SOURCE_NAME.eq(cc.SOURCE_NAME), c.TABLE_SCHEMA.eq(cc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(cc.TABLE_NAME), c.COLUMN_NAME.eq(cc.COLUMN_NAME))
            .where(pk.SOURCE_NAME.eq(binding.sourceName()),
                pk.TABLE_SCHEMA.eq(binding.tableSchema()),
                pk.TABLE_NAME.eq(binding.tableName()))
            .orderBy(cc.POSITION)
            .fetch(StoreNodeTables::columnOf);
    }

    /**
     * The node type's key columns in key order, resolved against the bound table's own column list so
     * the result carries generated field names rather than the key relation's spellings. The match is
     * case-insensitive on either spelling, which is the convention every crossing between an authored
     * or stated column name and a catalog column already uses. Empty when any key column fails to
     * resolve, which drops the node type: a key list with a hole would decode values into the wrong
     * positions, and there is no partial answer worth handing an emitter.
     */
    private static List<ColumnRef> keyColumns(DSLContext dsl, String graphName, String nodeTypeName,
                                              TableRef table) {
        var k = INTENT_RESOLVED_NODE_KEY_COLUMN;
        var names = dsl.select(k.COLUMN_NAME)
            .from(k)
            .where(k.GRAPH_NAME.eq(graphName), k.TYPE_NAME.eq(nodeTypeName))
            .orderBy(k.POSITION)
            .fetch(r -> r.value1());
        var resolved = new ArrayList<ColumnRef>(names.size());
        for (String name : names) {
            var match = table.allColumns().stream()
                .filter(c -> c.sqlName().equalsIgnoreCase(name)
                    || c.javaName().equalsIgnoreCase(name))
                .findFirst();
            if (match.isEmpty()) {
                return List.of();
            }
            resolved.add(match.get());
        }
        return resolved;
    }

    /**
     * One catalog column row as a {@link ColumnRef}, with its javapoet type decoded array-safely off
     * the captured binding type. The short constructor is deliberately not used and a guard test
     * forbids it: it decodes scalars only, and an array column's captured name is a JVM descriptor
     * that crashes the scalar decode.
     */
    private static ColumnRef columnOf(Record row) {
        String sqlName = row.get(SQL_COLUMN.COLUMN_NAME);
        String javaName = row.get(SQL_COLUMN.JOOQ_NAME);
        String bindingType = row.get(SQL_COLUMN.BINDING_TYPE);
        return new ColumnRef(sqlName, javaName, bindingType, decodeBindingType(bindingType));
    }
}
