package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION_SIGNATURE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ORDER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SORT_KEY_COMPLETION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.selectOne;

/**
 * Each root {@code SELECT} or {@code LOOKUP} producer, and whether the store states its fetcher whole:
 * read off its occurrence, the table its records are rows of, the type whose projection selects them,
 * its default order and the condition method its table has.
 *
 * <p>Roots flip piece by piece, so a producer the store does not state whole yet is {@link NotYet}, its
 * reason the stored fact that holds it back, and its fetcher stays the walk's. The census in
 * {@code EmissionCensusTest} pins each reason, and a reason goes with the node that states its shape.
 */
public final class RootQuery {

    private RootQuery() {}

    /** Why a root's fetcher is not emitted from the store yet. */
    public enum Reason {
        /** A {@code @lookupKey} narrows its rows by the keys the caller hands it. */
        LOOKUP,
        /** Its rows may be several types', told apart by table or discriminator. */
        POLYMORPHIC,
        /** It builds a connection. */
        CONNECTION,
        /** Its table carries the tenant column. */
        TENANTED,
        /** It is read through a {@code @pivot}. */
        PIVOT,
        /** An {@code @orderBy} argument chooses its order. */
        ORDER_ARGUMENT,
        /** A key of its order is not a column of its table. */
        SORT_KEY_NOT_A_COLUMN,
        /** It produces no records of one table-bound type. */
        NOT_ONE_TABLE
    }

    /** One root producer's verdict, keyed by its occurrence. */
    public sealed interface Result {
        String coordinate();
    }

    /** A root the store holds back, and why. */
    public record NotYet(String coordinate, Reason reason) implements Result {
        public NotYet {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * A root the store states whole: its occurrence and field, the type whose projection selects its
     * rows and their table, whether it reads a list, its order's keys in sequence, the row key
     * completing them, and the condition method its table has, {@code null} where it has none.
     */
    public record Emitted(String coordinate, String parentType, String fieldName, String returnType,
                          TableRef table, boolean list, List<Key> order, Signature condition)
            implements Result {
        public Emitted {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(parentType, "parentType");
            Objects.requireNonNull(fieldName, "fieldName");
            Objects.requireNonNull(returnType, "returnType");
            Objects.requireNonNull(table, "table");
            order = List.copyOf(order);
        }
    }

    /** One key of an order: the column, whether it holds nulls, its direction and its collation. */
    public record Key(ColumnRef column, boolean nullable, String direction, String collation) {}

    /** What the table's condition method takes beside the table and the arguments. */
    public record Signature(boolean takesContext, boolean takesSession) {}

    private record Producer(String coordinate, String kind, String shape, String parentType, String fieldName,
                            String returnType, String source, String schema, String table) {}

    /** The verdict of every root {@code SELECT} and {@code LOOKUP} producer of the graph, by occurrence. */
    public static Map<String, Result> read(StoreHandle store) {
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
        var o = GRAPHITRON_OCCURRENCE;
        var f = GRAPHITRON_FIELD;
        var ft = GRAPHITRON_FIELD_TARGET;
        var tt = GRAPHITRON_TABLETYPE;
        var x = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        var cn = GRAPHITRON_OCCURRENCE_PRODUCER_CONNECTION;
        var tc = GRAPHITRON_TENANT_COLUMN;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        var od = GRAPHITRON_OCCURRENCE_ORDER;
        var sk = GRAPHITRON_OCCURRENCE_SORT_KEY;
        var graph = store.graphName();
        var producers = store.dsl()
            .select(p.COORDINATE, p.KIND, r.SHAPE, f.TYPE_NAME, f.FIELD_NAME, ft.TARGET_TYPE_NAME,
                r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME,
                field(exists(selectOne().from(x).where(x.GRAPH_NAME.eq(p.GRAPH_NAME), x.COORDINATE.eq(p.COORDINATE)))),
                field(exists(selectOne().from(cn).where(cn.GRAPH_NAME.eq(p.GRAPH_NAME), cn.COORDINATE.eq(p.COORDINATE)))),
                field(exists(selectOne().from(tc).where(tc.GRAPH_NAME.eq(r.GRAPH_NAME), tc.SOURCE_NAME.eq(r.TABLE_SOURCE_NAME),
                    tc.TABLE_SCHEMA.eq(r.TABLE_SCHEMA), tc.TABLE_NAME.eq(r.TABLE_NAME)))),
                field(exists(selectOne().from(da).where(da.GRAPH_NAME.eq(f.GRAPH_NAME), da.COORDINATE.eq(f.COORDINATE),
                    da.DIRECTIVE_NAME.eq("pivot")))),
                field(exists(selectOne().from(od).where(od.GRAPH_NAME.eq(p.GRAPH_NAME), od.COORDINATE.eq(p.COORDINATE),
                    od.KIND.ne("DEFAULT")))),
                field(exists(selectOne().from(sk).where(sk.GRAPH_NAME.eq(p.GRAPH_NAME), sk.COORDINATE.eq(p.COORDINATE),
                    sk.KIND.ne("COLUMN")))),
                field(exists(selectOne().from(tt).where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME),
                    tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME), tt.TABLE_SOURCE_NAME.eq(r.TABLE_SOURCE_NAME),
                    tt.TABLE_SCHEMA.eq(r.TABLE_SCHEMA), tt.TABLE_NAME.eq(r.TABLE_NAME)))))
            .from(p)
            .join(o).on(o.GRAPH_NAME.eq(p.GRAPH_NAME), o.COORDINATE.eq(p.COORDINATE))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .leftJoin(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME), ft.FIELD_NAME.eq(f.FIELD_NAME))
            .leftJoin(r).on(r.GRAPH_NAME.eq(p.GRAPH_NAME), r.COORDINATE.eq(p.COORDINATE))
            .where(p.GRAPH_NAME.eq(graph), p.START.eq("ROOT"), p.KIND.in("SELECT", "LOOKUP"))
            .orderBy(p.COORDINATE)
            .fetch();

        var orders = orders(store);
        var signatures = signatures(store);
        var out = new LinkedHashMap<String, Result>();
        for (var row : producers) {
            var at = new Producer(row.value1(), row.value2(), row.value3(), row.value4(), row.value5(), row.value6(),
                row.value7(), row.value8(), row.value9());
            Reason reason = null;
            if (Boolean.TRUE.equals(row.value10())) reason = Reason.POLYMORPHIC;
            else if (Boolean.TRUE.equals(row.value11())) reason = Reason.CONNECTION;
            else if (at.table() == null || at.returnType() == null || !Boolean.TRUE.equals(row.value16())
                || !("RECORD_LIST".equals(at.shape()) || "SINGLE_RECORD".equals(at.shape()))) {
                reason = Reason.NOT_ONE_TABLE;
            }
            else if (Boolean.TRUE.equals(row.value12())) reason = Reason.TENANTED;
            else if (Boolean.TRUE.equals(row.value13())) reason = Reason.PIVOT;
            else if ("LOOKUP".equals(at.kind())) reason = Reason.LOOKUP;
            else if (Boolean.TRUE.equals(row.value14())) reason = Reason.ORDER_ARGUMENT;
            else if (Boolean.TRUE.equals(row.value15())) reason = Reason.SORT_KEY_NOT_A_COLUMN;
            if (reason != null) {
                out.put(at.coordinate(), new NotYet(at.coordinate(), reason));
                continue;
            }
            var table = tableRef(store, at.source(), at.schema(), at.table());
            if (table == null) {
                out.put(at.coordinate(), new NotYet(at.coordinate(), Reason.NOT_ONE_TABLE));
                continue;
            }
            boolean list = "RECORD_LIST".equals(at.shape());
            out.put(at.coordinate(), new Emitted(at.coordinate(), at.parentType(), at.fieldName(), at.returnType(),
                table, list, list ? orders.getOrDefault(at.coordinate(), List.of()) : List.of(),
                signatures.get(List.of(at.coordinate(), at.schema() + "." + at.table()))));
        }
        return out;
    }

    /**
     * Each root list's default order: its written keys in sequence, then the row-key columns the store
     * completes it with. A completing column identifies the row, so it sorts no nulls apart.
     */
    private static Map<String, List<Key>> orders(StoreHandle store) {
        var od = GRAPHITRON_OCCURRENCE_ORDER;
        var sk = GRAPHITRON_OCCURRENCE_SORT_KEY;
        var kc = GRAPHITRON_OCCURRENCE_SORT_KEY_COLUMN;
        var sc = GRAPHITRON_OCCURRENCE_SORT_KEY_COMPLETION;
        var c = SQL_COLUMN;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var out = new LinkedHashMap<String, List<Key>>();
        store.dsl()
            .select(od.COORDINATE, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE, c.NULLABLE,
                sk.DIRECTION, sk.COLLATION)
            .from(od)
            .join(p).on(p.GRAPH_NAME.eq(od.GRAPH_NAME), p.COORDINATE.eq(od.COORDINATE), p.START.eq("ROOT"))
            .join(sk).on(sk.GRAPH_NAME.eq(od.GRAPH_NAME), sk.COORDINATE.eq(od.COORDINATE), sk.BOUND.eq(od.BOUND),
                sk.CHOICE.eq(od.CHOICE))
            .join(kc).on(kc.GRAPH_NAME.eq(sk.GRAPH_NAME), kc.COORDINATE.eq(sk.COORDINATE), kc.BOUND.eq(sk.BOUND),
                kc.CHOICE.eq(sk.CHOICE), kc.POSITION.eq(sk.POSITION))
            .join(c).on(c.SOURCE_NAME.eq(kc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(kc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(kc.TABLE_NAME), c.COLUMN_NAME.eq(kc.COLUMN_NAME))
            .where(od.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("DEFAULT"))
            .orderBy(od.COORDINATE, sk.POSITION)
            .forEach(row -> out.computeIfAbsent(row.value1(), k -> new ArrayList<>()).add(new Key(
                new ColumnRef(row.value2(), row.value3(), row.value4(), row.value5()),
                Boolean.TRUE.equals(row.value6()), row.value7(), row.value8())));
        store.dsl()
            .select(sc.COORDINATE, c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE, sc.DIRECTION)
            .from(sc)
            .join(od).on(od.GRAPH_NAME.eq(sc.GRAPH_NAME), od.COORDINATE.eq(sc.COORDINATE), od.BOUND.eq(sc.BOUND),
                od.CHOICE.eq(sc.CHOICE))
            .join(p).on(p.GRAPH_NAME.eq(od.GRAPH_NAME), p.COORDINATE.eq(od.COORDINATE), p.START.eq("ROOT"))
            .join(c).on(c.SOURCE_NAME.eq(sc.TABLE_SOURCE_NAME), c.TABLE_SCHEMA.eq(sc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(sc.TABLE_NAME), c.COLUMN_NAME.eq(sc.COLUMN_NAME))
            .where(sc.GRAPH_NAME.eq(store.graphName()), od.KIND.eq("DEFAULT"))
            .orderBy(sc.COORDINATE, sc.POSITION)
            .forEach(row -> out.computeIfAbsent(row.value1(), k -> new ArrayList<>()).add(new Key(
                new ColumnRef(row.value2(), row.value3(), row.value4(), row.value5()), false, row.value6(), null)));
        return out;
    }

    /** Each root's condition method, by occurrence and bound table. */
    private static Map<List<String>, Signature> signatures(StoreHandle store) {
        var s = GRAPHITRON_OCCURRENCE_CONDITION_SIGNATURE;
        var p = GRAPHITRON_OCCURRENCE_PRODUCER;
        var out = new LinkedHashMap<List<String>, Signature>();
        store.dsl()
            .select(s.COORDINATE, s.BOUND, s.TAKES_CONTEXT, s.TAKES_SESSION)
            .from(s)
            .join(p).on(p.GRAPH_NAME.eq(s.GRAPH_NAME), p.COORDINATE.eq(s.COORDINATE), p.START.eq("ROOT"))
            .where(s.GRAPH_NAME.eq(store.graphName()))
            .forEach(row -> out.put(List.of(row.value1(), row.value2()), new Signature(row.value3(), row.value4())));
        return out;
    }

    /**
     * The catalog table as a {@link TableRef}: its names and generated classes, its primary key in key
     * order and its columns in declaration order. {@code null} where its schema publishes no
     * {@code Tables} class, there being no constant to reach it through.
     */
    private static TableRef tableRef(StoreHandle store, String source, String schema, String name) {
        var t = SQL_TABLE;
        var s = SQL_SCHEMA;
        var c = SQL_COLUMN;
        var pk = SQL_PRIMARY_KEY;
        var cc = SQL_CONSTRAINT_COLUMN;
        var row = store.dsl()
            .select(t.TABLE_NAME, t.JOOQ_NAME, t.CLASS_FQN, t.RECORD_CLASS_FQN, s.TABLES_CLASS_FQN)
            .from(t)
            .join(s).on(s.SOURCE_NAME.eq(t.SOURCE_NAME), s.TABLE_SCHEMA.eq(t.TABLE_SCHEMA))
            .where(t.SOURCE_NAME.eq(source), t.TABLE_SCHEMA.eq(schema), t.TABLE_NAME.eq(name))
            .fetchOne();
        if (row == null || row.value5() == null) {
            return null;
        }
        var keys = store.dsl()
            .select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
            .from(pk)
            .join(cc).on(cc.SOURCE_NAME.eq(pk.SOURCE_NAME), cc.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA),
                cc.TABLE_NAME.eq(pk.TABLE_NAME), cc.CONSTRAINT_NAME.eq(pk.CONSTRAINT_NAME))
            .join(c).on(c.SOURCE_NAME.eq(cc.SOURCE_NAME), c.TABLE_SCHEMA.eq(cc.TABLE_SCHEMA),
                c.TABLE_NAME.eq(cc.TABLE_NAME), c.COLUMN_NAME.eq(cc.COLUMN_NAME))
            .where(pk.SOURCE_NAME.eq(source), pk.TABLE_SCHEMA.eq(schema), pk.TABLE_NAME.eq(name))
            .orderBy(cc.POSITION)
            .fetch(k -> new ColumnRef(k.value1(), k.value2(), k.value3(), k.value4()));
        var columns = store.dsl()
            .select(c.COLUMN_NAME, c.JOOQ_NAME, c.BINDING_TYPE, c.DATABASE_TYPE)
            .from(c)
            .where(c.SOURCE_NAME.eq(source), c.TABLE_SCHEMA.eq(schema), c.TABLE_NAME.eq(name))
            .orderBy(c.ORDINAL)
            .fetch(k -> new ColumnRef(k.value1(), k.value2(), k.value3(), k.value4()));
        return new TableRef(row.value1(), row.value2(), row.value3(), row.value4(), row.value5(), keys, columns);
    }
}
