package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The condition emitter's query: for every field occurrence whose arguments or input fields bind a
 * column, the filter each of them writes, grouped under the field occurrence and the table the
 * columns are on.
 *
 * <p>A term is an argument or input-field occurrence with a {@code COLUMN} binding: where its value is
 * read from (its path of names from the argument), the column it compares, and what decides how it is
 * compared and whether this emitter writes it yet: the declared type's kind, whether it is a node id,
 * whether it is a {@code @lookupKey}, and the origin of its path from the field's table to the column.
 * The field occurrence carries what it produces, if anything, and the {@code @condition} calls applied
 * to the table, its own and its arguments' and input fields', each with what fills its parameters; a
 * term an {@code override: true} suppresses, as written, is left out.
 */
public final class ConditionQuery {

    private ConditionQuery() {}

    /** The column a term compares, where it binds one. */
    public record Column(String tableSqlName, String sqlName, String javaName, String bindingType) {
        public Column {
            Objects.requireNonNull(sqlName, "sqlName");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(bindingType, "bindingType");
        }
    }

    /**
     * One argument or input field's filter: its occurrence, its path of names from the argument, the
     * column it binds where it binds one rather than a node id, its type's kind, whether it is a node
     * id or a {@code @lookupKey}, its path's origin, the steps the path takes from the field's table
     * to the column's, none on an empty path, and the record class of the table the path lands on,
     * which a node id decodes into.
     */
    public record Term(String coordinate, ArgumentPaths.Path path, Column column, String typeKind,
                       boolean nodeId, boolean lookupKey, String origin, List<Step> steps,
                       String landingRecordClass) {
        public Term {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(path, "path");
            steps = List.copyOf(steps);
        }

        /** The names from the argument down to this term. */
        public List<String> names() {
            return path.names();
        }

        /** Whether the value at the end is a list. */
        public boolean list() {
            return path.lists().contains(path.names().size() - 1);
        }

        /** Whether a list is read through above the last step. */
        public boolean throughList() {
            return path.lists().stream().anyMatch(i -> i < path.names().size() - 1);
        }

        /** Whether every step is declared non-null, so the value is never absent. */
        public boolean present() {
            return path.present();
        }
    }

    /** A column pair a step joins on: the column on the table it departs and on the one it reaches. */
    public record Pair(String fromJavaName, String toJavaName) {}

    /** The condition method a step joins by: its class and name, taking the departed and reached tables. */
    public record StepMethod(String className, String methodName) {}

    /**
     * One step a term's path takes: its position, the table it reaches, the column pairs it joins on,
     * the condition method it joins by where it has one, and whether it calls a routine.
     */
    public record Step(int position, LiftQuery.Table to, List<Pair> pairs, StepMethod method, boolean routine) {
        public Step {
            Objects.requireNonNull(to, "to");
            pairs = List.copyOf(pairs);
        }
    }

    /**
     * What fills one parameter of a {@code @condition}'s method: its position, its declared type and how
     * a value is coerced into it, the fill, and for an {@code ARGUMENT} the occurrence it takes, that
     * occurrence's path of names from its argument, its type's kind and whether it is a node id.
     */
    public record Fill(int position, String parameterType, String extraction, String fill, String landing,
                       ArgumentPaths.Path path, String landingKind, boolean landingNodeId) {
        public Fill {
            Objects.requireNonNull(parameterType, "parameterType");
            Objects.requireNonNull(fill, "fill");
        }
    }

    /**
     * One {@code @condition} applied to the row's table: the occurrence it is written on and its kind,
     * the kind of binding the table is to it, the class and method it calls, and what fills each of the
     * method's parameters in position order, {@code UNFILLED} where nothing does.
     */
    public record Call(String coordinate, String occurrenceKind, String bindingKind, String className,
                       String methodName, List<Fill> fills) {
        public Call {
            Objects.requireNonNull(coordinate, "coordinate");
            fills = List.copyOf(fills);
        }
    }

    /**
     * One field occurrence's filters on one table: the occurrence, what it produces where it produces
     * anything, the table, the generated terms in coordinate order but those an {@code override: true}
     * suppresses, the {@code @condition} calls applied to the table in coordinate order, and how many
     * tables the field filters, more than one where its rows are a polymorphic type's participants on
     * tables of their own.
     */
    public record Row(String coordinate, String producerKind, LiftQuery.Table table, List<Term> terms,
                      List<Call> calls, int tables) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(table, "table");
            terms = List.copyOf(terms);
            calls = List.copyOf(calls);
        }
    }

    private record Raw(String field, String coordinate, String producerKind, String tableSqlName,
                       String tableClass, String constantsClass, String tableJavaName, String recordClass,
                       Column column, String typeKind, boolean nodeId, boolean lookupKey, String origin,
                       String targetType, String landingRecordClass) {}

    /** The graph's field occurrences with filters, by coordinate and table. */
    public static List<Row> read(StoreHandle store) {
        var o = GRAPHITRON_OCCURRENCE;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var c = SQL_COLUMN;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var a = GRAPHITRON_ARGUMENT;
        var f = GRAPHITRON_FIELD;
        var ty = GRAPHITRON_TYPE;
        var p = GRAPHITRON_PATH;
        var pr = GRAPHITRON_OCCURRENCE_PRODUCER;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        var graph = store.graphName();
        var paths = ArgumentPaths.read(store);
        var steps = steps(store);
        var calls = calls(store, paths);
        var parents = parents(store);
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        var pn = GRAPHITRON_PATH_NEED_TABLE;
        var jp = GRAPHITRON_JOIN_PATH;
        // A term is read over the table its argument filters, the field's own: its source. Its need
        // starts there, and its column is where that need's join path lands, the need's own landing
        // on an empty path.
        var landingSource = coalesce(jp.TARGET_SOURCE_NAME, pn.LANDING_SOURCE_NAME);
        var landingSchema = coalesce(jp.TARGET_SCHEMA, pn.LANDING_SCHEMA);
        var landingTable = coalesce(jp.TARGET_TABLE, pn.LANDING_TABLE);
        var lt = SQL_TABLE.as("lt");
        var nodeId = field(exists(selectOne().from(n).where(n.GRAPH_NAME.eq(o.GRAPH_NAME), n.COORDINATE.eq(o.COORDINATE))));
        var raws = store.dsl()
            .select(o.FIELD_COORDINATE, o.COORDINATE, pr.KIND, t.TABLE_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN,
                t.JOOQ_NAME, t.RECORD_CLASS_FQN, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, ty.KIND, nodeId,
                field(exists(selectOne().from(d).where(d.GRAPH_NAME.eq(o.GRAPH_NAME),
                    d.COORDINATE.eq(o.ELEMENT_COORDINATE), d.DIRECTIVE_NAME.eq("lookupKey")))),
                p.ORIGIN, pn.TARGET_TYPE, oc.TABLE_NAME, lt.RECORD_CLASS_FQN)
            .from(o)
            .join(st).on(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE))
            .join(t).on(t.SOURCE_NAME.eq(st.TABLE_SOURCE_NAME), t.TABLE_SCHEMA.eq(st.TABLE_SCHEMA),
                t.TABLE_NAME.eq(st.TABLE_NAME))
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .leftJoin(pn).on(pn.GRAPH_NAME.eq(o.GRAPH_NAME), pn.COORDINATE.eq(o.COORDINATE),
                pn.START_SOURCE_NAME.eq(st.TABLE_SOURCE_NAME), pn.START_SCHEMA.eq(st.TABLE_SCHEMA),
                pn.START_TABLE.eq(st.TABLE_NAME))
            .leftJoin(p).on(p.GRAPH_NAME.eq(pn.GRAPH_NAME), p.COORDINATE.eq(pn.COORDINATE),
                p.TARGET_TYPE.eq(pn.TARGET_TYPE))
            .leftJoin(jp).on(jp.GRAPH_NAME.eq(pn.GRAPH_NAME), jp.COORDINATE.eq(pn.COORDINATE),
                jp.TARGET_TYPE.eq(pn.TARGET_TYPE))
            .leftJoin(lt).on(lt.SOURCE_NAME.eq(landingSource), lt.TABLE_SCHEMA.eq(landingSchema),
                lt.TABLE_NAME.eq(landingTable))
            .leftJoin(oc).on(oc.GRAPH_NAME.eq(o.GRAPH_NAME), oc.COORDINATE.eq(o.COORDINATE),
                oc.TABLE_SOURCE_NAME.eq(landingSource), oc.TABLE_SCHEMA.eq(landingSchema),
                oc.TABLE_NAME.eq(landingTable))
            .leftJoin(c).on(c.SOURCE_NAME.eq(oc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(oc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(oc.TABLE_NAME), c.COLUMN_NAME.eq(oc.COLUMN_NAME))
            .leftJoin(a).on(a.GRAPH_NAME.eq(o.GRAPH_NAME), a.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .leftJoin(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .leftJoin(ty).on(ty.GRAPH_NAME.eq(o.GRAPH_NAME), ty.TYPE_NAME.eq(coalesce(a.NAMED_TYPE, f.NAMED_TYPE)))
            .leftJoin(pr).on(pr.GRAPH_NAME.eq(o.GRAPH_NAME), pr.COORDINATE.eq(o.FIELD_COORDINATE))
            .where(o.GRAPH_NAME.eq(graph), o.KIND.in("FIELD_ARGUMENT", "INPUT_FIELD"),
                oc.COORDINATE.isNotNull().or(nodeId))
            .orderBy(o.FIELD_COORDINATE, t.TABLE_NAME, o.COORDINATE)
            .fetch(r -> new Raw(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(),
                r.value7(), r.value8(), r.value9() == null ? null : new Column(r.value17(), r.value9(), r.value10(), r.value11()),
                r.value12(), Boolean.TRUE.equals(r.value13()), Boolean.TRUE.equals(r.value14()), r.value15(),
                r.value16(), r.value18()));
        var rows = new LinkedHashMap<List<String>, List<Raw>>();
        for (var raw : raws) {
            rows.computeIfAbsent(List.of(raw.field(), raw.tableSqlName()), k -> new ArrayList<>()).add(raw);
        }
        // A field with a condition and no generated term is a row of its calls alone.
        for (var key : calls.keySet()) {
            rows.computeIfAbsent(List.of(key.field(), key.table().sqlName()), k -> new ArrayList<>());
        }
        var tables = new LinkedHashMap<List<String>, LiftQuery.Table>();
        calls.keySet().forEach(key -> tables.put(List.of(key.field(), key.table().sqlName()), key.table()));
        var producers = producers(store);
        var out = new ArrayList<Row>();
        rows.forEach((key, group) -> {
            var field = key.get(0);
            var table = group.isEmpty() ? tables.get(key) : new LiftQuery.Table(group.getFirst().tableSqlName(),
                group.getFirst().tableClass(), group.getFirst().constantsClass(), group.getFirst().tableJavaName(),
                group.getFirst().recordClass());
            var applied = calls.entrySet().stream()
                .filter(e -> e.getKey().field().equals(field) && e.getKey().table().sqlName().equals(key.get(1)))
                .flatMap(e -> e.getValue().stream()).toList();
            // An override: true written on a condition suppresses the terms at its occurrence and under it.
            var overriding = applied.stream().filter(CallRow::override).map(written -> written.call().coordinate()).toList();
            var terms = group.stream()
                .filter(raw -> overriding.stream().noneMatch(at -> under(raw.coordinate(), at, parents)))
                .map(raw -> new Term(raw.coordinate(), paths.get(raw.coordinate()),
                    raw.column(), raw.typeKind(), raw.nodeId(), raw.lookupKey(), raw.origin(),
                    steps.getOrDefault(List.of(raw.coordinate(), String.valueOf(raw.targetType())), List.of()),
                    raw.landingRecordClass())).toList();
            out.add(new Row(field, group.isEmpty() ? producers.get(field) : group.getFirst().producerKind(), table,
                terms, applied.stream().map(CallRow::call).toList(),
                (int) rows.keySet().stream().filter(k -> k.getFirst().equals(field)).count()));
        });
        return out;
    }

    /** Whether {@code coordinate} is {@code ancestor} or hangs under it. */
    private static boolean under(String coordinate, String ancestor, Map<String, String> parents) {
        for (var at = coordinate; at != null; at = parents.get(at)) {
            if (at.equals(ancestor)) return true;
        }
        return false;
    }

    /** Each argument and input-field occurrence's parent. */
    private static Map<String, String> parents(StoreHandle store) {
        var o = GRAPHITRON_OCCURRENCE;
        var out = new java.util.HashMap<String, String>();
        store.dsl().select(o.COORDINATE, o.PARENT_COORDINATE).from(o)
            .where(o.GRAPH_NAME.eq(store.graphName()), o.KIND.in("FIELD_ARGUMENT", "INPUT_FIELD"))
            .forEach(r -> out.put(r.value1(), r.value2()));
        return out;
    }

    /** What each field occurrence produces, where it produces anything. */
    private static Map<String, String> producers(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var out = new java.util.HashMap<String, String>();
        store.dsl().select(p.COORDINATE, p.KIND).from(p).where(p.GRAPH_NAME.eq(store.graphName()))
            .forEach(r -> out.put(r.value1(), r.value2()));
        return out;
    }

    private record CallKey(String field, LiftQuery.Table table) {}

    private record CallRow(Call call, boolean override) {}

    /**
     * The graph's {@code @condition} calls, by the field occurrence they filter and the table they are
     * applied to, each with whether it was written {@code override: true}.
     */
    private static Map<CallKey, List<CallRow>> calls(StoreHandle store, Map<String, ArgumentPaths.Path> paths) {
        var c = GRAPHITRON_OCCURRENCE_CONDITION;
        var o = GRAPHITRON_OCCURRENCE;
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var m = GRAPHITRON_OCCURRENCE_CALL_METHOD;
        var cp = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var mp = CODE_METHOD_PARAMETER;
        var lo = GRAPHITRON_OCCURRENCE.as("lo");
        var la = GRAPHITRON_ARGUMENT;
        var lf = GRAPHITRON_FIELD;
        var lt = GRAPHITRON_TYPE;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var bt = GRAPHITRON_OCCURRENCE_BINDING_TABLE;
        // The table a binding names, whichever binding it is.
        var bound = select(bt.GRAPH_NAME, bt.COORDINATE, bt.BOUND, bt.TABLE_SOURCE_NAME, bt.TABLE_SCHEMA, bt.TABLE_NAME)
            .from(bt)
            .union(select(oc.GRAPH_NAME, oc.COORDINATE, oc.BOUND, oc.TABLE_SOURCE_NAME, oc.TABLE_SCHEMA, oc.TABLE_NAME)
                .from(oc))
            .union(select(n.GRAPH_NAME, n.COORDINATE, n.BOUND, n.TABLE_SOURCE_NAME, n.TABLE_SCHEMA, n.TABLE_NAME)
                .from(n))
            .asTable("bound", "graph_name", "coordinate", "bound", "source_name", "schema_name", "table_name");
        var boundGraph = bound.field("graph_name", String.class);
        var boundCoordinate = bound.field("coordinate", String.class);
        var boundBound = bound.field("bound", String.class);
        var fills = multiset(
            select(mp.POSITION, mp.PARAMETER_TYPE, mp.EXTRACTION, coalesce(cp.FILL, org.jooq.impl.DSL.inline("UNFILLED")),
                    cp.LANDING_COORDINATE, lt.KIND,
                    field(exists(selectOne().from(n).where(n.GRAPH_NAME.eq(cp.GRAPH_NAME),
                        n.COORDINATE.eq(cp.LANDING_COORDINATE)))))
                .from(mp)
                .leftJoin(cp).on(cp.GRAPH_NAME.eq(c.GRAPH_NAME), cp.COORDINATE.eq(c.COORDINATE), cp.CALL.eq(c.CALL),
                    cp.POSITION.eq(mp.POSITION))
                .leftJoin(lo).on(lo.GRAPH_NAME.eq(cp.GRAPH_NAME), lo.COORDINATE.eq(cp.LANDING_COORDINATE))
                .leftJoin(la).on(la.GRAPH_NAME.eq(lo.GRAPH_NAME), la.COORDINATE.eq(lo.ELEMENT_COORDINATE))
                .leftJoin(lf).on(lf.GRAPH_NAME.eq(lo.GRAPH_NAME), lf.COORDINATE.eq(lo.ELEMENT_COORDINATE))
                .leftJoin(lt).on(lt.GRAPH_NAME.eq(lo.GRAPH_NAME), lt.TYPE_NAME.eq(coalesce(la.NAMED_TYPE, lf.NAMED_TYPE)))
                .where(mp.SOURCE_NAME.eq(m.METHOD_SOURCE_NAME), mp.CLASS_NAME.eq(m.METHOD_CLASS_NAME),
                    mp.METHOD_NAME.eq(m.METHOD_NAME), mp.DESCRIPTOR.eq(m.METHOD_DESCRIPTOR))
                .orderBy(mp.POSITION));
        var out = new LinkedHashMap<CallKey, List<CallRow>>();
        store.dsl()
            .select(c.COORDINATE, o.KIND, coalesce(o.FIELD_COORDINATE, o.COORDINATE), c.OVERRIDE_WRITTEN, b.KIND,
                m.METHOD_CLASS_NAME, m.METHOD_NAME, t.TABLE_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN, t.JOOQ_NAME,
                t.RECORD_CLASS_FQN, fills)
            .from(c)
            .join(o).on(o.GRAPH_NAME.eq(c.GRAPH_NAME), o.COORDINATE.eq(c.COORDINATE))
            .join(b).on(b.GRAPH_NAME.eq(c.GRAPH_NAME), b.COORDINATE.eq(c.COORDINATE), b.BOUND.eq(c.BOUND))
            .join(m).on(m.GRAPH_NAME.eq(c.GRAPH_NAME), m.COORDINATE.eq(c.COORDINATE), m.CALL.eq(c.CALL))
            .join(bound).on(boundGraph.eq(c.GRAPH_NAME), boundCoordinate.eq(c.COORDINATE), boundBound.eq(c.BOUND))
            .join(t).on(t.SOURCE_NAME.eq(bound.field("source_name", String.class)),
                t.TABLE_SCHEMA.eq(bound.field("schema_name", String.class)),
                t.TABLE_NAME.eq(bound.field("table_name", String.class)))
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(c.GRAPH_NAME.eq(store.graphName()))
            .orderBy(coalesce(o.FIELD_COORDINATE, o.COORDINATE), t.TABLE_NAME, c.COORDINATE)
            .forEach(r -> {
                var table = new LiftQuery.Table(r.value8(), r.value9(), r.value10(), r.value11(), r.value12());
                var filled = r.value13().map(f -> new Fill(f.value1(), f.value2(), f.value3(), f.value4(), f.value5(),
                    f.value5() == null ? null : paths.get(f.value5()), f.value6(), Boolean.TRUE.equals(f.value7())));
                out.computeIfAbsent(new CallKey(r.value3(), table), k -> new ArrayList<>())
                    .add(new CallRow(new Call(r.value1(), r.value2(), r.value5(), r.value6(), r.value7(), filled),
                        Boolean.TRUE.equals(r.value4())));
            });
        return out;
    }

    /** Every join path's steps in order, by the need's coordinate and target type. */
    private static Map<List<String>, List<Step>> steps(StoreHandle store) {
        var js = GRAPHITRON_JOIN_PATH_STEP;
        var jc = GRAPHITRON_JOIN_PATH_STEP_COLUMN;
        var jm = GRAPHITRON_JOIN_PATH_STEP_METHOD;
        var jr = GRAPHITRON_JOIN_PATH_STEP_ROUTINE;
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var fc = SQL_COLUMN.as("fc");
        var tc = SQL_COLUMN.as("tc");
        var pairs = multiset(
            select(fc.JOOQ_NAME, tc.JOOQ_NAME)
                .from(jc)
                .join(fc).on(fc.SOURCE_NAME.eq(jc.FROM_SOURCE_NAME), fc.TABLE_SCHEMA.eq(jc.FROM_SCHEMA),
                    fc.TABLE_NAME.eq(jc.FROM_TABLE), fc.COLUMN_NAME.eq(jc.FROM_COLUMN))
                .join(tc).on(tc.SOURCE_NAME.eq(jc.TO_SOURCE_NAME), tc.TABLE_SCHEMA.eq(jc.TO_SCHEMA),
                    tc.TABLE_NAME.eq(jc.TO_TABLE), tc.COLUMN_NAME.eq(jc.TO_COLUMN))
                .where(jc.GRAPH_NAME.eq(js.GRAPH_NAME), jc.COORDINATE.eq(js.COORDINATE),
                    jc.TARGET_TYPE.eq(js.TARGET_TYPE), jc.POSITION.eq(js.POSITION))
                .orderBy(jc.ORDINAL))
            .convertFrom(r -> r.map(Records.mapping(Pair::new)));
        var routine = field(exists(selectOne().from(jr).where(jr.GRAPH_NAME.eq(js.GRAPH_NAME),
            jr.COORDINATE.eq(js.COORDINATE), jr.TARGET_TYPE.eq(js.TARGET_TYPE), jr.POSITION.eq(js.POSITION))));
        var out = new LinkedHashMap<List<String>, List<Step>>();
        store.dsl()
            .select(js.COORDINATE, js.TARGET_TYPE, js.POSITION, t.TABLE_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN,
                t.JOOQ_NAME, t.RECORD_CLASS_FQN, pairs, jm.METHOD_CLASS_NAME, jm.METHOD_NAME, routine)
            .from(js)
            .join(t).on(t.SOURCE_NAME.eq(js.TO_SOURCE_NAME), t.TABLE_SCHEMA.eq(js.TO_SCHEMA), t.TABLE_NAME.eq(js.TO_TABLE))
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .leftJoin(jm).on(jm.GRAPH_NAME.eq(js.GRAPH_NAME), jm.COORDINATE.eq(js.COORDINATE),
                jm.TARGET_TYPE.eq(js.TARGET_TYPE), jm.POSITION.eq(js.POSITION))
            .where(js.GRAPH_NAME.eq(store.graphName()))
            .orderBy(js.COORDINATE, js.TARGET_TYPE, js.POSITION)
            .forEach(r -> out.computeIfAbsent(List.of(r.value1(), r.value2()), k -> new ArrayList<>())
                .add(new Step(r.value3(), new LiftQuery.Table(r.value4(), r.value5(), r.value6(), r.value7(), r.value8()),
                    r.value9(), r.value10() == null ? null : new StepMethod(r.value10(), r.value11()),
                    Boolean.TRUE.equals(r.value12()))));
        return out;
    }
}
