package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FETCHER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NESTING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_READER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_PIVOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The projection unit emitter's query: each table-bound type with the occurrences its fields
 * start, and what the store states about each, which {@link ProjectionEmitter} renders as the
 * type's {@code $project} or names the reason it cannot yet.
 *
 * <p>A type's unit is the chains its fields start: an occurrence's chain restarts at a table-bound
 * type, so the occurrences a type's unit holds are those with no parent whose field the type
 * declares and, beneath each, the occurrences of the chain it starts. Each occurrence carries the
 * facts the store holds about it and decides nothing: the columns it binds and the tables they are
 * on, the key columns its node id packs, the origin of each path meeting one of its needs, how its
 * field is fetched and read, and the target its field returns.
 */
public final class ProjectionQuery {

    private ProjectionQuery() {}

    /** A table, by its catalog partition, schema and name. */
    public record TableKey(String source, String schema, String name) {}

    /** One column an occurrence binds: the table it is on, its SQL name and the generated field it is read through. */
    public record Column(TableKey table, String sqlName, String javaName) {
        public Column {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(sqlName, "sqlName");
            Objects.requireNonNull(javaName, "javaName");
        }
    }

    /**
     * One occurrence of the unit: its coordinate, the field's name at each step from the unit's
     * type down to it, whether the field is non-null and how many lists wrap it, the columns it
     * binds, the key columns its node id packs in key order, the origin of each path that meets
     * one of its needs, the side its field is fetched on with the producer's kind or the reader's
     * way of reading, the type it returns and whether that type is table-bound or a pivot's result,
     * whether it is a nesting field, its fields read off its parent's row, and the occurrences
     * nested beneath it in the order their fields are declared.
     */
    public record Arm(String coordinate, List<String> chain, boolean nonNull, int listDepth,
                      List<Column> columns, List<Column> nodeKeys, List<String> pathOrigins, String side,
                      String producerKind, String reads, String targetType, boolean targetTable,
                      boolean pivot, boolean nesting, List<Arm> nested) {
        public Arm {
            Objects.requireNonNull(coordinate, "coordinate");
            chain = List.copyOf(chain);
            columns = List.copyOf(columns);
            nodeKeys = List.copyOf(nodeKeys);
            pathOrigins = List.copyOf(pathOrigins);
            nested = List.copyOf(nested);
        }

        /** The field's name, the last step of its chain. */
        public String field() {
            return chain.get(chain.size() - 1);
        }
    }

    /** One table-bound type's unit: the type, the table its rows are, and its own fields' occurrences. */
    public record Unit(String type, LiftQuery.Table table, TableKey key, List<Arm> arms) {
        public Unit {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(key, "key");
            arms = List.copyOf(arms);
        }
    }

    /** The graph's table-bound types' units, by type name. */
    public static Map<String, Unit> read(StoreHandle store) {
        var tt = GRAPHITRON_TABLETYPE;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var tables = new LinkedHashMap<String, LiftQuery.Table>();
        var tableKeys = new LinkedHashMap<String, TableKey>();
        store.dsl()
            .select(tt.TYPE_NAME, t.TABLE_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN, t.JOOQ_NAME, t.RECORD_CLASS_FQN,
                t.SOURCE_NAME, t.TABLE_SCHEMA)
            .from(tt)
            .join(t).on(t.SOURCE_NAME.eq(tt.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(tt.TABLE_SCHEMA),
                t.TABLE_NAME.eq(tt.TABLE_NAME))
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(tt.GRAPH_NAME.eq(store.graphName()))
            .orderBy(tt.TYPE_NAME)
            .forEach(r -> {
                tables.put(r.value1(), new LiftQuery.Table(r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
                tableKeys.put(r.value1(), new TableKey(r.value7(), r.value8(), r.value2()));
            });

        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var ft = GRAPHITRON_FIELD_TARGET;
        var fe = GRAPHITRON_FETCHER;
        var pr = GRAPHITRON_PRODUCER;
        var rd = GRAPHITRON_READER;
        var target = GRAPHITRON_TABLETYPE.as("target");
        var pv = GRAPHITRON_RESULT_PIVOT;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var on = GRAPHITRON_OCCURRENCE_NODE_ID;
        var ns = GRAPHITRON_OCCURRENCE_NESTING;
        var p = GRAPHITRON_PATH;
        var c = SQL_COLUMN;
        var facts = new LinkedHashMap<String, Fact>();
        store.dsl()
            .select(o.COORDINATE, o.PARENT_COORDINATE, o.NAME, f.TYPE_NAME, f.NON_NULL, f.LIST_DEPTH, f.ORDINAL,
                multiset(select(oc.TABLE_SOURCE_NAME, oc.TABLE_SCHEMA, oc.TABLE_NAME, c.COLUMN_NAME, c.JOOQ_NAME)
                    .from(oc)
                    .join(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                        c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
                    .where(oc.GRAPH_NAME.eq(o.GRAPH_NAME), oc.COORDINATE.eq(o.COORDINATE)))
                    .convertFrom(rs -> rs.map(Records.mapping(ProjectionQuery::column))),
                multiset(select(on.TABLE_SOURCE_NAME, on.TABLE_SCHEMA, on.TABLE_NAME, c.COLUMN_NAME, c.JOOQ_NAME)
                    .from(on)
                    .join(c).on(c.SOURCE_NAME.eq(on.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(on.TABLE_SCHEMA),
                        c.TABLE_NAME.eq(on.TABLE_NAME), c.COLUMN_NAME.eq(on.COLUMN_NAME))
                    .where(on.GRAPH_NAME.eq(o.GRAPH_NAME), on.COORDINATE.eq(o.COORDINATE))
                    .orderBy(on.POSITION))
                    .convertFrom(rs -> rs.map(Records.mapping(ProjectionQuery::column))),
                multiset(select(p.ORIGIN).from(p)
                    .where(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.COORDINATE))
                    .orderBy(p.TARGET_TYPE))
                    .convertFrom(rs -> rs.map(rec -> rec.value1())),
                fe.SIDE, pr.KIND, rd.READS, ft.TARGET_TYPE_NAME, target.TYPE_NAME,
                exists(selectOne().from(pv).where(pv.GRAPH_NAME.eq(f.GRAPH_NAME),
                    pv.PIVOT_TYPE_NAME.eq(f.TYPE_NAME), pv.PIVOT_FIELD_NAME.eq(f.FIELD_NAME))),
                exists(selectOne().from(ns).where(ns.GRAPH_NAME.eq(o.GRAPH_NAME), ns.COORDINATE.eq(o.COORDINATE))))
            .from(o)
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .leftJoin(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            .leftJoin(target).on(target.GRAPH_NAME.eq(ft.GRAPH_NAME), target.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
            .leftJoin(fe).on(fe.GRAPH_NAME.eq(f.GRAPH_NAME), fe.TYPE_NAME.eq(f.TYPE_NAME),
                fe.FIELD_NAME.eq(f.FIELD_NAME))
            .leftJoin(pr).on(pr.GRAPH_NAME.eq(f.GRAPH_NAME), pr.TYPE_NAME.eq(f.TYPE_NAME),
                pr.FIELD_NAME.eq(f.FIELD_NAME))
            .leftJoin(rd).on(rd.GRAPH_NAME.eq(f.GRAPH_NAME), rd.TYPE_NAME.eq(f.TYPE_NAME),
                rd.FIELD_NAME.eq(f.FIELD_NAME))
            .where(o.GRAPH_NAME.eq(store.graphName()), o.KIND.eq("FIELD"))
            .forEach(r -> facts.put(r.value1(), new Fact(r.value1(), r.value2(), r.value3(), r.value4(),
                Boolean.TRUE.equals(r.value5()), r.value6() == null ? 0 : r.value6(),
                r.value7() == null ? 0 : r.value7(), r.value8(), r.value9(), r.value10(), r.value11(),
                r.value12(), r.value13(), r.value14(), r.value15() != null, Boolean.TRUE.equals(r.value16()),
                Boolean.TRUE.equals(r.value17()))));

        // A chain starts at an occurrence with no parent; the type declaring its field is the unit
        // it belongs to.
        var children = new LinkedHashMap<String, List<Fact>>();
        var starts = new LinkedHashMap<String, List<Fact>>();
        for (var fact : facts.values()) {
            if (fact.parent() != null) {
                children.computeIfAbsent(fact.parent(), k -> new ArrayList<>()).add(fact);
            } else {
                starts.computeIfAbsent(fact.declaringType(), k -> new ArrayList<>()).add(fact);
            }
        }
        var units = new LinkedHashMap<String, Unit>();
        for (var entry : tables.entrySet()) {
            String type = entry.getKey();
            units.put(type, new Unit(type, entry.getValue(), tableKeys.get(type),
                arms(starts.getOrDefault(type, List.of()), List.of(), children)));
        }
        return units;
    }

    /** The arms of {@code facts}, siblings in declaration order, each with its nested arms beneath. */
    private static List<Arm> arms(List<Fact> facts, List<String> above, Map<String, List<Fact>> children) {
        return facts.stream()
            .sorted(Comparator.comparingInt(Fact::ordinal).thenComparing(Fact::coordinate))
            .map(fact -> {
                var chain = new ArrayList<>(above);
                chain.add(fact.name());
                return new Arm(fact.coordinate(), chain, fact.nonNull(), fact.listDepth(), fact.columns(),
                    fact.nodeKeys(), fact.pathOrigins(), fact.side(), fact.producerKind(), fact.reads(),
                    fact.targetType(), fact.targetTable(), fact.pivot(), fact.nesting(),
                    arms(children.getOrDefault(fact.coordinate(), List.of()), chain, children));
            })
            .toList();
    }

    private static Column column(String source, String schema, String table, String column, String javaName) {
        return new Column(new TableKey(source, schema, table), column, javaName);
    }

    /** One occurrence's facts as read, before the unit's tree is built. */
    private record Fact(String coordinate, String parent, String name, String declaringType, boolean nonNull,
                        int listDepth, int ordinal, List<Column> columns, List<Column> nodeKeys,
                        List<String> pathOrigins, String side, String producerKind, String reads,
                        String targetType, boolean targetTable, boolean pivot, boolean nesting) {}
}
