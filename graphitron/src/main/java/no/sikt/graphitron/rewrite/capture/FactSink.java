package no.sikt.graphitron.rewrite.capture;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.TableRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates the rows a capture load produces and writes them to the store in one pass.
 *
 * <p>Two jobs beyond buffering. It <b>orders the write</b>: relations flush parents-before-children
 * in a topological order computed from the declared foreign keys, so the walk may emit rows in
 * whatever order the SDL hands them over without tripping a constraint. And it <b>enforces
 * first-wins</b>: {@link #claim} is the gate every element-level natural key passes through, so a
 * key an author can duplicate (a field declared twice, a repeated enum value, a second application
 * of a single-application directive) never reaches the database as a duplicate insert. A primary-key
 * violation is therefore always a capture bug, which is exactly the constraint split the fact-base
 * architecture rests on: author mistakes become detection rows, never constraint violations.
 *
 * <p>The sink is <b>graph-scoped</b>: constructed with the run's graph name, it stamps the
 * {@code graph_name} column on every buffered row whose relation carries the dimension, and
 * namespaces those relations' claim keys by its own graph. The claims are the load-bearing half:
 * they are a hand-maintained mirror of every natural key, and widening the database keys without
 * widening the claim keys would relocate the fusion the partition dimension exists to prevent one
 * layer up, where a two-graph load would first-wins-drop the second graph's types before the
 * widened primary keys could see them. Scoping the sink leaves every SDL-family call site
 * untouched and correct by construction; a future multi-graph load is a second sink.
 */
final class FactSink {

    private final DSLContext dsl;
    private final String graphName;
    private final Map<Table<?>, List<TableRecord<?>>> buckets = new LinkedHashMap<>();
    private final Map<Table<?>, Set<List<Object>>> claimed = new HashMap<>();
    private final Map<Table<?>, Field<String>> graphFields = new HashMap<>();

    FactSink(DSLContext dsl, String graphName) {
        this.dsl = dsl;
        this.graphName = graphName;
    }

    /** The store this sink writes to; capture reads nothing back, but tests do. */
    DSLContext dsl() {
        return dsl;
    }

    /**
     * Registers {@code key} as taken on {@code table}, returning {@code true} the first time and
     * {@code false} for every repeat. Callers write the row only on {@code true}; the losing
     * occurrence is the duplicate-declaration detection's business, not the database's. On a
     * graph-keyed relation the key is namespaced by this sink's graph, mirroring the widened
     * primary key, so a claim can never fuse two graphs' coordinates.
     */
    boolean claim(Table<?> table, Object... key) {
        var full = graphField(table) == null ? Arrays.asList(key) : withGraph(key);
        return claimed.computeIfAbsent(table, t -> new HashSet<>()).add(full);
    }

    /**
     * Buffers one row, stamping the graph dimension on it when its relation carries one. The
     * stamp lives here rather than at the call sites so every SDL-family writer stays untouched
     * and correct by construction.
     */
    void add(TableRecord<?> record) {
        Field<String> graph = graphField(record.getTable());
        if (graph != null) {
            record.set(graph, graphName);
        }
        buckets.computeIfAbsent(record.getTable(), t -> new ArrayList<>()).add(record);
    }

    private Field<String> graphField(Table<?> table) {
        // Not computeIfAbsent: a graph-free relation maps to null, which computeIfAbsent
        // would re-derive on every row of the largest family this sink writes.
        if (!graphFields.containsKey(table)) {
            graphFields.put(table, table.field("GRAPH_NAME", String.class));
        }
        return graphFields.get(table);
    }

    private List<Object> withGraph(Object... key) {
        var full = new ArrayList<>(key.length + 1);
        full.add(graphName);
        full.addAll(Arrays.asList(key));
        return full;
    }

    /**
     * Writes every buffered row, parents first: one prepared statement per relation, bound once per
     * row. The rows arrive as records because that is the surface capture dogfoods, but they are
     * written through a bind batch rather than {@code batchInsert}, which re-derives each record's
     * changed-field set and renders per record. Over the class census the compile classpath
     * produces, that is worth about 1.8x (3.7 s to 2.1 s for 207k rows, warm), and the census is
     * large enough for the difference to be the load's cost rather than a detail.
     *
     * <p>Binding through the JDBC connection directly would be worth about 1.5x again, and nothing
     * short of that reaches it: a transaction, typed bind parameters and disabling execute logging
     * were each measured and each changed nothing. The remainder is jOOQ's per-value binding, not a
     * setting.
     */
    void flush() {
        for (Table<?> table : parentsFirst(buckets.keySet())) {
            var rows = buckets.get(table);
            if (rows.isEmpty()) {
                continue;
            }
            Field<?>[] fields = table.fields();
            var insert = dsl.insertInto(table)
                .columns(fields)
                .values(new Object[fields.length]);
            // The source-keyed families are shared between graphs, so two builds crawling the
            // same new jar concurrently both land: the second writer's identical rows merge away
            // instead of violating the key. Graph-keyed families stay plain inserts, where a
            // duplicate is a capture bug the constraint must surface.
            var batch = sharedFamily(table)
                ? dsl.batch(insert.onDuplicateKeyIgnore())
                : dsl.batch(insert);
            for (TableRecord<?> row : rows) {
                batch = batch.bind(row.intoArray());
            }
            batch.execute();
        }
        buckets.clear();
    }

    private static boolean sharedFamily(Table<?> table) {
        String name = table.getName().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("jvm_") || name.startsWith("sql_");
    }

    /**
     * Topologically sorts {@code tables} so a relation follows every relation it references.
     * Reads the generated foreign keys rather than a hand-kept list, so adding a relation to the
     * DDL cannot leave a write order behind. Self-references are ignored (a row referencing its
     * own relation is satisfied by insertion order within the bucket), and a reference to a
     * relation this load did not touch is simply not a constraint on the order.
     */
    static List<Table<?>> parentsFirst(Set<Table<?>> tables) {
        var sorted = new ArrayList<Table<?>>(tables.size());
        var placed = new HashSet<Table<?>>();
        var visiting = new HashSet<Table<?>>();
        for (Table<?> table : tables) {
            visit(table, tables, placed, visiting, sorted);
        }
        return sorted;
    }

    private static void visit(Table<?> table, Set<Table<?>> present, Set<Table<?>> placed,
                              Set<Table<?>> visiting, List<Table<?>> sorted) {
        if (placed.contains(table) || !visiting.add(table)) {
            return;
        }
        for (var reference : table.getReferences()) {
            Table<?> parent = reference.getKey().getTable();
            if (!parent.equals(table) && present.contains(parent)) {
                visit(parent, present, placed, visiting, sorted);
            }
        }
        visiting.remove(table);
        placed.add(table);
        sorted.add(table);
    }
}
