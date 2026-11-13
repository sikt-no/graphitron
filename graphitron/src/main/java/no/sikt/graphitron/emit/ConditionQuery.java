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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LIST_ELEMENT;
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
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.countDistinct;

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
     * column it binds where it binds one rather than a node id, its type's kind, how many node tables
     * a node id there binds, none where it is no node id, whether it is a {@code @lookupKey}, its
     * path's origin, the steps the path takes from the field's table
     * to the column's, none on an empty path, the record class of the table the path lands on,
     * which a node id decodes into, and the list occurrences above it it is read once per element of.
     */
    public record Term(String coordinate, ArgumentPaths.Path path, Column column, String typeKind,
                       int nodeIdBindings, boolean lookupKey, String origin, List<Step> steps,
                       String landingRecordClass, List<ListAbove> lists) {
        public Term {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(path, "path");
            steps = List.copyOf(steps);
            lists = List.copyOf(lists);
        }

        /** Whether the term is a node id. */
        public boolean nodeId() {
            return nodeIdBindings > 0;
        }

        /** The names from the argument down to this term. */
        public List<String> names() {
            return path.names();
        }

        /** Whether the value at the end is a list. */
        public boolean list() {
            return path.lists().contains(path.names().size() - 1);
        }

        /** Whether every step is declared non-null, so the value is never absent. */
        public boolean present() {
            return path.present();
        }
    }

    /**
     * A list occurrence a term is read once per element of: its coordinate, and its position on the
     * term's path of names, how many steps it sits below the argument.
     */
    public record ListAbove(String coordinate, int position) {}

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
     * occurrence's path of names from its argument, its type's kind and whether it is a node id; for a
     * {@code CONTEXT_KEY} the key, for a {@code SESSION_MEMBER} the member; and the class the parameter
     * delivers once its containers are peeled off, as the census states it; and whether the occurrence
     * it takes is read once per element of a list above it.
     */
    public record Fill(int position, String parameterType, String extraction, String fill, String landing,
                       ArgumentPaths.Path path, String landingKind, boolean landingNodeId, String contextKey,
                       String sessionMember, String element, boolean throughList) {
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
     * One field occurrence's filters on one table its rows are, a row of its {@code TABLE} binding: the
     * occurrence, what it produces where it produces anything, the table, the generated terms in
     * coordinate order but those an {@code override: true} suppresses, the {@code @condition} calls
     * applied there in coordinate order, a condition on a node id's table among them, and whether a
     * call reads the request context or the session, which the emitted
     * method then takes.
     */
    public record Row(String coordinate, String producerKind, LiftQuery.Table table, List<Term> terms,
                      List<Call> calls, boolean takesEnv, boolean takesDsl) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(table, "table");
            terms = List.copyOf(terms);
            calls = List.copyOf(calls);
        }
    }

    private record Raw(String coordinate, Column column, String typeKind, int nodeIdBindings, boolean lookupKey,
                       String origin, String targetType, String landingRecordClass, List<ListAbove> lists) {}

    private record Applied(Call call, boolean override) {}

    /**
     * The graph's field occurrences with filters, by coordinate and table: one statement driven from
     * the fields' {@code TABLE} bindings, the terms read over each row's table and the calls applied to
     * it nested on its key.
     */
    public static List<Row> read(StoreHandle store) {
        var paths = ArgumentPaths.read(store);
        var steps = steps(store);
        var parents = parents(store);
        // The rows: a field and each table it is bound to, where its query starts and its filter
        // applies.
        var rb = GRAPHITRON_OCCURRENCE_BINDING_TABLE.as("rb");
        var fo = GRAPHITRON_OCCURRENCE.as("fo");
        var rbGraph = rb.GRAPH_NAME;
        var rbCoordinate = rb.COORDINATE;
        var rbBound = rb.BOUND;
        var rbSource = rb.TABLE_SOURCE_NAME;
        var rbSchema = rb.TABLE_SCHEMA;
        var rbTable = rb.TABLE_NAME;
        var rt = SQL_TABLE.as("rt");
        var rs = SQL_SCHEMA.as("rs");
        var rp = GRAPHITRON_OCCURRENCE_PRODUCER.as("rp");

        // The terms: each argument or input-field occurrence under the field read over the row's table,
        // its own source, binding a column where its need lands or being a node id. Its need starts
        // there, and its column is where that need's join path lands, the need's own landing on an
        // empty path.
        var o = GRAPHITRON_OCCURRENCE;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var c = SQL_COLUMN;
        var a = GRAPHITRON_ARGUMENT;
        var f = GRAPHITRON_FIELD;
        var ty = GRAPHITRON_TYPE;
        var p = GRAPHITRON_PATH;
        var n = GRAPHITRON_OCCURRENCE_NODE_ID;
        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        var pn = GRAPHITRON_PATH_NEED_TABLE;
        var jp = GRAPHITRON_JOIN_PATH;
        var lt = SQL_TABLE.as("lt");
        var landingSource = coalesce(jp.TARGET_SOURCE_NAME, pn.LANDING_SOURCE_NAME);
        var landingSchema = coalesce(jp.TARGET_SCHEMA, pn.LANDING_SCHEMA);
        var landingTable = coalesce(jp.TARGET_TABLE, pn.LANDING_TABLE);
        var nodeId = field(exists(selectOne().from(n).where(n.GRAPH_NAME.eq(o.GRAPH_NAME), n.COORDINATE.eq(o.COORDINATE))));
        // A node id under a field over several tables binds one node table, or one per participant
        // where each decodes the id as a node of its own.
        var nodeIdBindings = field(select(countDistinct(n.BOUND)).from(n)
            .where(n.GRAPH_NAME.eq(o.GRAPH_NAME), n.COORDINATE.eq(o.COORDINATE)));
        // A term is a lookup's key where the argument it is read from carries @lookupKey: the argument
        // itself, or every input field under it.
        var ao = GRAPHITRON_OCCURRENCE.as("ao");
        var le = GRAPHITRON_OCCURRENCE_LIST_ELEMENT;
        var listOccurrence = GRAPHITRON_OCCURRENCE.as("lo2");
        var argumentOccurrence = GRAPHITRON_OCCURRENCE.as("la2");
        var lists = multiset(select(le.LIST_COORDINATE, listOccurrence.DEPTH.minus(argumentOccurrence.DEPTH))
                .from(le)
                .join(listOccurrence).on(listOccurrence.GRAPH_NAME.eq(le.GRAPH_NAME),
                    listOccurrence.COORDINATE.eq(le.LIST_COORDINATE))
                .join(argumentOccurrence).on(argumentOccurrence.GRAPH_NAME.eq(o.GRAPH_NAME),
                    argumentOccurrence.COORDINATE.eq(o.ARGUMENT_COORDINATE))
                .where(le.GRAPH_NAME.eq(o.GRAPH_NAME), le.COORDINATE.eq(o.COORDINATE))
                .orderBy(le.LIST_COORDINATE))
            .convertFrom(r -> r.map(Records.mapping(ListAbove::new)));
        var terms = multiset(
            select(o.COORDINATE, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, ty.KIND, nodeIdBindings,
                field(exists(selectOne().from(ao).join(d).on(d.GRAPH_NAME.eq(ao.GRAPH_NAME),
                        d.COORDINATE.eq(ao.ELEMENT_COORDINATE), d.DIRECTIVE_NAME.eq("lookupKey"))
                    .where(ao.GRAPH_NAME.eq(o.GRAPH_NAME), ao.COORDINATE.eq(o.ARGUMENT_COORDINATE)))),
                p.ORIGIN, oc.TABLE_NAME, pn.TARGET_TYPE, lt.RECORD_CLASS_FQN, lists)
                .from(o)
                .join(st).on(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE))
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
                .where(o.GRAPH_NAME.eq(rbGraph), o.FIELD_COORDINATE.eq(rbCoordinate),
                    o.KIND.in("FIELD_ARGUMENT", "INPUT_FIELD"),
                    st.TABLE_SOURCE_NAME.eq(rbSource), st.TABLE_SCHEMA.eq(rbSchema),
                    st.TABLE_NAME.eq(rbTable), oc.COORDINATE.isNotNull().or(nodeId))
                .orderBy(o.COORDINATE));

        // The calls: the field's own and its arguments' and input fields' applied to the row's table,
        // and those a node id's input field applies to the table it identifies, each with what fills its
        // method's parameters in position order.
        var cc = GRAPHITRON_OCCURRENCE_CONDITION;
        var co = GRAPHITRON_OCCURRENCE.as("co");
        var cb = GRAPHITRON_OCCURRENCE_BINDING;
        var cm = GRAPHITRON_OCCURRENCE_CALL_METHOD;
        var cp = GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
        var mp = CODE_METHOD_PARAMETER;
        var lo = GRAPHITRON_OCCURRENCE.as("lo");
        var la = GRAPHITRON_ARGUMENT.as("la");
        var lf = GRAPHITRON_FIELD.as("lf");
        var lty = GRAPHITRON_TYPE.as("lty");
        var ln = GRAPHITRON_OCCURRENCE_NODE_ID.as("ln");
        var fills = multiset(
            select(mp.POSITION, mp.PARAMETER_TYPE, mp.EXTRACTION, coalesce(cp.FILL, inline("UNFILLED")),
                    cp.LANDING_COORDINATE, lty.KIND,
                    field(exists(selectOne().from(ln).where(ln.GRAPH_NAME.eq(cp.GRAPH_NAME),
                        ln.COORDINATE.eq(cp.LANDING_COORDINATE)))), cp.CONTEXT_KEY, cp.SESSION_MEMBER,
                    coalesce(mp.ELEMENT_CLASS, mp.ERASED_CLASS),
                    field(exists(selectOne().from(GRAPHITRON_OCCURRENCE_LIST_ELEMENT)
                        .where(GRAPHITRON_OCCURRENCE_LIST_ELEMENT.GRAPH_NAME.eq(cp.GRAPH_NAME),
                            GRAPHITRON_OCCURRENCE_LIST_ELEMENT.COORDINATE.eq(cp.LANDING_COORDINATE)))))
                .from(mp)
                .leftJoin(cp).on(cp.GRAPH_NAME.eq(cc.GRAPH_NAME), cp.COORDINATE.eq(cc.COORDINATE), cp.CALL.eq(cc.CALL),
                    cp.POSITION.eq(mp.POSITION))
                .leftJoin(lo).on(lo.GRAPH_NAME.eq(cp.GRAPH_NAME), lo.COORDINATE.eq(cp.LANDING_COORDINATE))
                .leftJoin(la).on(la.GRAPH_NAME.eq(lo.GRAPH_NAME), la.COORDINATE.eq(lo.ELEMENT_COORDINATE))
                .leftJoin(lf).on(lf.GRAPH_NAME.eq(lo.GRAPH_NAME), lf.COORDINATE.eq(lo.ELEMENT_COORDINATE))
                .leftJoin(lty).on(lty.GRAPH_NAME.eq(lo.GRAPH_NAME), lty.TYPE_NAME.eq(coalesce(la.NAMED_TYPE, lf.NAMED_TYPE)))
                .where(mp.SOURCE_NAME.eq(cm.METHOD_SOURCE_NAME), mp.CLASS_NAME.eq(cm.METHOD_CLASS_NAME),
                    mp.METHOD_NAME.eq(cm.METHOD_NAME), mp.DESCRIPTOR.eq(cm.METHOD_DESCRIPTOR))
                .orderBy(mp.POSITION));
        var calls = multiset(
            select(cc.COORDINATE, co.KIND, cc.OVERRIDE_WRITTEN, cb.KIND, cm.METHOD_CLASS_NAME, cm.METHOD_NAME, fills)
                .from(cc)
                .join(co).on(co.GRAPH_NAME.eq(cc.GRAPH_NAME), co.COORDINATE.eq(cc.COORDINATE))
                .join(cb).on(cb.GRAPH_NAME.eq(cc.GRAPH_NAME), cb.COORDINATE.eq(cc.COORDINATE), cb.BOUND.eq(cc.BOUND))
                .join(cm).on(cm.GRAPH_NAME.eq(cc.GRAPH_NAME), cm.COORDINATE.eq(cc.COORDINATE), cm.CALL.eq(cc.CALL))
                .where(cc.GRAPH_NAME.eq(rbGraph),
                    coalesce(co.FIELD_COORDINATE, co.COORDINATE).eq(rbCoordinate),
                    cc.BOUND.eq(rbBound).or(cb.KIND.eq("NODE_ID")))
                .orderBy(cc.COORDINATE));

        var out = new ArrayList<Row>();
        store.dsl()
            .select(rbCoordinate, rp.KIND, rt.TABLE_NAME, rt.CLASS_FQN, rs.TABLES_CLASS_FQN, rt.JOOQ_NAME,
                rt.RECORD_CLASS_FQN, terms, calls)
            .from(rb)
            .join(fo).on(fo.GRAPH_NAME.eq(rbGraph), fo.COORDINATE.eq(rbCoordinate), fo.KIND.eq("FIELD"))
            .join(rt).on(rt.SOURCE_NAME.eq(rbSource), rt.TABLE_SCHEMA.eq(rbSchema),
                rt.TABLE_NAME.eq(rbTable))
            .join(rs).on(rs.SOURCE_NAME.eq(rt.SOURCE_NAME), rs.TABLE_SCHEMA.eq(rt.TABLE_SCHEMA))
            .leftJoin(rp).on(rp.GRAPH_NAME.eq(rbGraph), rp.COORDINATE.eq(rbCoordinate))
            .where(rbGraph.eq(store.graphName()))
            .orderBy(rbCoordinate, rt.TABLE_NAME)
            .forEach(r -> {
                var applied = r.value9().map(k -> new Applied(new Call(k.value1(), k.value2(), k.value4(),
                    k.value5(), k.value6(), k.value7().map(x -> new Fill(x.value1(), x.value2(), x.value3(),
                        x.value4(), x.value5(), x.value5() == null ? null : paths.get(x.value5()), x.value6(),
                        Boolean.TRUE.equals(x.value7()), x.value8(), x.value9(), x.value10(),
                        Boolean.TRUE.equals(x.value11())))),
                    Boolean.TRUE.equals(k.value3())));
                // An override: true written on a condition suppresses the terms at its occurrence and
                // under it, read as written.
                var overriding = applied.stream().filter(Applied::override).map(x -> x.call().coordinate()).toList();
                var written = r.value8().stream()
                    .map(t -> new Raw(t.value1(), t.value2() == null ? null : new Column(t.value9(), t.value2(),
                        t.value3(), t.value4()), t.value5(), t.value6() == null ? 0 : t.value6(),
                        Boolean.TRUE.equals(t.value7()), t.value8(), t.value10(), t.value11(), t.value12()))
                    .filter(raw -> overriding.stream().noneMatch(at -> under(raw.coordinate(), at, parents)))
                    .map(raw -> new Term(raw.coordinate(), paths.get(raw.coordinate()), raw.column(), raw.typeKind(),
                        raw.nodeIdBindings(), raw.lookupKey(), raw.origin(),
                        steps.getOrDefault(List.of(raw.coordinate(), String.valueOf(raw.targetType())), List.of()),
                        raw.landingRecordClass(), raw.lists()))
                    .toList();
                var made = applied.stream().map(Applied::call).toList();
                if (r.value8().isEmpty() && made.isEmpty()) {
                    return;
                }
                var kinds = made.stream().flatMap(x -> x.fills().stream()).map(Fill::fill).toList();
                out.add(new Row(r.value1(), r.value2(),
                    new LiftQuery.Table(r.value3(), r.value4(), r.value5(), r.value6(), r.value7()), written, made,
                    kinds.contains("CONTEXT_KEY"),
                    kinds.contains("SESSION") || kinds.contains("SESSION_MEMBER")));
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
