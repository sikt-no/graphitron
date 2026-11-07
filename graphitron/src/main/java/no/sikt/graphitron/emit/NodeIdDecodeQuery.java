package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;

/**
 * The node id decode emitter's query: how the node id at an argument or input field occurrence is
 * decoded into a jOOQ record of a given class. The record's columns the key loads into, in the node
 * key's order, are the node type's own key where the record is a row of its table, else the
 * referencing columns of the record table's one foreign key to it.
 *
 * <p>Read once per graph: each occurrence's node id binding with the node's type id and key columns,
 * the table each record class is a row of, the foreign key column pairs between tables, and each
 * column's generated name. A decode the store cannot state is refused with the reason: an id bound to
 * no node or to several, a class that is no table's record, a table with no foreign key to the
 * node's or several, a key column no foreign key column references, and a column carrying a
 * converter.
 */
public final class NodeIdDecodeQuery {

    /**
     * A node id decoded into a jOOQ record: the node type as a refused id's message names it, the type
     * id the id must carry, the table the record is a row of as the generated code names it, and the
     * record's columns the key values load into, in key order.
     */
    public record Decode(String nodeTypeName, String typeId, String tablesClass, String tableField,
                         String recordClass, List<String> columnJavaNames) {
        public Decode {
            columnJavaNames = List.copyOf(columnJavaNames);
        }
    }

    /** A decode, or why the node id cannot be decoded into the record. */
    public sealed interface Resolution {
        record Resolved(Decode decode) implements Resolution {}
        record Refused(String reason) implements Resolution {}
    }

    /** A table as the generated code names it. */
    private record Table(String source, String schema, String name, String tablesClass, String tableField) {}

    /** One node id binding: the node's table, its type and type id, and the key columns in key order. */
    private record Binding(String source, String schema, String table, String nodeTypeName, String typeId,
                           List<String> columns) {}

    /** One column's generated name, and whether a converter sits on it. */
    private record Column(String javaName, boolean converted) {}

    private final Map<String, List<Binding>> bindings;
    private final Map<String, Table> tables;
    private final Map<String, Map<String, List<String[]>>> foreignKeys;
    private final Map<String, Column> columns;

    private NodeIdDecodeQuery(Map<String, List<Binding>> bindings, Map<String, Table> tables,
                              Map<String, Map<String, List<String[]>>> foreignKeys, Map<String, Column> columns) {
        this.bindings = bindings;
        this.tables = tables;
        this.foreignKeys = foreignKeys;
        this.columns = columns;
    }

    /** The graph's node id bindings and the catalog facts a decode reads. */
    public static NodeIdDecodeQuery read(StoreHandle store) {
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var gn = GRAPHITRON_NODE;
        var bindingRows = new LinkedHashMap<String, LinkedHashMap<String, Binding>>();
        store.dsl().select(n.COORDINATE, n.TABLE_SOURCE_NAME, n.TABLE_SCHEMA, n.TABLE_NAME,
                coalesce(gn.TYPE_ID, n.NODE_TYPE_NAME), n.COLUMN_NAME, n.NODE_TYPE_NAME)
            .from(n)
            .leftJoin(gn).on(gn.GRAPH_NAME.eq(n.GRAPH_NAME), gn.TYPE_NAME.eq(n.NODE_TYPE_NAME))
            .where(n.GRAPH_NAME.eq(store.graphName()))
            .orderBy(n.COORDINATE, n.TABLE_SCHEMA, n.TABLE_NAME, n.POSITION)
            .forEach(r -> bindingRows.computeIfAbsent(r.value1(), k -> new LinkedHashMap<>())
                .computeIfAbsent(key(r.value2(), r.value3(), r.value4()),
                    k -> new Binding(r.value2(), r.value3(), r.value4(), r.value7(), r.value5(), new ArrayList<>()))
                .columns().add(r.value6()));
        var bindings = new HashMap<String, List<Binding>>();
        bindingRows.forEach((coordinate, byTable) -> bindings.put(coordinate, List.copyOf(byTable.values())));

        var t = SQL_TABLE;
        var sc = SQL_SCHEMA;
        var tables = new HashMap<String, Table>();
        store.dsl().select(t.RECORD_CLASS_FQN, t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME,
                sc.TABLES_CLASS_FQN, t.JOOQ_NAME)
            .from(t)
            .join(sc).on(sc.SOURCE_NAME.eq(t.SOURCE_NAME), sc.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .forEach(r -> tables.putIfAbsent(r.value1(),
                new Table(r.value2(), r.value3(), r.value4(), r.value5(), r.value6())));

        // Referencing column by referenced column, per constraint, per ordered pair of tables.
        var rc = SQL_REFERENTIAL_CONSTRAINT_COLUMN;
        var cc = SQL_CONSTRAINT_COLUMN.as("cc");
        var rk = SQL_CONSTRAINT_COLUMN.as("rk");
        var foreignKeys = new HashMap<String, Map<String, List<String[]>>>();
        store.dsl().select(rc.SOURCE_NAME, rc.TABLE_SCHEMA, rc.TABLE_NAME, rc.REFERENCED_SOURCE_NAME,
                rc.REFERENCED_SCHEMA, rc.REFERENCED_TABLE, rc.CONSTRAINT_NAME, cc.COLUMN_NAME, rk.COLUMN_NAME)
            .from(rc)
            .join(cc).on(cc.SOURCE_NAME.eq(rc.SOURCE_NAME), cc.TABLE_SCHEMA.eq(rc.TABLE_SCHEMA),
                cc.TABLE_NAME.eq(rc.TABLE_NAME), cc.CONSTRAINT_NAME.eq(rc.CONSTRAINT_NAME),
                cc.POSITION.eq(rc.POSITION))
            .join(rk).on(rk.SOURCE_NAME.eq(rc.REFERENCED_SOURCE_NAME), rk.TABLE_SCHEMA.eq(rc.REFERENCED_SCHEMA),
                rk.TABLE_NAME.eq(rc.REFERENCED_TABLE), rk.CONSTRAINT_NAME.eq(rc.REFERENCED_CONSTRAINT_NAME),
                rk.POSITION.eq(rc.REFERENCED_POSITION))
            .forEach(r -> foreignKeys
                .computeIfAbsent(key(r.value1(), r.value2(), r.value3()) + "->" + key(r.value4(), r.value5(), r.value6()),
                    k -> new TreeMap<>())
                .computeIfAbsent(r.value7(), k -> new ArrayList<>())
                .add(new String[] {r.value8(), r.value9()}));

        var c = SQL_COLUMN;
        var columns = new HashMap<String, Column>();
        store.dsl().select(c.SOURCE_NAME, c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME, c.JOOQ_NAME,
                c.BINDING_TYPE, c.DATABASE_TYPE)
            .from(c)
            .forEach(r -> columns.put(key(r.value1(), r.value2(), r.value3()) + "." + r.value4(),
                new Column(r.value5(), !r.value6().equals(r.value7()))));
        return new NodeIdDecodeQuery(bindings, tables, foreignKeys, columns);
    }

    private static String key(String source, String schema, String table) {
        return source + "/" + schema + "." + table;
    }

    /** The decode of the node id at {@code coordinate} into a record of the node type's own table. */
    public Resolution resolveOwn(String coordinate) {
        var bound = bindings.getOrDefault(coordinate, List.of());
        if (bound.size() != 1) {
            return refusedFor(bound);
        }
        var binding = bound.getFirst();
        var nodeKey = key(binding.source(), binding.schema(), binding.table());
        return tables.entrySet().stream()
            .filter(e -> key(e.getValue().source(), e.getValue().schema(), e.getValue().name()).equals(nodeKey))
            .map(e -> resolve(coordinate, e.getKey()))
            .findFirst()
            .orElse(new Resolution.Refused("the node type's table " + binding.table() + " has no record class"));
    }

    /** The decode of the node id at {@code coordinate} into a record of {@code recordClass}. */
    public Resolution resolve(String coordinate, String recordClass) {
        var bound = bindings.getOrDefault(coordinate, List.of());
        if (bound.size() != 1) {
            return refusedFor(bound);
        }
        var binding = bound.getFirst();
        var target = tables.get(recordClass);
        if (target == null) {
            return new Resolution.Refused(
                "member type " + recordClass + " is not a jOOQ record a node id can decode into");
        }
        var targetKey = key(target.source(), target.schema(), target.name());
        var nodeKey = key(binding.source(), binding.schema(), binding.table());
        List<String> targetColumns;
        if (targetKey.equals(nodeKey)) {
            targetColumns = binding.columns();
        } else {
            var byConstraint = foreignKeys.getOrDefault(targetKey + "->" + nodeKey, Map.of());
            if (byConstraint.size() != 1) {
                return new Resolution.Refused(byConstraint.isEmpty()
                    ? target.name() + " has no foreign key to " + binding.table()
                    : target.name() + " has " + byConstraint.size() + " foreign keys to " + binding.table()
                        + ", so nothing says which the id fills");
            }
            var pairs = byConstraint.values().iterator().next();
            targetColumns = new ArrayList<>();
            for (var keyColumn : binding.columns()) {
                var referencing = pairs.stream().filter(pair -> pair[1].equals(keyColumn))
                    .map(pair -> pair[0]).findFirst();
                if (referencing.isEmpty()) {
                    return new Resolution.Refused(target.name() + "'s foreign key to " + binding.table()
                        + " does not reference key column " + keyColumn);
                }
                targetColumns.add(referencing.get());
            }
        }
        var javaNames = new ArrayList<String>();
        for (var column : targetColumns) {
            var known = columns.get(targetKey + "." + column);
            if (known == null) {
                return new Resolution.Refused("column " + column + " of " + target.name() + " is not in the catalog");
            }
            if (known.converted()) {
                return new Resolution.Refused(
                    "column " + column + " carries a converter, which a node id decode does not build yet");
            }
            javaNames.add(known.javaName());
        }
        return new Resolution.Resolved(
            new Decode(binding.nodeTypeName(), binding.typeId(), target.tablesClass(), target.tableField(),
                recordClass, javaNames));
    }

    private static Resolution.Refused refusedFor(List<Binding> bound) {
        return new Resolution.Refused(bound.isEmpty() ? "no node type is bound to the id"
            : "the id is one of several node types, which is not built here yet");
    }
}
