package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.tables.GraphitronField;
import no.sikt.graphitron.model.tables.GraphitronOccurrence;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.SelectField;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.CODE_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCESSOR_PATH;
import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERRORS_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_WRITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_occurrence_source} and its subtypes: what each
 * occurrence's values come from, rows of one or more tables or an instance of a class, marked and
 * swept.
 *
 * <p>A source is stated on the occurrence, never as a pointer to another. It is written a depth at a
 * time, as {@link Occurrences} writes the occurrences: depth 0 from the declaring type's table or a
 * root {@code @routine}'s function, and each deeper depth from what this reading stated for the
 * parent at the depth above and what the parent field's producer hands back. Under a parent field
 * that writes a path, a child field or an argument reads the tables the parent's join path reaches,
 * and a path that stops gives them none. A child whose own service hands back a table's records
 * reads, at any depth, the table its producer's result names, where its rows come from, and nothing
 * its parent gives; likewise an argument or input field a service call takes as a table's jOOQ
 * record reads that table, the record it fills. Each relation is one {@code INSERT .. SELECT} per depth over the rows the rules
 * give for that depth, the instant and the depth bound as parameters; {@link #sweep} runs after the
 * last depth.
 *
 * <p>A kind is part of its subtypes' keys, so a source whose kind changed since the last reading is
 * deleted before it is written again, taking its subtypes.
 *
 * <p>A step of the assembly's per-depth loop: a field under a parent's value reads that value, its
 * table or its class, and never the key a producer takes off it, which is the producer's
 * ({@link OccurrenceLifts}). Reads the producers {@link OccurrenceProducers} writes for the same
 * depth, the call parameters {@link OccurrenceCallParameters} wrote for the calls above, and the join
 * paths {@link Steps} wrote for the depth above.
 */
public final class OccurrenceSources {

    private OccurrenceSources() {}

    private static final String TABLE = "TABLE";
    private static final String OBJECT = "OBJECT";

    /** States the sources of the occurrences at {@code depth}. */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        write(dsl, graph, candidates(graph, touchedAt, depth).asTable("c"), touchedAt);
    }

    /** Deletes the rows no depth of this reading marked, subtypes before the source they hang on. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        dsl.deleteFrom(GRAPHITRON_OCCURRENCE_SOURCE_OBJECT)
            .where(GRAPHITRON_OCCURRENCE_SOURCE_OBJECT.GRAPH_NAME.eq(graph))
            .and(GRAPHITRON_OCCURRENCE_SOURCE_OBJECT.TOUCHED_AT.ne(touchedAt))
            .execute();
        dsl.deleteFrom(GRAPHITRON_OCCURRENCE_SOURCE_TABLE)
            .where(GRAPHITRON_OCCURRENCE_SOURCE_TABLE.GRAPH_NAME.eq(graph))
            .and(GRAPHITRON_OCCURRENCE_SOURCE_TABLE.TOUCHED_AT.ne(touchedAt))
            .execute();
        dsl.deleteFrom(GRAPHITRON_OCCURRENCE_SOURCE)
            .where(GRAPHITRON_OCCURRENCE_SOURCE.GRAPH_NAME.eq(graph))
            .and(GRAPHITRON_OCCURRENCE_SOURCE.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** Writes one depth's rows into every relation, supertypes before the subtypes that reference them. */
    private static void write(DSLContext dsl, String graph, Table<?> c, LocalDateTime touchedAt) {
        var cGraph = c.field("graph_name", String.class);
        var cCoordinate = c.field("coordinate", String.class);
        var cKind = c.field("kind", String.class);
        var cSource = c.field("table_source_name", String.class);
        var cSchema = c.field("table_schema", String.class);
        var cTable = c.field("table_name", String.class);
        var cClass = c.field("class_name", String.class);

        // A source whose kind changed goes with its subtypes first.
        var s = GRAPHITRON_OCCURRENCE_SOURCE;
        dsl.deleteFrom(s)
            .where(s.GRAPH_NAME.eq(graph))
            .and(exists(selectOne().from(c)
                .where(cGraph.eq(s.GRAPH_NAME), cCoordinate.eq(s.COORDINATE), cKind.ne(s.KIND))))
            .execute();

        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.COORDINATE, s.KIND, s.TOUCHED_AT)
            .select(selectDistinct(cGraph, cCoordinate, cKind, val(touchedAt, s.TOUCHED_AT)).from(c))
            .onConflict(s.GRAPH_NAME, s.COORDINATE)
            .doUpdate()
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .execute();

        var t = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        var st = SQL_TABLE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME,
                t.KIND, t.TOUCHED_AT, t.TABLE_SOURCE_REF, t.TABLE_SCHEMA_REF, t.TABLE_NAME_REF)
            .select(selectDistinct(cGraph, cCoordinate, cSource, cSchema, cTable, inline(TABLE),
                    val(touchedAt, t.TOUCHED_AT), st.SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME)
                .from(c)
                .leftJoin(st).on(st.SOURCE_NAME.eq(cSource), st.TABLE_SCHEMA.eq(cSchema),
                    st.TABLE_NAME.eq(cTable))
                .where(cKind.eq(TABLE)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME)
            .doUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .set(t.TABLE_SOURCE_REF, excluded(t.TABLE_SOURCE_REF))
            .set(t.TABLE_SCHEMA_REF, excluded(t.TABLE_SCHEMA_REF))
            .set(t.TABLE_NAME_REF, excluded(t.TABLE_NAME_REF))
            .execute();

        // A class is referenced in the graph's own classpath entries, the first by name where two
        // entries declare it.
        var b = GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
        var oc = CODE_CLASS.as("oc");
        var ocAny = CODE_CLASS.as("oc_any");
        var ogs = STORE_GRAPH_SOURCE.as("ogs");
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.KIND, b.CLASS_NAME, b.TOUCHED_AT,
                b.CLASS_SOURCE_REF, b.CLASS_NAME_REF)
            .select(selectDistinct(cGraph, cCoordinate, cKind, cClass, val(touchedAt, b.TOUCHED_AT),
                    oc.SOURCE_NAME, oc.CLASS_NAME)
                .from(c)
                .leftJoin(oc).on(oc.CLASS_NAME.eq(cClass), oc.SOURCE_NAME.eq(field(
                    select(min(ocAny.SOURCE_NAME)).from(ocAny)
                        .join(ogs).on(ogs.SOURCE_NAME.eq(ocAny.SOURCE_NAME))
                        .where(ogs.GRAPH_NAME.eq(cGraph), ocAny.CLASS_NAME.eq(cClass)))))
                .where(cKind.eq(OBJECT)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE)
            .doUpdate()
            .set(b.CLASS_NAME, excluded(b.CLASS_NAME))
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .set(b.CLASS_SOURCE_REF, excluded(b.CLASS_SOURCE_REF))
            .set(b.CLASS_NAME_REF, excluded(b.CLASS_NAME_REF))
            .execute();
    }

    /**
     * Every source row the rules give the occurrences at {@code depth}, one row per occurrence and
     * table for a table source and one per occurrence for an object source. Below depth 0 a rule
     * reads only the sources this reading stated for the parent.
     */
    private static Select<Record> candidates(String graph, LocalDateTime touchedAt, int depth) {
        var o = GRAPHITRON_OCCURRENCE.as("o");
        Condition atDepth = o.GRAPH_NAME.eq(graph).and(o.TOUCHED_AT.eq(touchedAt))
            .and(o.DEPTH.eq(val(depth)));
        // A child whose own service hands back a table's records reads that table and no other.
        Condition at = atDepth.andNot(ownRecordService(o, touchedAt));
        if (depth == 0) {
            return declaringTable(o, at).union(rootRoutine(o, at))
                .union(ownServiceRecord(o, atDepth, touchedAt));
        }
        var p = GRAPHITRON_OCCURRENCE.as("p");
        // An occurrence a service call takes as a table's record reads that table and no other.
        Condition record = at.and(takesRecord(o, touchedAt));
        at = at.andNot(takesRecord(o, touchedAt));
        return ownServiceRecord(o, atDepth, touchedAt)
            .union(recordParameter(o, record, touchedAt))
            .union(record(o, p, at, touchedAt))
            .union(object(o, p, at, touchedAt))
            .union(objectMember(o, p, at, touchedAt))
            .union(writePayload(o, p, at, touchedAt))
            .union(writePayloadMember(o, p, at, touchedAt))
            .union(nestedCopy(o, p, at, touchedAt))
            .union(nestedPath(o, p, at, touchedAt))
            .union(argumentTarget(o, p, at))
            .union(argumentMember(o, p, at))
            .union(argumentWrite(o, p, at))
            .union(argumentCopy(o, p, at, touchedAt))
            .union(argumentPath(o, p, at, touchedAt))
            .union(inputFieldCopy(o, p, at, touchedAt));
    }

    private static Field<String> none(String name) {
        return castNull(SQLDataType.VARCHAR).as(name);
    }

    /** The row every rule selects, in one column order: a table for a table source, a class for an object. */
    private static org.jooq.SelectSelectStep<Record> row(
            GraphitronOccurrence o, String kind, Field<String> tableSource, Field<String> schema,
            Field<String> table, Field<String> className) {
        return selectDistinct(List.<SelectField<?>>of(o.GRAPH_NAME.as("graph_name"), o.COORDINATE.as("coordinate"),
            inline(kind).as("kind"),
            tableSource == null ? none("table_source_name") : tableSource.as("table_source_name"),
            schema == null ? none("table_schema") : schema.as("table_schema"),
            table == null ? none("table_name") : table.as("table_name"),
            className == null ? none("class_name") : className.as("class_name")));
    }

    /**
     * Whether the occurrence {@code o} is a child whose own producer is a service handing back a
     * table's records, as the producer's result this reading stated names the table.
     */
    private static Condition ownRecordService(GraphitronOccurrence o, LocalDateTime touchedAt) {
        var op = GRAPHITRON_OCCURRENCE_PRODUCER.as("orp");
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT.as("orr");
        return exists(selectOne().from(op)
            .join(r).on(r.GRAPH_NAME.eq(op.GRAPH_NAME), r.COORDINATE.eq(op.COORDINATE),
                r.TOUCHED_AT.eq(touchedAt))
            .where(op.GRAPH_NAME.eq(o.GRAPH_NAME), op.COORDINATE.eq(o.COORDINATE),
                op.TOUCHED_AT.eq(touchedAt), op.KIND.eq("SERVICE"), op.START.eq("CHILD"),
                r.TABLE_NAME.isNotNull()));
    }

    /**
     * A child whose own service hands back a table's records reads the table its producer's result
     * names, where its rows come from, whatever its parent reads.
     */
    private static Select<Record> ownServiceRecord(GraphitronOccurrence o, Condition at,
                                                   LocalDateTime touchedAt) {
        var op = GRAPHITRON_OCCURRENCE_PRODUCER.as("sp");
        var r = GRAPHITRON_OCCURRENCE_PRODUCER_RESULT.as("sr");
        return row(o, TABLE, r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, null)
            .from(o)
            .join(op).on(op.GRAPH_NAME.eq(o.GRAPH_NAME), op.COORDINATE.eq(o.COORDINATE),
                op.TOUCHED_AT.eq(touchedAt))
            .join(r).on(r.GRAPH_NAME.eq(op.GRAPH_NAME), r.COORDINATE.eq(op.COORDINATE),
                r.TOUCHED_AT.eq(touchedAt))
            .where(at, o.KIND.eq("FIELD"), op.KIND.eq("SERVICE"), op.START.eq("CHILD"),
                r.TABLE_NAME.isNotNull());
    }

    /** Depth 0: a field of a table-bound type, object or interface, reads that type's table. */
    private static Select<Record> declaringTable(GraphitronOccurrence o, Condition at) {
        var f = GRAPHITRON_FIELD.as("f");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        return row(o, TABLE, tt.TABLE_SOURCE_NAME, tt.TABLE_SCHEMA, tt.TABLE_NAME, null)
            .from(o)
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(tt).on(tt.GRAPH_NAME.eq(f.GRAPH_NAME), tt.TYPE_NAME.eq(f.TYPE_NAME))
            .where(at, o.KIND.eq("FIELD"));
    }

    /**
     * Depth 0: a root field whose producer calls a routine reads the derived table its function
     * returns, the FUNCTION row of {@code sql_table} sharing the routine's key.
     */
    private static Select<Record> rootRoutine(GraphitronOccurrence o, Condition at) {
        var f = GRAPHITRON_FIELD.as("f");
        var ro = GRAPHQL_ROOT_OPERATION.as("ro");
        var pr = GRAPHITRON_PRODUCER_ROUTINE.as("pr");
        var st = SQL_TABLE.as("st");
        return row(o, TABLE, st.SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME, null)
            .from(o)
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ro).on(ro.GRAPH_NAME.eq(f.GRAPH_NAME), ro.TYPE_NAME.eq(f.TYPE_NAME))
            .join(pr).on(pr.GRAPH_NAME.eq(f.GRAPH_NAME), pr.TYPE_NAME.eq(f.TYPE_NAME),
                pr.FIELD_NAME.eq(f.FIELD_NAME))
            .join(st).on(st.SOURCE_NAME.eq(pr.ROUTINE_SOURCE_NAME), st.TABLE_SCHEMA.eq(pr.TABLE_SCHEMA),
                st.TABLE_NAME.eq(pr.ROUTINE_NAME), st.TABLE_TYPE.eq("FUNCTION"))
            .where(at, o.KIND.eq("FIELD"));
    }

    /**
     * A nested field under a parent whose service hands back a table's own
     * record, or whose {@code @externalField}'s {@code Field} carries one, reads that table, the
     * record class matched in the graph's own catalog.
     */
    private static Select<Record> record(GraphitronOccurrence o, GraphitronOccurrence p,
                                         Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var cf = GRAPHITRON_FIELD.as("cf");
        var ps = GRAPHITRON_PRODUCER_SERVICE.as("ps");
        var m = CODE_METHOD.as("m");
        var xr = GRAPHITRON_CODE_REFERENCE.as("xr");
        var xm = CODE_METHOD.as("xm");
        var gs = STORE_GRAPH_SOURCE.as("gs");
        var st = SQL_TABLE.as("st");
        var po = GRAPHITRON_OCCURRENCE_SOURCE.as("po");
        var ef = GRAPHITRON_ERRORS_FIELD.as("ef");
        // The class the parent hands over: its service's result, or the class its Field carries.
        Field<String> handed = coalesce(m.RESULT_ELEMENT_CLASS, xm.RESULT_FIRST_ARGUMENT_CLASS);
        return row(o, TABLE, st.SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .leftJoin(ps).on(ps.GRAPH_NAME.eq(pf.GRAPH_NAME), ps.TYPE_NAME.eq(pf.TYPE_NAME),
                ps.FIELD_NAME.eq(pf.FIELD_NAME))
            .leftJoin(m).on(m.SOURCE_NAME.eq(ps.METHOD_SOURCE_NAME), m.CLASS_NAME.eq(ps.METHOD_CLASS_NAME),
                m.METHOD_NAME.eq(ps.METHOD_NAME), m.DESCRIPTOR.eq(ps.METHOD_DESCRIPTOR))
            .leftJoin(xr).on(xr.GRAPH_NAME.eq(pf.GRAPH_NAME), xr.COORDINATE.eq(pf.COORDINATE),
                xr.DIRECTIVE_NAME.eq("externalField"), xr.KIND.eq("ROOT"))
            .leftJoin(xm).on(xm.SOURCE_NAME.eq(xr.METHOD_SOURCE_NAME), xm.CLASS_NAME.eq(xr.METHOD_CLASS_NAME),
                xm.METHOD_NAME.eq(xr.METHOD_NAME), xm.DESCRIPTOR.eq(xr.METHOD_DESCRIPTOR))
            .join(gs).on(gs.GRAPH_NAME.eq(o.GRAPH_NAME))
            .join(st).on(st.SOURCE_NAME.eq(gs.SOURCE_NAME), st.RECORD_CLASS_FQN.eq(handed))
            .join(cf).on(cf.GRAPH_NAME.eq(o.GRAPH_NAME), cf.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(at, o.KIND.eq("FIELD"), p.KIND.eq("FIELD"))
            .and(notExists(selectOne().from(po)
                .where(po.GRAPH_NAME.eq(p.GRAPH_NAME), po.COORDINATE.eq(p.COORDINATE),
                    po.KIND.eq(OBJECT), po.TOUCHED_AT.eq(touchedAt))))
            .and(notExists(selectOne().from(ef)
                .where(ef.GRAPH_NAME.eq(cf.GRAPH_NAME), ef.TYPE_NAME.eq(cf.TYPE_NAME),
                    ef.FIELD_NAME.eq(cf.FIELD_NAME))));
    }

    /**
     * A nested field under a parent whose service hands back a class that is
     * no table's record, or whose {@code @externalField}'s {@code Field} carries one, reads an
     * instance of that class, the parent's own method's and no class the type may otherwise be
     * backed by.
     */
    private static Select<Record> object(GraphitronOccurrence o, GraphitronOccurrence p,
                                         Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var cf = GRAPHITRON_FIELD.as("cf");
        var ps = GRAPHITRON_PRODUCER_SERVICE.as("ps");
        var m = CODE_METHOD.as("m");
        var xr = GRAPHITRON_CODE_REFERENCE.as("xr");
        var xm = CODE_METHOD.as("xm");
        var gs = STORE_GRAPH_SOURCE.as("gs");
        var st = SQL_TABLE.as("st");
        var po = GRAPHITRON_OCCURRENCE_SOURCE.as("po");
        var ef = GRAPHITRON_ERRORS_FIELD.as("ef");
        // The class the parent hands over: its service's result, or the class its Field carries.
        Field<String> handed = coalesce(m.RESULT_ELEMENT_CLASS, xm.RESULT_FIRST_ARGUMENT_CLASS);
        return row(o, OBJECT, null, null, null, handed)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .leftJoin(ps).on(ps.GRAPH_NAME.eq(pf.GRAPH_NAME), ps.TYPE_NAME.eq(pf.TYPE_NAME),
                ps.FIELD_NAME.eq(pf.FIELD_NAME))
            .leftJoin(m).on(m.SOURCE_NAME.eq(ps.METHOD_SOURCE_NAME), m.CLASS_NAME.eq(ps.METHOD_CLASS_NAME),
                m.METHOD_NAME.eq(ps.METHOD_NAME), m.DESCRIPTOR.eq(ps.METHOD_DESCRIPTOR))
            .leftJoin(xr).on(xr.GRAPH_NAME.eq(pf.GRAPH_NAME), xr.COORDINATE.eq(pf.COORDINATE),
                xr.DIRECTIVE_NAME.eq("externalField"), xr.KIND.eq("ROOT"))
            .leftJoin(xm).on(xm.SOURCE_NAME.eq(xr.METHOD_SOURCE_NAME), xm.CLASS_NAME.eq(xr.METHOD_CLASS_NAME),
                xm.METHOD_NAME.eq(xr.METHOD_NAME), xm.DESCRIPTOR.eq(xr.METHOD_DESCRIPTOR))
            .join(cf).on(cf.GRAPH_NAME.eq(o.GRAPH_NAME), cf.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(at, o.KIND.eq("FIELD"), p.KIND.eq("FIELD"), handed.isNotNull())
            .and(notExists(selectOne().from(gs)
                .join(st).on(st.SOURCE_NAME.eq(gs.SOURCE_NAME))
                .where(gs.GRAPH_NAME.eq(o.GRAPH_NAME), st.RECORD_CLASS_FQN.eq(handed))))
            .and(notExists(selectOne().from(po)
                .where(po.GRAPH_NAME.eq(p.GRAPH_NAME), po.COORDINATE.eq(p.COORDINATE),
                    po.KIND.eq(OBJECT), po.TOUCHED_AT.eq(touchedAt))))
            .and(notExists(selectOne().from(ef)
                .where(ef.GRAPH_NAME.eq(cf.GRAPH_NAME), ef.TYPE_NAME.eq(cf.TYPE_NAME),
                    ef.FIELD_NAME.eq(cf.FIELD_NAME))));
    }

    /**
     * A field under an occurrence whose accessor path this reading stated reads an instance of the
     * class that path reaches, its parent's value: the object side reaching past the producer's own
     * object as the table side reaches past its first table. Not where the class is a table's record,
     * nor for an errors field, as for the producer's own
     * object.
     */
    private static Select<Record> objectMember(GraphitronOccurrence o, GraphitronOccurrence p,
                                               Condition at, LocalDateTime touchedAt) {
        var pa = GRAPHITRON_ACCESSOR_PATH.as("pa");
        var cf = GRAPHITRON_FIELD.as("cf");
        var gs = STORE_GRAPH_SOURCE.as("gs");
        var st = SQL_TABLE.as("st");
        var ef = GRAPHITRON_ERRORS_FIELD.as("ef");
        return row(o, OBJECT, null, null, null, pa.TARGET_CLASS_NAME)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pa).on(pa.GRAPH_NAME.eq(p.GRAPH_NAME), pa.COORDINATE.eq(p.COORDINATE),
                pa.TOUCHED_AT.eq(touchedAt))
            .join(cf).on(cf.GRAPH_NAME.eq(o.GRAPH_NAME), cf.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(at, o.KIND.eq("FIELD"))
            .and(notExists(selectOne().from(gs)
                .join(st).on(st.SOURCE_NAME.eq(gs.SOURCE_NAME))
                .where(gs.GRAPH_NAME.eq(o.GRAPH_NAME), st.RECORD_CLASS_FQN.eq(pa.TARGET_CLASS_NAME))))
            .and(notExists(selectOne().from(ef)
                .where(ef.GRAPH_NAME.eq(cf.GRAPH_NAME), ef.TYPE_NAME.eq(cf.TYPE_NAME),
                    ef.FIELD_NAME.eq(cf.FIELD_NAME))));
    }

    /**
     * The payload a root write with no source produces: a field of it whose target type is bound to
     * a table is a producer of its own, lifted from the keys the write returned, and reads its
     * target's table. A payload field whose target binds no table reads what the write produced and
     * has no source until that result is stated; the errors field reads none.
     */
    private static Select<Record> writePayload(GraphitronOccurrence o, GraphitronOccurrence p,
                                               Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var cf = GRAPHITRON_FIELD.as("cf");
        var ro = GRAPHQL_ROOT_OPERATION.as("ro");
        var pw = GRAPHITRON_PRODUCER_WRITE.as("pw");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        var po = GRAPHITRON_OCCURRENCE_SOURCE.as("po");
        var ef = GRAPHITRON_ERRORS_FIELD.as("ef");
        return row(o, TABLE, tt.TABLE_SOURCE_NAME, tt.TABLE_SCHEMA, tt.TABLE_NAME, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(ro).on(ro.GRAPH_NAME.eq(pf.GRAPH_NAME), ro.TYPE_NAME.eq(pf.TYPE_NAME))
            .join(pw).on(pw.GRAPH_NAME.eq(pf.GRAPH_NAME), pw.TYPE_NAME.eq(pf.TYPE_NAME),
                pw.FIELD_NAME.eq(pf.FIELD_NAME))
            .join(cf).on(cf.GRAPH_NAME.eq(o.GRAPH_NAME), cf.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(cf.GRAPH_NAME), ft.TYPE_NAME.eq(cf.TYPE_NAME),
                ft.FIELD_NAME.eq(cf.FIELD_NAME))
            .join(tt).on(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
            .where(at, o.KIND.eq("FIELD"), p.KIND.eq("FIELD"))
            .and(notExists(selectOne().from(po)
                .where(po.GRAPH_NAME.eq(p.GRAPH_NAME), po.COORDINATE.eq(p.COORDINATE),
                    po.TOUCHED_AT.eq(touchedAt))))
            .and(notExists(selectOne().from(ef)
                .where(ef.GRAPH_NAME.eq(cf.GRAPH_NAME), ef.TYPE_NAME.eq(cf.TYPE_NAME),
                    ef.FIELD_NAME.eq(cf.FIELD_NAME))));
    }

    /**
     * A field of the payload a root write with no source produces whose target, a union or an
     * interface, binds no table of its own reads each table its members are bound to, a row per
     * table: the tables its lift from the returned keys lands on.
     */
    private static Select<Record> writePayloadMember(GraphitronOccurrence o, GraphitronOccurrence p,
                                                     Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var cf = GRAPHITRON_FIELD.as("cf");
        var ro = GRAPHQL_ROOT_OPERATION.as("ro");
        var pw = GRAPHITRON_PRODUCER_WRITE.as("pw");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var pm = GRAPHQL_POLY_MEMBER.as("pm");
        var mt = GRAPHITRON_TABLETYPE.as("mt");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        var po = GRAPHITRON_OCCURRENCE_SOURCE.as("po");
        var ef = GRAPHITRON_ERRORS_FIELD.as("ef");
        return row(o, TABLE, mt.TABLE_SOURCE_NAME, mt.TABLE_SCHEMA, mt.TABLE_NAME, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(ro).on(ro.GRAPH_NAME.eq(pf.GRAPH_NAME), ro.TYPE_NAME.eq(pf.TYPE_NAME))
            .join(pw).on(pw.GRAPH_NAME.eq(pf.GRAPH_NAME), pw.TYPE_NAME.eq(pf.TYPE_NAME),
                pw.FIELD_NAME.eq(pf.FIELD_NAME))
            .join(cf).on(cf.GRAPH_NAME.eq(o.GRAPH_NAME), cf.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(cf.GRAPH_NAME), ft.TYPE_NAME.eq(cf.TYPE_NAME),
                ft.FIELD_NAME.eq(cf.FIELD_NAME))
            .join(pm).on(pm.GRAPH_NAME.eq(ft.GRAPH_NAME), pm.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME))
            .join(mt).on(mt.GRAPH_NAME.eq(pm.GRAPH_NAME), mt.TYPE_NAME.eq(pm.MEMBER_TYPE_NAME))
            .where(at, o.KIND.eq("FIELD"), p.KIND.eq("FIELD"))
            .and(notExists(selectOne().from(tt)
                .where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))))
            .and(notExists(selectOne().from(po)
                .where(po.GRAPH_NAME.eq(p.GRAPH_NAME), po.COORDINATE.eq(p.COORDINATE),
                    po.TOUCHED_AT.eq(touchedAt))))
            .and(notExists(selectOne().from(ef)
                .where(ef.GRAPH_NAME.eq(cf.GRAPH_NAME), ef.TYPE_NAME.eq(cf.TYPE_NAME),
                    ef.FIELD_NAME.eq(cf.FIELD_NAME))));
    }

    /**
     * A nested field of a plain type under a parent with a table source, and no service or
     * {@code @externalField} of its own handing it an object, reads its parent's tables; an errors
     * field reads none, a field whose parent field writes a path reads where that path lands
     * instead, and a field of a connection reads none, binding into the page its returning producer
     * produces.
     */
    private static Select<Record> nestedCopy(GraphitronOccurrence o, GraphitronOccurrence p,
                                             Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var cf = GRAPHITRON_FIELD.as("cf");
        var pt = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("pt");
        var pc = GRAPHITRON_PRODUCER_CLAIM.as("pc");
        var xr = GRAPHITRON_CODE_REFERENCE.as("xr");
        var xm = CODE_METHOD.as("xm");
        var ef = GRAPHITRON_ERRORS_FIELD.as("ef");
        var pw = GRAPHITRON_FIELD_TARGET.as("pw");
        return row(o, TABLE, pt.TABLE_SOURCE_NAME, pt.TABLE_SCHEMA, pt.TABLE_NAME, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(pt).on(pt.GRAPH_NAME.eq(p.GRAPH_NAME), pt.COORDINATE.eq(p.COORDINATE),
                pt.TOUCHED_AT.eq(touchedAt))
            .join(cf).on(cf.GRAPH_NAME.eq(o.GRAPH_NAME), cf.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(at, o.KIND.eq("FIELD"), p.KIND.eq("FIELD"))
            // A written service decides, resolved or not: an unresolved one is its own defect, and
            // its children read nothing rather than the parent's row.
            .and(notExists(selectOne().from(pc)
                .where(pc.GRAPH_NAME.eq(pf.GRAPH_NAME), pc.COORDINATE.eq(pf.COORDINATE),
                    pc.KIND.eq("SERVICE"))))
            .and(notExists(selectOne().from(xr)
                .join(xm).on(xm.SOURCE_NAME.eq(xr.METHOD_SOURCE_NAME),
                    xm.CLASS_NAME.eq(xr.METHOD_CLASS_NAME), xm.METHOD_NAME.eq(xr.METHOD_NAME),
                    xm.DESCRIPTOR.eq(xr.METHOD_DESCRIPTOR))
                .where(xr.GRAPH_NAME.eq(pf.GRAPH_NAME), xr.COORDINATE.eq(pf.COORDINATE),
                    xr.DIRECTIVE_NAME.eq("externalField"), xr.KIND.eq("ROOT"),
                    xm.RESULT_FIRST_ARGUMENT_CLASS.isNotNull())))
            .andNot(writesPath(pf))
            .and(notExists(selectOne().from(ef)
                .where(ef.GRAPH_NAME.eq(cf.GRAPH_NAME), ef.TYPE_NAME.eq(cf.TYPE_NAME),
                    ef.FIELD_NAME.eq(cf.FIELD_NAME))))
            // A connection's fields read the page its producer produces, which no table source
            // states; until that result is stated they wait.
            .and(notExists(selectOne().from(pw)
                .where(pw.GRAPH_NAME.eq(pf.GRAPH_NAME), pw.TYPE_NAME.eq(pf.TYPE_NAME),
                    pw.FIELD_NAME.eq(pf.FIELD_NAME), pw.WRAPPER_TYPE_NAME.isNotNull())));
    }

    /**
     * A nested field of a plain type under a parent field that writes a path, and no service or
     * {@code @externalField} of its own handing it an object, reads each table the parent's join
     * path reaches, a row per table; a path that stops reaches none. An errors field reads none, and
     * neither does a field of a connection.
     */
    private static Select<Record> nestedPath(GraphitronOccurrence o, GraphitronOccurrence p,
                                             Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var cf = GRAPHITRON_FIELD.as("cf");
        var j = GRAPHITRON_JOIN_PATH.as("j");
        var pc = GRAPHITRON_PRODUCER_CLAIM.as("pc");
        var xr = GRAPHITRON_CODE_REFERENCE.as("xr");
        var xm = CODE_METHOD.as("xm");
        var ef = GRAPHITRON_ERRORS_FIELD.as("ef");
        var pw = GRAPHITRON_FIELD_TARGET.as("pw");
        return row(o, TABLE, j.TARGET_SOURCE_NAME, j.TARGET_SCHEMA, j.TARGET_TABLE, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(j).on(j.GRAPH_NAME.eq(p.GRAPH_NAME), j.COORDINATE.eq(p.COORDINATE),
                j.TOUCHED_AT.eq(touchedAt))
            .join(cf).on(cf.GRAPH_NAME.eq(o.GRAPH_NAME), cf.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(at, o.KIND.eq("FIELD"), p.KIND.eq("FIELD"))
            .and(writesPath(pf))
            // A written service decides, resolved or not: an unresolved one is its own defect, and
            // its children read nothing rather than the parent's row.
            .and(notExists(selectOne().from(pc)
                .where(pc.GRAPH_NAME.eq(pf.GRAPH_NAME), pc.COORDINATE.eq(pf.COORDINATE),
                    pc.KIND.eq("SERVICE"))))
            .and(notExists(selectOne().from(xr)
                .join(xm).on(xm.SOURCE_NAME.eq(xr.METHOD_SOURCE_NAME),
                    xm.CLASS_NAME.eq(xr.METHOD_CLASS_NAME), xm.METHOD_NAME.eq(xr.METHOD_NAME),
                    xm.DESCRIPTOR.eq(xr.METHOD_DESCRIPTOR))
                .where(xr.GRAPH_NAME.eq(pf.GRAPH_NAME), xr.COORDINATE.eq(pf.COORDINATE),
                    xr.DIRECTIVE_NAME.eq("externalField"), xr.KIND.eq("ROOT"),
                    xm.RESULT_FIRST_ARGUMENT_CLASS.isNotNull())))
            .and(notExists(selectOne().from(ef)
                .where(ef.GRAPH_NAME.eq(cf.GRAPH_NAME), ef.TYPE_NAME.eq(cf.TYPE_NAME),
                    ef.FIELD_NAME.eq(cf.FIELD_NAME))))
            .and(notExists(selectOne().from(pw)
                .where(pw.GRAPH_NAME.eq(pf.GRAPH_NAME), pw.TYPE_NAME.eq(pf.TYPE_NAME),
                    pw.FIELD_NAME.eq(pf.FIELD_NAME), pw.WRAPPER_TYPE_NAME.isNotNull())));
    }

    /**
     * Whether a service call made this reading takes the argument or input field {@code o} as a
     * table's jOOQ record, as its fill states.
     */
    private static Condition takesRecord(GraphitronOccurrence o, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER.as("tc");
        return exists(selectOne().from(c)
            .where(c.GRAPH_NAME.eq(o.GRAPH_NAME), c.LANDING_COORDINATE.eq(o.COORDINATE),
                c.TOUCHED_AT.eq(touchedAt), c.CALL_KIND.eq("SERVICE"), c.RECORD_TABLE.isNotNull()));
    }

    /**
     * An argument, or an input field an argMapping entry names, that a service call takes as a jOOQ
     * record reads the table the record is a row of, whatever its field's target: the input fills
     * that record.
     */
    private static Select<Record> recordParameter(GraphitronOccurrence o, Condition at,
                                                  LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER.as("rc");
        return row(o, TABLE, c.RECORD_SOURCE_NAME, c.RECORD_SCHEMA, c.RECORD_TABLE, null)
            .from(o)
            .join(c).on(c.GRAPH_NAME.eq(o.GRAPH_NAME), c.LANDING_COORDINATE.eq(o.COORDINATE),
                c.TOUCHED_AT.eq(touchedAt), c.CALL_KIND.eq("SERVICE"), c.RECORD_TABLE.isNotNull())
            .where(at, o.KIND.in("FIELD_ARGUMENT", "INPUT_FIELD"));
    }

    /**
     * An argument of a field whose target type is bound to a table reads that table, where the field
     * writes no path.
     */
    private static Select<Record> argumentTarget(GraphitronOccurrence o, GraphitronOccurrence p,
                                                 Condition at) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        return row(o, TABLE, tt.TABLE_SOURCE_NAME, tt.TABLE_SCHEMA, tt.TABLE_NAME, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(pf.GRAPH_NAME), ft.TYPE_NAME.eq(pf.TYPE_NAME),
                ft.FIELD_NAME.eq(pf.FIELD_NAME))
            .join(tt).on(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
            .where(at, o.KIND.eq("FIELD_ARGUMENT"))
            .andNot(writesPath(pf));
    }

    /**
     * An argument of a field whose target, a union or an interface, binds no table of its own reads
     * each table its members are bound to, a row per table, where the field writes no path.
     */
    private static Select<Record> argumentMember(GraphitronOccurrence o, GraphitronOccurrence p,
                                                 Condition at) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var pm = GRAPHQL_POLY_MEMBER.as("pm");
        var mt = GRAPHITRON_TABLETYPE.as("mt");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        return row(o, TABLE, mt.TABLE_SOURCE_NAME, mt.TABLE_SCHEMA, mt.TABLE_NAME, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(pf.GRAPH_NAME), ft.TYPE_NAME.eq(pf.TYPE_NAME),
                ft.FIELD_NAME.eq(pf.FIELD_NAME))
            .join(pm).on(pm.GRAPH_NAME.eq(ft.GRAPH_NAME), pm.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME))
            .join(mt).on(mt.GRAPH_NAME.eq(pm.GRAPH_NAME), mt.TYPE_NAME.eq(pm.MEMBER_TYPE_NAME))
            .where(at, o.KIND.eq("FIELD_ARGUMENT"))
            .andNot(writesPath(pf))
            .and(notExists(selectOne().from(tt)
                .where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))));
    }

    /**
     * An argument of a write whose target binds no table, a scalar, an enum or the payload object
     * graphitron builds, reads the table the write mutates, where the write writes no path.
     */
    private static Select<Record> argumentWrite(GraphitronOccurrence o, GraphitronOccurrence p,
                                                Condition at) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var pw = GRAPHITRON_PRODUCER_WRITE.as("pw");
        var gt = GRAPHITRON_TYPE.as("gt");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        var pm = GRAPHQL_POLY_MEMBER.as("pm");
        var mt = GRAPHITRON_TABLETYPE.as("mt");
        return row(o, TABLE, pw.TABLE_SOURCE_NAME, pw.TABLE_SCHEMA, pw.TABLE_NAME, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(pf.GRAPH_NAME), ft.TYPE_NAME.eq(pf.TYPE_NAME),
                ft.FIELD_NAME.eq(pf.FIELD_NAME))
            .join(pw).on(pw.GRAPH_NAME.eq(pf.GRAPH_NAME), pw.TYPE_NAME.eq(pf.TYPE_NAME),
                pw.FIELD_NAME.eq(pf.FIELD_NAME))
            .where(at, o.KIND.eq("FIELD_ARGUMENT"))
            .andNot(writesPath(pf))
            .and(notExists(selectOne().from(gt)
                .where(gt.GRAPH_NAME.eq(ft.GRAPH_NAME), gt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME),
                    gt.KIND.in("INTERFACE", "UNION"))))
            .and(notExists(selectOne().from(tt)
                .where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))))
            .and(notExists(selectOne().from(pm)
                .join(mt).on(mt.GRAPH_NAME.eq(pm.GRAPH_NAME), mt.TYPE_NAME.eq(pm.MEMBER_TYPE_NAME))
                .where(pm.GRAPH_NAME.eq(ft.GRAPH_NAME), pm.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME))));
    }

    /**
     * An argument of a field whose target is bound to no table, a nested object, a scalar or an
     * enum, reads its field's own tables, the field's path being empty; a write's arguments read the
     * write's table instead, and the arguments of a field that writes a path the tables it lands on.
     */
    private static Select<Record> argumentCopy(GraphitronOccurrence o, GraphitronOccurrence p,
                                               Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var pt = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("pt");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        var pm = GRAPHQL_POLY_MEMBER.as("pm");
        var mt = GRAPHITRON_TABLETYPE.as("mt");
        var pw = GRAPHITRON_PRODUCER_WRITE.as("pw");
        var gt = GRAPHITRON_TYPE.as("gt");
        return row(o, TABLE, pt.TABLE_SOURCE_NAME, pt.TABLE_SCHEMA, pt.TABLE_NAME, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(pf.GRAPH_NAME), ft.TYPE_NAME.eq(pf.TYPE_NAME),
                ft.FIELD_NAME.eq(pf.FIELD_NAME))
            .join(pt).on(pt.GRAPH_NAME.eq(p.GRAPH_NAME), pt.COORDINATE.eq(p.COORDINATE),
                pt.TOUCHED_AT.eq(touchedAt))
            .where(at, o.KIND.eq("FIELD_ARGUMENT"))
            .and(notExists(selectOne().from(tt)
                .where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))))
            .and(notExists(selectOne().from(pm)
                .join(mt).on(mt.GRAPH_NAME.eq(pm.GRAPH_NAME), mt.TYPE_NAME.eq(pm.MEMBER_TYPE_NAME))
                .where(pm.GRAPH_NAME.eq(ft.GRAPH_NAME), pm.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME))))
            .and(notExists(selectOne().from(pw)
                .where(pw.GRAPH_NAME.eq(pf.GRAPH_NAME), pw.TYPE_NAME.eq(pf.TYPE_NAME),
                    pw.FIELD_NAME.eq(pf.FIELD_NAME))
                .andNot(exists(selectOne().from(gt)
                    .where(gt.GRAPH_NAME.eq(ft.GRAPH_NAME), gt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME),
                        gt.KIND.in("INTERFACE", "UNION"))))))
            .andNot(writesPath(pf));
    }

    /**
     * An argument of a field that writes a path reads each table the field's join path reaches, a
     * row per table, whatever the field's target, a {@code @routine}'s result included; a path that
     * stops reaches none.
     */
    private static Select<Record> argumentPath(GraphitronOccurrence o, GraphitronOccurrence p,
                                               Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var j = GRAPHITRON_JOIN_PATH.as("j");
        return row(o, TABLE, j.TARGET_SOURCE_NAME, j.TARGET_SCHEMA, j.TARGET_TABLE, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(j).on(j.GRAPH_NAME.eq(p.GRAPH_NAME), j.COORDINATE.eq(p.COORDINATE),
                j.TOUCHED_AT.eq(touchedAt))
            .where(at, o.KIND.eq("FIELD_ARGUMENT"))
            .and(writesPath(pf));
    }

    /**
     * Whether the field {@code pf} writes a path, which its join path resolves, as its {@code FIELD}
     * claim's places state; a root field writing only its leading @routine, its producer's call,
     * writes none.
     */
    private static Condition writesPath(GraphitronField pf) {
        var c = GRAPHITRON_JOIN_PATH_CLAIM.as("wpc");
        return exists(selectOne().from(c)
            .where(c.GRAPH_NAME.eq(pf.GRAPH_NAME), c.ELEMENT_COORDINATE.eq(pf.COORDINATE),
                c.CLAIM_KIND.eq("FIELD"), c.LAST_PLACE.ge(c.FIRST_PLACE)));
    }

    /** An input field reads its parent argument's or input field's tables. */
    private static Select<Record> inputFieldCopy(GraphitronOccurrence o, GraphitronOccurrence p,
                                                 Condition at, LocalDateTime touchedAt) {
        var pt = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("pt");
        return row(o, TABLE, pt.TABLE_SOURCE_NAME, pt.TABLE_SCHEMA, pt.TABLE_NAME, null)
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pt).on(pt.GRAPH_NAME.eq(p.GRAPH_NAME), pt.COORDINATE.eq(p.COORDINATE),
                pt.TOUCHED_AT.eq(touchedAt))
            .where(at, o.KIND.eq("INPUT_FIELD"));
    }
}
