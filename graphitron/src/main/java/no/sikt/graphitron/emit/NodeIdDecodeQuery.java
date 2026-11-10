package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;

/**
 * The node id decode emitter's query: how the node id at an argument or input field occurrence is
 * decoded into a jOOQ record of a given class. The record's columns the key loads into, in the node
 * key's order, are traced back through the occurrence's join path where it has one from the record's
 * table to the node's: each key column through each step's column pairs, last step first, to the
 * column of the record that carries it, so a written path of any length decodes where identity
 * passes along it. With no such path, a record of the node's own table loads the key itself.
 *
 * <p>Read once per graph: each occurrence's node id binding with the node's type id and key columns,
 * the table each record class is a row of, each occurrence's join paths with their column pairs, and
 * each column's generated name. A decode the store cannot state is refused with the reason: an id
 * bound to no node or to several, a class that is no table's record, a record of another table with
 * no path from it to the node's, a key column the path does not carry back to the record, and a
 * column carrying a converter.
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

    /** One column pair of a step: the column on the side the step leaves and on the side it reaches. */
    private record Pair(String from, String to) {}

    /** One step of a join path: the tables it leaves and reaches, and its column pairs. */
    private record Step(String from, String to, List<Pair> pairs) {}

    private final Map<String, List<Binding>> bindings;
    private final Map<String, Table> tables;
    private final Map<String, List<List<Step>>> paths;
    private final Map<String, Column> columns;

    private NodeIdDecodeQuery(Map<String, List<Binding>> bindings, Map<String, Table> tables,
                              Map<String, List<List<Step>>> paths, Map<String, Column> columns) {
        this.bindings = bindings;
        this.tables = tables;
        this.paths = paths;
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

        // Each met join path of an occurrence carrying a node id, its steps in order with their pairs.
        var j = GRAPHITRON_JOIN_PATH;
        var st = GRAPHITRON_JOIN_PATH_STEP;
        var sc2 = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var stepsByPath = new TreeMap<String, TreeMap<Integer, Step>>();
        store.dsl().select(st.COORDINATE, st.TARGET_TYPE, st.POSITION, st.FROM_SOURCE_NAME, st.FROM_SCHEMA,
                st.FROM_TABLE, st.TO_SOURCE_NAME, st.TO_SCHEMA, st.TO_TABLE, sc2.FROM_COLUMN, sc2.TO_COLUMN)
            .from(st)
            .join(j).on(j.GRAPH_NAME.eq(st.GRAPH_NAME), j.COORDINATE.eq(st.COORDINATE),
                j.TARGET_TYPE.eq(st.TARGET_TYPE))
            .leftJoin(sc2).on(sc2.GRAPH_NAME.eq(st.GRAPH_NAME), sc2.COORDINATE.eq(st.COORDINATE),
                sc2.TARGET_TYPE.eq(st.TARGET_TYPE), sc2.POSITION.eq(st.POSITION))
            .where(st.GRAPH_NAME.eq(store.graphName()), st.COORDINATE.in(bindings.keySet()))
            .orderBy(st.COORDINATE, st.TARGET_TYPE, st.POSITION, sc2.ORDINAL)
            .forEach(r -> {
                var step = stepsByPath.computeIfAbsent(r.value1() + " " + r.value2(), k -> new TreeMap<>())
                    .computeIfAbsent(r.value3(), k -> new Step(key(r.value4(), r.value5(), r.value6()),
                        key(r.value7(), r.value8(), r.value9()), new ArrayList<>()));
                if (r.value10() != null) {
                    step.pairs().add(new Pair(r.value10(), r.value11()));
                }
            });
        var paths = new HashMap<String, List<List<Step>>>();
        stepsByPath.forEach((path, steps) -> paths
            .computeIfAbsent(path.substring(0, path.indexOf(' ')), k -> new ArrayList<>())
            .add(List.copyOf(steps.values())));

        var c = SQL_COLUMN;
        var columns = new HashMap<String, Column>();
        store.dsl().select(c.SOURCE_NAME, c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME, c.JOOQ_NAME,
                c.BINDING_TYPE, c.DATABASE_TYPE)
            .from(c)
            .forEach(r -> columns.put(key(r.value1(), r.value2(), r.value3()) + "." + r.value4(),
                new Column(r.value5(), !r.value6().equals(r.value7()))));
        return new NodeIdDecodeQuery(bindings, tables, paths, columns);
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
        var path = paths.getOrDefault(coordinate, List.of()).stream()
            .filter(steps -> steps.getFirst().from().equals(targetKey) && steps.getLast().to().equals(nodeKey))
            .findFirst();
        List<String> targetColumns;
        if (path.isPresent()) {
            targetColumns = new ArrayList<>();
            for (var keyColumn : binding.columns()) {
                var carried = carriedBack(path.get(), keyColumn);
                if (carried == null) {
                    return new Resolution.Refused("the path from " + target.name() + " to " + binding.table()
                        + " does not carry key column " + keyColumn + " back to a column of " + target.name());
                }
                targetColumns.add(carried);
            }
        } else if (targetKey.equals(nodeKey)) {
            targetColumns = binding.columns();
        } else {
            return new Resolution.Refused("no path from " + target.name() + " to " + binding.table()
                + " is stated at the id, so nothing says which columns it fills");
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

    /**
     * The column of the path's first table that carries {@code keyColumn} of its last, each step's
     * pairs followed from the side it reaches to the side it leaves, last step first; null where a
     * step has no pair reaching the column.
     */
    private static String carriedBack(List<Step> steps, String keyColumn) {
        var column = keyColumn;
        for (int position = steps.size() - 1; position >= 0; position--) {
            var reached = column;
            var pair = steps.get(position).pairs().stream().filter(p -> p.to().equals(reached)).findFirst();
            if (pair.isEmpty()) {
                return null;
            }
            column = pair.get().from();
        }
        return column;
    }

    private static Resolution.Refused refusedFor(List<Binding> bound) {
        return new Resolution.Refused(bound.isEmpty() ? "no node type is bound to the id"
            : "the id is one of several node types, which is not built here yet");
    }
}
