package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Field;
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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_READER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_RESULT_PIVOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.when;
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

    /** A column pair a step joins on, each by the generated field it is read through. */
    public record Pair(String fromJavaName, String toJavaName) {}

    /**
     * One step of a join path, in path order: the table it steps from, the table it reaches with
     * the constants class and field that reach it, and the column pairs it joins on, none for a
     * step joining otherwise.
     */
    public record Step(int position, TableKey from, TableKey to, String constantsClassName, String javaFieldName,
                       List<Pair> pairs) {
        public Step {
            pairs = List.copyOf(pairs);
        }
    }

    /**
     * One occurrence of the unit: its coordinate, the field's name at each step from the unit's
     * type down to it, whether the field is non-null and how many lists wrap it, the columns it
     * binds, the key columns its node id packs in key order, the origin of each path that meets
     * one of its needs, the side its field is fetched on with the producer's kind or the reader's
     * way of reading, the type it returns and whether that type is table-bound or a pivot's result,
     * whether it is a nesting field, its fields read off its parent's row, its join path's steps
     * in order, the type owning the name a projection writes for it where that is not shared
     * (an interface a discriminated participant's field is declared by, else the participant), and
     * the occurrences nested beneath it in the order their fields are declared.
     */
    public record Arm(String coordinate, List<String> chain, boolean nonNull, int listDepth,
                      List<Column> columns, List<Column> nodeKeys, List<String> pathOrigins, String side,
                      String producerKind, String reads, String targetType, boolean targetTable,
                      boolean pivot, boolean nesting, List<Step> steps, String aliasOwner, List<Column> liftColumns,
                      List<Arm> nested) {
        public Arm {
            Objects.requireNonNull(coordinate, "coordinate");
            chain = List.copyOf(chain);
            columns = List.copyOf(columns);
            nodeKeys = List.copyOf(nodeKeys);
            pathOrigins = List.copyOf(pathOrigins);
            steps = List.copyOf(steps);
            liftColumns = List.copyOf(liftColumns);
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
                exists(selectOne().from(ns).where(ns.GRAPH_NAME.eq(o.GRAPH_NAME), ns.COORDINATE.eq(o.COORDINATE))),
                steps(o.GRAPH_NAME, o.COORDINATE),
                aliasOwner(f.GRAPH_NAME, f.TYPE_NAME, f.FIELD_NAME),
                liftColumns(o.GRAPH_NAME, o.COORDINATE))
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
                Boolean.TRUE.equals(r.value17()), r.value18(), r.value19(), r.value20())));

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
                    fact.targetType(), fact.targetTable(), fact.pivot(), fact.nesting(), fact.steps(), fact.aliasOwner(),
                    fact.liftColumns(),
                    arms(children.getOrDefault(fact.coordinate(), List.of()), chain, children));
            })
            .toList();
    }

    /** The occurrence's join path steps in path order, each with the column pairs it joins on. */
    private static Field<List<Step>> steps(Field<String> graph, Field<String> coordinate) {
        var js = GRAPHITRON_JOIN_PATH_STEP;
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var st = SQL_TABLE;
        var ss = SQL_SCHEMA;
        var fc = SQL_COLUMN.as("fc");
        var tc = SQL_COLUMN.as("tc");
        return multiset(
            select(js.POSITION, js.FROM_SOURCE_NAME, js.FROM_SCHEMA, js.FROM_TABLE, js.TO_SOURCE_NAME, js.TO_SCHEMA,
                    js.TO_TABLE, ss.TABLES_CLASS_FQN, st.JOOQ_NAME,
                    multiset(select(fc.JOOQ_NAME, tc.JOOQ_NAME)
                        .from(sc)
                        .join(fc).on(fc.SOURCE_NAME.eq(sc.FROM_SOURCE_NAME), fc.TABLE_SCHEMA.eq(sc.FROM_SCHEMA),
                            fc.TABLE_NAME.eq(sc.FROM_TABLE), fc.COLUMN_NAME.eq(sc.FROM_COLUMN))
                        .join(tc).on(tc.SOURCE_NAME.eq(sc.TO_SOURCE_NAME), tc.TABLE_SCHEMA.eq(sc.TO_SCHEMA),
                            tc.TABLE_NAME.eq(sc.TO_TABLE), tc.COLUMN_NAME.eq(sc.TO_COLUMN))
                        .where(sc.GRAPH_NAME.eq(js.GRAPH_NAME), sc.COORDINATE.eq(js.COORDINATE),
                            sc.TARGET_TYPE.eq(js.TARGET_TYPE), sc.POSITION.eq(js.POSITION))
                        .orderBy(sc.ORDINAL))
                        .convertFrom(rs -> rs.map(Records.mapping(Pair::new))))
                .from(js)
                .join(st).on(st.SOURCE_NAME.eq(js.TO_SOURCE_NAME), st.TABLE_SCHEMA.eq(js.TO_SCHEMA),
                    st.TABLE_NAME.eq(js.TO_TABLE))
                .join(ss).on(ss.SOURCE_NAME.eq(st.SOURCE_NAME), ss.TABLE_SCHEMA.eq(st.TABLE_SCHEMA))
                .where(js.GRAPH_NAME.eq(graph), js.COORDINATE.eq(coordinate))
                .orderBy(js.POSITION))
            .convertFrom(rs -> rs.map(r -> new Step(r.value1(),
                new TableKey(r.value2(), r.value3(), r.value4()), new TableKey(r.value5(), r.value6(), r.value7()),
                r.value8(), r.value9(), r.value10())));
    }

    /**
     * The type owning the name a projection writes for the field, null where it is shared: for a
     * participant a discriminated interface tells apart by a column, the interface declaring the
     * field where one does, else the participant itself, so participants selected into one row
     * write an interface's field under one name and their own under names of their own.
     */
    private static Field<String> aliasOwner(Field<String> graph, Field<String> type, Field<String> field) {
        var pa = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        var po = GRAPHITRON_OCCURRENCE.as("po");
        var pf = GRAPHITRON_FIELD.as("pf");
        var pft = GRAPHITRON_FIELD_TARGET.as("pft");
        var declared = GRAPHITRON_FIELD.as("declared");
        var discriminated = exists(selectOne().from(pa)
            .where(pa.GRAPH_NAME.eq(graph), pa.PARTICIPANT_TYPE.eq(type), pa.RECOGNIZED_BY.eq("DISCRIMINATOR")));
        var declaringInterface = select(min(pft.TARGET_TYPE_NAME))
            .from(pa)
            .join(po).on(po.GRAPH_NAME.eq(pa.GRAPH_NAME), po.COORDINATE.eq(pa.COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(po.GRAPH_NAME), pf.COORDINATE.eq(po.ELEMENT_COORDINATE))
            .join(pft).on(pft.GRAPH_NAME.eq(pf.GRAPH_NAME), pft.TYPE_NAME.eq(pf.TYPE_NAME),
                pft.FIELD_NAME.eq(pf.FIELD_NAME))
            .join(declared).on(declared.GRAPH_NAME.eq(pft.GRAPH_NAME), declared.TYPE_NAME.eq(pft.TARGET_TYPE_NAME),
                declared.FIELD_NAME.eq(field))
            .where(pa.GRAPH_NAME.eq(graph), pa.PARTICIPANT_TYPE.eq(type), pa.RECOGNIZED_BY.eq("DISCRIMINATOR"));
        return when(discriminated, coalesce(field(declaringInterface), type));
    }

    /**
     * The columns of the parent's row a child producer's lift is keyed on, in key order: the lift's
     * key columns where the key is the source's, else the source columns its one hop pairs with
     * them, the source's row holding the values the target's key is matched by.
     */
    private static Field<List<Column>> liftColumns(Field<String> graph, Field<String> coordinate) {
        var l = GRAPHITRON_OCCURRENCE_PRODUCER_LIFT;
        var kc = SQL_CONSTRAINT_COLUMN;
        var sc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var src = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        var col = SQL_COLUMN;
        var onSource = l.KEY_TABLE.eq(src.TABLE_NAME).and(l.KEY_SCHEMA.eq(src.TABLE_SCHEMA));
        Field<String> table = when(onSource, kc.TABLE_NAME).otherwise(sc.FROM_TABLE);
        Field<String> schema = when(onSource, kc.TABLE_SCHEMA).otherwise(sc.FROM_SCHEMA);
        Field<String> source = when(onSource, kc.SOURCE_NAME).otherwise(sc.FROM_SOURCE_NAME);
        Field<String> column = when(onSource, kc.COLUMN_NAME).otherwise(sc.FROM_COLUMN);
        return multiset(
            select(col.SOURCE_NAME, col.TABLE_SCHEMA, col.TABLE_NAME, col.COLUMN_NAME, col.JOOQ_NAME)
                .from(l)
                .join(src).on(src.GRAPH_NAME.eq(l.GRAPH_NAME), src.COORDINATE.eq(l.COORDINATE))
                .join(kc).on(kc.SOURCE_NAME.eq(l.KEY_SOURCE_NAME), kc.TABLE_SCHEMA.eq(l.KEY_SCHEMA),
                    kc.TABLE_NAME.eq(l.KEY_TABLE), kc.CONSTRAINT_NAME.eq(l.KEY_CONSTRAINT))
                .leftJoin(sc).on(sc.GRAPH_NAME.eq(l.GRAPH_NAME), sc.COORDINATE.eq(l.COORDINATE), sc.POSITION.eq(0),
                    sc.TO_TABLE.eq(kc.TABLE_NAME), sc.TO_SCHEMA.eq(kc.TABLE_SCHEMA), sc.TO_COLUMN.eq(kc.COLUMN_NAME))
                .join(col).on(col.SOURCE_NAME.eq(source), col.TABLE_SCHEMA.eq(schema), col.TABLE_NAME.eq(table),
                    col.COLUMN_NAME.eq(column))
                .where(l.GRAPH_NAME.eq(graph), l.COORDINATE.eq(coordinate), l.READ.eq("COLUMNS"))
                .orderBy(kc.POSITION))
            .convertFrom(rs -> rs.map(Records.mapping(ProjectionQuery::column)));
    }

    private static Column column(String source, String schema, String table, String column, String javaName) {
        return new Column(new TableKey(source, schema, table), column, javaName);
    }

    /** One occurrence's facts as read, before the unit's tree is built. */
    private record Fact(String coordinate, String parent, String name, String declaringType, boolean nonNull,
                        int listDepth, int ordinal, List<Column> columns, List<Column> nodeKeys,
                        List<String> pathOrigins, String side, String producerKind, String reads,
                        String targetType, boolean targetTable, boolean pivot, boolean nesting,
                        List<Step> steps, String aliasOwner, List<Column> liftColumns) {}
}
