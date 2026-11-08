package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
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
 * The field occurrence carries what it produces, if anything, and whether a {@code @condition} is
 * written on it or anywhere under it, which decides whether the generated terms stand alone.
 */
public final class ConditionQuery {

    private ConditionQuery() {}

    /** The column a term compares, where it binds one. */
    public record Column(String sqlName, String javaName, String bindingType) {
        public Column {
            Objects.requireNonNull(sqlName, "sqlName");
            Objects.requireNonNull(javaName, "javaName");
            Objects.requireNonNull(bindingType, "bindingType");
        }
    }

    /**
     * One argument or input field's filter: its occurrence, its path of names from the argument, the
     * column it binds where it binds one rather than a node id, its type's kind, whether it is a node id or a {@code @lookupKey}, and its path's
     * origin.
     */
    public record Term(String coordinate, ArgumentPaths.Path path, Column column, String typeKind,
                       boolean nodeId, boolean lookupKey, String origin) {
        public Term {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(path, "path");
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

    /**
     * One field occurrence's filters on one table: the occurrence, what it produces where it produces
     * anything, whether a {@code @condition} is written on it or under it, the table and the terms in
     * coordinate order.
     */
    public record Row(String coordinate, String producerKind, boolean authored, LiftQuery.Table table,
                      List<Term> terms) {
        public Row {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(table, "table");
            terms = List.copyOf(terms);
        }
    }

    private record Raw(String field, String coordinate, String producerKind, String tableSqlName,
                       String tableClass, String constantsClass, String tableJavaName, String recordClass,
                       Column column, String typeKind, boolean nodeId, boolean lookupKey, String origin) {}

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
        var authored = authored(store);
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        var pn = GRAPHITRON_PATH_NEED_TABLE;
        var jp = GRAPHITRON_JOIN_PATH;
        // A term is read over the table its argument filters, the field's own: its source. Its need
        // starts there, and its column is where that need's join path lands, the need's own landing
        // on an empty path.
        var landingSource = coalesce(jp.TARGET_SOURCE_NAME, pn.LANDING_SOURCE_NAME);
        var landingSchema = coalesce(jp.TARGET_SCHEMA, pn.LANDING_SCHEMA);
        var landingTable = coalesce(jp.TARGET_TABLE, pn.LANDING_TABLE);
        var nodeId = field(exists(selectOne().from(n).where(n.GRAPH_NAME.eq(o.GRAPH_NAME), n.COORDINATE.eq(o.COORDINATE))));
        var raws = store.dsl()
            .select(o.FIELD_COORDINATE, o.COORDINATE, pr.KIND, t.TABLE_NAME, t.CLASS_FQN, s.TABLES_CLASS_FQN,
                t.JOOQ_NAME, t.RECORD_CLASS_FQN, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, ty.KIND, nodeId,
                field(exists(selectOne().from(d).where(d.GRAPH_NAME.eq(o.GRAPH_NAME),
                    d.COORDINATE.eq(o.ELEMENT_COORDINATE), d.DIRECTIVE_NAME.eq("lookupKey")))),
                p.ORIGIN)
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
                r.value7(), r.value8(), r.value9() == null ? null : new Column(r.value9(), r.value10(), r.value11()),
                r.value12(), Boolean.TRUE.equals(r.value13()), Boolean.TRUE.equals(r.value14()), r.value15()));
        var rows = new LinkedHashMap<List<String>, List<Raw>>();
        for (var raw : raws) {
            rows.computeIfAbsent(List.of(raw.field(), raw.tableSqlName()), k -> new ArrayList<>()).add(raw);
        }
        var out = new ArrayList<Row>();
        rows.forEach((key, group) -> {
            var first = group.getFirst();
            var table = new LiftQuery.Table(first.tableSqlName(), first.tableClass(), first.constantsClass(),
                first.tableJavaName(), first.recordClass());
            var terms = group.stream().map(raw -> new Term(raw.coordinate(), paths.get(raw.coordinate()),
                raw.column(), raw.typeKind(), raw.nodeId(), raw.lookupKey(), raw.origin())).toList();
            out.add(new Row(first.field(), first.producerKind(), authored.contains(first.field()), table, terms));
        });
        return out;
    }

    /**
     * The field occurrences a {@code @condition} is written on, or on an argument or input field
     * anywhere under them.
     */
    private static Set<String> authored(StoreHandle store) {
        var o = GRAPHITRON_OCCURRENCE;
        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        return new HashSet<>(store.dsl()
            .selectDistinct(coalesce(o.FIELD_COORDINATE, o.COORDINATE))
            .from(o)
            .join(d).on(d.GRAPH_NAME.eq(o.GRAPH_NAME), d.COORDINATE.eq(o.ELEMENT_COORDINATE),
                d.DIRECTIVE_NAME.eq("condition"))
            .where(o.GRAPH_NAME.eq(store.graphName()))
            .fetch(r -> r.value1()));
    }
}
