package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.tables.GraphitronOccurrence;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.SelectField;
import org.jooq.SelectSelectStep;
import org.jooq.impl.SQLDataType;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_ROUTINE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ELEMENT_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.castNull;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

/**
 * The capture-cadence writer of {@code graphitron_path_need}: every type an occurrence with a table
 * source must reach, with the source table it starts on and the table it lands on, marked and swept.
 *
 * <p>Written a depth at a time over the occurrences, one {@code INSERT .. SELECT} per depth with the
 * instant and the depth bound. A field reaches its target where the target has a table of its own
 * and is no interface; each member with a table of a single-table interface, a multi-table
 * interface or a union; and otherwise its target type itself, landing on its start, or on no table
 * where its declaration writes a path, a reader asking the join path where such a field lands. An
 * argument copies its field's needs, starting where the field lands for each type, where its join
 * path reaches for a field that writes a path; under a field with no needs, a root field with no
 * path, it expands the field's target as a field's needs do, starting on the type's table, an
 * interface's own table for its members where it has one, or on its own one source table where the
 * type has none. A
 * connection's pagination arguments' needs stand until the connection's binding states what they
 * take. An input field copies its parent's needs and starts, landing where its parent lands, or on
 * its parent's start where the parent writes a path. An argument or input field writing a path for a
 * need, its own {@code @reference} or a {@code @referenceFor} naming the need's type, lands on no
 * table yet, its join path saying where.
 *
 * <p>A need's start is a reference to one of the occurrence's {@code graphitron_occurrence_source_table}
 * rows; a start the rules give that is none of them keeps a null reference, which says so.
 *
 * <p>A step of the assembly's per-depth loop: reads the sources {@link OccurrenceSources} writes for
 * the same depth, and the needs it wrote and the join paths {@link Paths} and {@link Steps} wrote
 * for the depth above. {@link #sweep} runs after the last depth.
 */
public final class PathNeeds {

    private PathNeeds() {}

    /** States the needs of the occurrences at {@code depth}. */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        requireOneSourceTablePerField(dsl, graph, touchedAt, depth);
        write(dsl, graph, candidates(graph, touchedAt, depth), touchedAt);
        writeObject(dsl, graph, depth, touchedAt);
    }

    /**
     * Upserts one depth's needs that start on an object: a field read off an object source needs its
     * target type, starting on the class the source names; the need, then its object side.
     */
    private static void writeObject(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE.as("o");
        var so = GRAPHITRON_OCCURRENCE_SOURCE_OBJECT.as("so");
        var f = GRAPHITRON_FIELD.as("f");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var t = GRAPHITRON_TYPE.as("t");
        var n = GRAPHITRON_PATH_NEED;
        // A need that started on a table goes, with its table side and path, where the occurrence now
        // reads off an object.
        var sx = GRAPHITRON_OCCURRENCE_SOURCE_OBJECT.as("sx");
        dsl.deleteFrom(n)
            .where(n.GRAPH_NAME.eq(graph), n.START_KIND.ne(OBJECT))
            .and(exists(selectOne().from(sx).where(sx.GRAPH_NAME.eq(n.GRAPH_NAME),
                sx.COORDINATE.eq(n.COORDINATE), sx.TOUCHED_AT.eq(touchedAt))))
            .execute();
        dsl.insertInto(n)
            .columns(n.GRAPH_NAME, n.COORDINATE, n.TARGET_TYPE, n.START_KIND, n.TOUCHED_AT,
                n.TARGET_GRAPH_REF, n.TARGET_TYPE_REF)
            .select(selectDistinct(o.GRAPH_NAME, o.COORDINATE, ft.TARGET_TYPE_NAME, inline(OBJECT),
                    val(touchedAt, n.TOUCHED_AT), t.GRAPH_NAME, t.TYPE_NAME)
                .from(o)
                .join(so).on(so.GRAPH_NAME.eq(o.GRAPH_NAME), so.COORDINATE.eq(o.COORDINATE),
                    so.TOUCHED_AT.eq(touchedAt))
                .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
                .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                    ft.FIELD_NAME.eq(f.FIELD_NAME))
                .leftJoin(t).on(t.GRAPH_NAME.eq(ft.GRAPH_NAME), t.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
                .where(o.GRAPH_NAME.eq(graph), o.TOUCHED_AT.eq(touchedAt), o.DEPTH.eq(depth),
                    o.KIND.eq("FIELD")))
            .onConflict(n.GRAPH_NAME, n.COORDINATE, n.TARGET_TYPE)
            .doUpdate()
            .set(n.START_KIND, excluded(n.START_KIND))
            .set(n.TOUCHED_AT, excluded(n.TOUCHED_AT))
            .set(n.TARGET_GRAPH_REF, excluded(n.TARGET_GRAPH_REF))
            .set(n.TARGET_TYPE_REF, excluded(n.TARGET_TYPE_REF))
            .execute();
        var no = GRAPHITRON_PATH_NEED_OBJECT;
        var nn = GRAPHITRON_PATH_NEED.as("nn");
        dsl.insertInto(no)
            .columns(no.GRAPH_NAME, no.COORDINATE, no.TARGET_TYPE, no.START_KIND, no.START_CLASS,
                no.TOUCHED_AT, no.START_GRAPH_REF, no.START_COORDINATE_REF)
            .select(selectDistinct(nn.GRAPH_NAME, nn.COORDINATE, nn.TARGET_TYPE, nn.START_KIND,
                    so.CLASS_NAME, val(touchedAt, no.TOUCHED_AT), so.GRAPH_NAME, so.COORDINATE)
                .from(nn)
                .join(o).on(o.GRAPH_NAME.eq(nn.GRAPH_NAME), o.COORDINATE.eq(nn.COORDINATE))
                .join(so).on(so.GRAPH_NAME.eq(nn.GRAPH_NAME), so.COORDINATE.eq(nn.COORDINATE))
                .where(nn.GRAPH_NAME.eq(graph), nn.TOUCHED_AT.eq(touchedAt), nn.START_KIND.eq(OBJECT),
                    o.DEPTH.eq(depth)))
            .onConflict(no.GRAPH_NAME, no.COORDINATE, no.TARGET_TYPE)
            .doUpdate()
            .set(no.START_CLASS, excluded(no.START_CLASS))
            .set(no.TOUCHED_AT, excluded(no.TOUCHED_AT))
            .set(no.START_GRAPH_REF, excluded(no.START_GRAPH_REF))
            .set(no.START_COORDINATE_REF, excluded(no.START_COORDINATE_REF))
            .execute();
    }

    /** A need that starts on the object an occurrence's values are read off. */
    private static final String OBJECT = "OBJECT";

    /** Deletes the needs no depth of this reading marked. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var n = GRAPHITRON_PATH_NEED;
        dsl.deleteFrom(n)
            .where(n.GRAPH_NAME.eq(graph))
            .and(n.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /**
     * A field's need starts on its one source table: a field under a parent that forks is an
     * occurrence per participant, so no field has two.
     */
    private static void requireOneSourceTablePerField(DSLContext dsl, String graph,
                                                      LocalDateTime touchedAt, int depth) {
        var o = GRAPHITRON_OCCURRENCE;
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
        var forked = dsl.select(o.COORDINATE).from(o)
            .join(st).on(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE),
                st.TOUCHED_AT.eq(touchedAt))
            .where(o.GRAPH_NAME.eq(graph), o.TOUCHED_AT.eq(touchedAt), o.DEPTH.eq(depth),
                o.KIND.eq("FIELD"))
            .groupBy(o.COORDINATE)
            .having(count().gt(1))
            .limit(5)
            .fetch(o.COORDINATE);
        if (!forked.isEmpty()) {
            throw new IllegalStateException("A field occurrence has several source tables, which no "
                + "rule gives a field: " + forked);
        }
    }

    /**
     * Upserts one depth's needs, the need and then its table side, each start, target type and
     * landing referenced where it stands. The rule is read once per relation: the need's subtype
     * references the need, so the need is written first.
     */
    private static void write(DSLContext dsl, String graph, Select<Record> rows,
                              LocalDateTime touchedAt) {
        var c = rows.asTable("c");
        var cGraph = c.field("graph_name", String.class);
        var cCoordinate = c.field("coordinate", String.class);
        var cTarget = c.field("target_type", String.class);
        var cStartSource = c.field("start_source_name", String.class);
        var cStartSchema = c.field("start_schema", String.class);
        var cStartTable = c.field("start_table", String.class);
        var cLandingSource = c.field("landing_source_name", String.class);
        var cLandingSchema = c.field("landing_schema", String.class);
        var cLandingTable = c.field("landing_table", String.class);
        var n = GRAPHITRON_PATH_NEED;
        var t = GRAPHITRON_TYPE.as("t");
        // A need that started on an object goes, with its object side and path, where the occurrence
        // now reads off a table.
        var sx = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("sx");
        dsl.deleteFrom(n)
            .where(n.GRAPH_NAME.eq(graph), n.START_KIND.ne(TABLE))
            .and(exists(selectOne().from(sx).where(sx.GRAPH_NAME.eq(n.GRAPH_NAME),
                sx.COORDINATE.eq(n.COORDINATE), sx.TOUCHED_AT.eq(touchedAt))))
            .execute();
        dsl.insertInto(n)
            .columns(n.GRAPH_NAME, n.COORDINATE, n.TARGET_TYPE, n.START_KIND, n.TOUCHED_AT,
                n.TARGET_GRAPH_REF, n.TARGET_TYPE_REF)
            .select(selectDistinct(cGraph, cCoordinate, cTarget, inline(TABLE),
                    val(touchedAt, n.TOUCHED_AT), t.GRAPH_NAME, t.TYPE_NAME)
                .from(c)
                .leftJoin(t).on(t.GRAPH_NAME.eq(cGraph), t.TYPE_NAME.eq(cTarget)))
            .onConflict(n.GRAPH_NAME, n.COORDINATE, n.TARGET_TYPE)
            .doUpdate()
            .set(n.START_KIND, excluded(n.START_KIND))
            .set(n.TOUCHED_AT, excluded(n.TOUCHED_AT))
            .set(n.TARGET_GRAPH_REF, excluded(n.TARGET_GRAPH_REF))
            .set(n.TARGET_TYPE_REF, excluded(n.TARGET_TYPE_REF))
            .execute();

        var nt = GRAPHITRON_PATH_NEED_TABLE;
        var s = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("s");
        var lt = SQL_TABLE.as("lt");
        dsl.insertInto(nt)
            .columns(nt.GRAPH_NAME, nt.COORDINATE, nt.TARGET_TYPE, nt.START_KIND,
                nt.START_SOURCE_NAME, nt.START_SCHEMA, nt.START_TABLE, nt.LANDING_SOURCE_NAME,
                nt.LANDING_SCHEMA, nt.LANDING_TABLE, nt.TOUCHED_AT,
                nt.START_GRAPH_REF, nt.START_COORDINATE_REF, nt.START_SOURCE_REF, nt.START_SCHEMA_REF,
                nt.START_TABLE_REF, nt.LANDING_SOURCE_REF, nt.LANDING_SCHEMA_REF, nt.LANDING_TABLE_REF)
            .select(selectDistinct(cGraph, cCoordinate, cTarget, inline(TABLE), cStartSource,
                    cStartSchema, cStartTable, cLandingSource, cLandingSchema, cLandingTable,
                    val(touchedAt, nt.TOUCHED_AT),
                    s.GRAPH_NAME, s.COORDINATE, s.TABLE_SOURCE_NAME, s.TABLE_SCHEMA, s.TABLE_NAME,
                    lt.SOURCE_NAME, lt.TABLE_SCHEMA, lt.TABLE_NAME)
                .from(c)
                .leftJoin(s).on(s.GRAPH_NAME.eq(cGraph), s.COORDINATE.eq(cCoordinate),
                    s.TABLE_SOURCE_NAME.eq(cStartSource), s.TABLE_SCHEMA.eq(cStartSchema),
                    s.TABLE_NAME.eq(cStartTable), s.TOUCHED_AT.eq(touchedAt))
                .leftJoin(lt).on(lt.SOURCE_NAME.eq(cLandingSource), lt.TABLE_SCHEMA.eq(cLandingSchema),
                    lt.TABLE_NAME.eq(cLandingTable)))
            .onConflict(nt.GRAPH_NAME, nt.COORDINATE, nt.TARGET_TYPE)
            .doUpdate()
            .set(nt.START_SOURCE_NAME, excluded(nt.START_SOURCE_NAME))
            .set(nt.START_SCHEMA, excluded(nt.START_SCHEMA))
            .set(nt.START_TABLE, excluded(nt.START_TABLE))
            .set(nt.LANDING_SOURCE_NAME, excluded(nt.LANDING_SOURCE_NAME))
            .set(nt.LANDING_SCHEMA, excluded(nt.LANDING_SCHEMA))
            .set(nt.LANDING_TABLE, excluded(nt.LANDING_TABLE))
            .set(nt.TOUCHED_AT, excluded(nt.TOUCHED_AT))
            .set(nt.START_GRAPH_REF, excluded(nt.START_GRAPH_REF))
            .set(nt.START_COORDINATE_REF, excluded(nt.START_COORDINATE_REF))
            .set(nt.START_SOURCE_REF, excluded(nt.START_SOURCE_REF))
            .set(nt.START_SCHEMA_REF, excluded(nt.START_SCHEMA_REF))
            .set(nt.START_TABLE_REF, excluded(nt.START_TABLE_REF))
            .set(nt.LANDING_SOURCE_REF, excluded(nt.LANDING_SOURCE_REF))
            .set(nt.LANDING_SCHEMA_REF, excluded(nt.LANDING_SCHEMA_REF))
            .set(nt.LANDING_TABLE_REF, excluded(nt.LANDING_TABLE_REF))
            .execute();
    }

    /** A need that starts on one of the occurrence's source tables. */
    private static final String TABLE = "TABLE";

    /** Every need the rules give the occurrences at {@code depth}. */
    private static Select<Record> candidates(String graph, LocalDateTime touchedAt, int depth) {
        var o = GRAPHITRON_OCCURRENCE.as("o");
        Condition at = o.GRAPH_NAME.eq(graph).and(o.TOUCHED_AT.eq(touchedAt))
            .and(o.DEPTH.eq(val(depth)));
        var fields = fieldTableBound(o, at, touchedAt)
            .union(fieldMember(o, at, touchedAt))
            .union(fieldUnbound(o, at, touchedAt));
        if (depth == 0) {
            return fields;
        }
        var p = GRAPHITRON_OCCURRENCE.as("p");
        // An occurrence a service call takes as a table's record reaches that table and nothing else.
        Condition record = at.and(takesRecord(o, touchedAt));
        at = at.andNot(takesRecord(o, touchedAt));
        return fields
            .union(recordParameter(o, record, touchedAt))
            .union(argumentCopy(o, p, at, touchedAt))
            .union(argumentTableBound(o, p, at, touchedAt))
            .union(argumentMember(o, p, at, touchedAt))
            .union(argumentUnbound(o, p, at, touchedAt))
            .union(inputFieldCopy(o, p, at, touchedAt));
    }

    private static Field<String> none(String name) {
        return castNull(SQLDataType.VARCHAR).as(name);
    }

    /** The row every rule selects, in one column order. */
    private static SelectSelectStep<Record> row(
            GraphitronOccurrence o, Field<String> target,
            Field<String> startSource, Field<String> startSchema, Field<String> startTable,
            Field<String> landingSource, Field<String> landingSchema, Field<String> landingTable) {
        return selectDistinct(List.<SelectField<?>>of(o.GRAPH_NAME.as("graph_name"),
            o.COORDINATE.as("coordinate"), target.as("target_type"),
            startSource.as("start_source_name"), startSchema.as("start_schema"),
            startTable.as("start_table"),
            landingSource == null ? none("landing_source_name") : landingSource.as("landing_source_name"),
            landingSchema == null ? none("landing_schema") : landingSchema.as("landing_schema"),
            landingTable == null ? none("landing_table") : landingTable.as("landing_table")));
    }

    /**
     * Whether the element {@code o} is an occurrence of writes a path at its declaration. A root
     * field's leading @routine is its producer's call and its table the field's source already, so
     * a root field writing that alone writes none.
     */
    private static Condition writesPath(GraphitronOccurrence o) {
        var el = GRAPHQL_AST_ELEMENT_ENTRY.as("wp_el");
        var ap = GRAPHITRON_AST_PATH_ENTRY.as("wp_ap");
        var f = GRAPHITRON_FIELD.as("wp_f");
        var ro = GRAPHQL_ROOT_OPERATION.as("wp_ro");
        var re = GRAPHITRON_AST_ROUTINE_ENTRY.as("wp_re");
        return exists(selectOne().from(el)
            .join(ap).on(ap.GRAPH_NAME.eq(el.GRAPH_NAME), ap.SOURCE_NAME.eq(el.SOURCE_NAME),
                ap.ANCHOR_LINE.eq(el.SOURCE_LINE), ap.ANCHOR_COLUMN.eq(el.SOURCE_COLUMN))
            .where(el.GRAPH_NAME.eq(o.GRAPH_NAME), el.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .andNot(ap.PLACE.eq(0)
                .and(exists(selectOne().from(f)
                    .join(ro).on(ro.GRAPH_NAME.eq(f.GRAPH_NAME), ro.TYPE_NAME.eq(f.TYPE_NAME))
                    .where(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))))
                .and(exists(selectOne().from(re)
                    .where(re.GRAPH_NAME.eq(ap.GRAPH_NAME), re.SOURCE_NAME.eq(ap.SOURCE_NAME),
                        re.SOURCE_LINE.eq(ap.ELEMENT_LINE), re.SOURCE_COLUMN.eq(ap.ELEMENT_COLUMN))))));
    }

    /**
     * Whether the argument or input field {@code o} writes a path toward {@code target}: its own
     * {@code @referenceFor} naming the type, or its own {@code @reference}, which covers every type.
     */
    private static Condition claims(GraphitronOccurrence o, Field<String> target) {
        var c = GRAPHITRON_JOIN_PATH_CLAIM.as("cl_c");
        return exists(selectOne().from(c)
            .where(c.GRAPH_NAME.eq(o.GRAPH_NAME), c.ELEMENT_COORDINATE.eq(o.ELEMENT_COORDINATE))
            .and(c.CLAIM_KIND.ne("REFERENCE_FOR").or(c.TARGET_TYPE.eq(target))));
    }

    /** A field whose target has a table of its own and is no interface reaches it, landing there. */
    private static Select<Record> fieldTableBound(GraphitronOccurrence o, Condition at,
                                                  LocalDateTime touchedAt) {
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("st");
        var f = GRAPHITRON_FIELD.as("f");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var gt = GRAPHITRON_TYPE.as("gt");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        return row(o, ft.TARGET_TYPE_NAME, st.TABLE_SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME,
                tt.TABLE_SOURCE_NAME, tt.TABLE_SCHEMA, tt.TABLE_NAME)
            .from(o)
            .join(st).on(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE),
                st.TOUCHED_AT.eq(touchedAt))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            .join(gt).on(gt.GRAPH_NAME.eq(ft.GRAPH_NAME), gt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
            .join(tt).on(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
            .where(at, o.KIND.eq("FIELD"), gt.KIND.ne("INTERFACE"));
    }

    /**
     * A field whose target is an interface or a union reaches each member with a table, landing on
     * the member's table: every implementation of a single-table interface, never the interface,
     * and each table-bound member of a multi-table interface or a union.
     */
    private static Select<Record> fieldMember(GraphitronOccurrence o, Condition at,
                                              LocalDateTime touchedAt) {
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("st");
        var f = GRAPHITRON_FIELD.as("f");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var gt = GRAPHITRON_TYPE.as("gt");
        var pm = GRAPHQL_POLY_MEMBER.as("pm");
        var mt = GRAPHITRON_TABLETYPE.as("mt");
        return row(o, pm.MEMBER_TYPE_NAME, st.TABLE_SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME,
                mt.TABLE_SOURCE_NAME, mt.TABLE_SCHEMA, mt.TABLE_NAME)
            .from(o)
            .join(st).on(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE),
                st.TOUCHED_AT.eq(touchedAt))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            .join(gt).on(gt.GRAPH_NAME.eq(ft.GRAPH_NAME), gt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME),
                gt.KIND.in("INTERFACE", "UNION"))
            .join(pm).on(pm.GRAPH_NAME.eq(ft.GRAPH_NAME), pm.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME))
            .join(mt).on(mt.GRAPH_NAME.eq(pm.GRAPH_NAME), mt.TYPE_NAME.eq(pm.MEMBER_TYPE_NAME))
            .where(at, o.KIND.eq("FIELD"));
    }

    /**
     * A field whose target is bound to no table and has no member that is, a scalar, an enum, a
     * plain object or a carrier, reaches its own type: landing on its start, the path being empty,
     * or on no table where its declaration writes a path, its join path saying where it lands.
     */
    private static Select<Record> fieldUnbound(GraphitronOccurrence o, Condition at,
                                               LocalDateTime touchedAt) {
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("st");
        var f = GRAPHITRON_FIELD.as("f");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        var pm = GRAPHQL_POLY_MEMBER.as("pm");
        var mt = GRAPHITRON_TABLETYPE.as("mt");
        Condition written = writesPath(o);
        return row(o, ft.TARGET_TYPE_NAME, st.TABLE_SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME,
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(st.TABLE_SOURCE_NAME),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(st.TABLE_SCHEMA),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(st.TABLE_NAME))
            .from(o)
            .join(st).on(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE),
                st.TOUCHED_AT.eq(touchedAt))
            .join(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(f.GRAPH_NAME), ft.TYPE_NAME.eq(f.TYPE_NAME),
                ft.FIELD_NAME.eq(f.FIELD_NAME))
            .where(at, o.KIND.eq("FIELD"))
            .and(notExists(selectOne().from(tt)
                .where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))))
            .and(notExists(selectOne().from(pm)
                .join(mt).on(mt.GRAPH_NAME.eq(pm.GRAPH_NAME), mt.TYPE_NAME.eq(pm.MEMBER_TYPE_NAME))
                .where(pm.GRAPH_NAME.eq(ft.GRAPH_NAME), pm.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME))));
    }

    /**
     * An argument of a field with needs reaches each type its field reaches, starting where the
     * field lands for it: the field's landing, or, where the field writes a path and its need lands
     * on no table, the table its join path reaches; a path that stops starts nothing. Its own path
     * empty, the argument lands there too, or on no table yet where the argument writes a path.
     */
    private static Select<Record> argumentCopy(GraphitronOccurrence o, GraphitronOccurrence p,
                                               Condition at, LocalDateTime touchedAt) {
        var pn = GRAPHITRON_PATH_NEED_TABLE.as("pn");
        var j = GRAPHITRON_JOIN_PATH.as("j");
        Condition written = claims(o, pn.TARGET_TYPE);
        Condition walked = pn.LANDING_TABLE.isNull();
        Field<String> startSource = when(walked, j.TARGET_SOURCE_NAME).otherwise(pn.LANDING_SOURCE_NAME);
        Field<String> startSchema = when(walked, j.TARGET_SCHEMA).otherwise(pn.LANDING_SCHEMA);
        Field<String> startTable = when(walked, j.TARGET_TABLE).otherwise(pn.LANDING_TABLE);
        return row(o, pn.TARGET_TYPE, startSource, startSchema, startTable,
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(startSource),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(startSchema),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(startTable))
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pn).on(pn.GRAPH_NAME.eq(p.GRAPH_NAME), pn.COORDINATE.eq(p.COORDINATE),
                pn.TOUCHED_AT.eq(touchedAt))
            .leftJoin(j).on(j.GRAPH_NAME.eq(pn.GRAPH_NAME), j.COORDINATE.eq(pn.COORDINATE),
                j.TARGET_TYPE.eq(pn.TARGET_TYPE), j.TOUCHED_AT.eq(touchedAt))
            .where(at, o.KIND.eq("FIELD_ARGUMENT"), startTable.isNotNull())
            .and(sourcedAt(o, touchedAt));
    }

    /**
     * Whether a service call made this reading takes the argument or input field {@code o} as a
     * table's jOOQ record, as its fill states.
     */
    private static Condition takesRecord(GraphitronOccurrence o, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_CALL_PARAMETER.as("tc");
        return exists(selectOne().from(c)
            .where(c.GRAPH_NAME.eq(o.GRAPH_NAME), c.LANDING_COORDINATE.eq(o.COORDINATE),
                c.TOUCHED_AT.eq(touchedAt), c.RECORD_TABLE.isNotNull()));
    }

    /**
     * An argument, or an input field an argMapping entry names, that a service call takes as a jOOQ
     * record reaches its own input type, starting and landing on the table its source states, the
     * record's: the input fills that record, so the fields under it bind its columns. Where it writes
     * a path the path decides the landing. Its type is an input type, no node, so a bare
     * {@code @nodeId} under it takes the landing table's node.
     */
    private static Select<Record> recordParameter(GraphitronOccurrence o, Condition at,
                                                  LocalDateTime touchedAt) {
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("st");
        var a = GRAPHITRON_ARGUMENT.as("ra");
        var f = GRAPHITRON_FIELD.as("rf");
        Field<String> target = coalesce(a.NAMED_TYPE, f.NAMED_TYPE);
        Condition written = claims(o, target);
        return row(o, target, st.TABLE_SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME,
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(st.TABLE_SOURCE_NAME),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(st.TABLE_SCHEMA),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(st.TABLE_NAME))
            .from(o)
            .join(st).on(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE),
                st.TOUCHED_AT.eq(touchedAt))
            .leftJoin(a).on(a.GRAPH_NAME.eq(o.GRAPH_NAME), a.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .leftJoin(f).on(f.GRAPH_NAME.eq(o.GRAPH_NAME), f.COORDINATE.eq(o.ELEMENT_COORDINATE))
            .where(at, o.KIND.in("FIELD_ARGUMENT", "INPUT_FIELD"), target.isNotNull());
    }

    /** Whether the parent occurrence {@code p} has no need this reading stated. */
    private static Condition parentHasNoNeed(GraphitronOccurrence p, LocalDateTime touchedAt) {
        var pn = GRAPHITRON_PATH_NEED_TABLE.as("pn0");
        return notExists(selectOne().from(pn)
            .where(pn.GRAPH_NAME.eq(p.GRAPH_NAME), pn.COORDINATE.eq(p.COORDINATE),
                pn.TOUCHED_AT.eq(touchedAt)));
    }

    /**
     * An argument of a field with no needs, a root field with no path, whose target has a table of
     * its own and is no interface reaches it, starting and landing on that table.
     */
    private static Select<Record> argumentTableBound(GraphitronOccurrence o, GraphitronOccurrence p,
                                                     Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var gt = GRAPHITRON_TYPE.as("gt");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        Condition written = claims(o, ft.TARGET_TYPE_NAME);
        return row(o, ft.TARGET_TYPE_NAME, tt.TABLE_SOURCE_NAME, tt.TABLE_SCHEMA, tt.TABLE_NAME,
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(tt.TABLE_SOURCE_NAME),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(tt.TABLE_SCHEMA),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(tt.TABLE_NAME))
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(pf.GRAPH_NAME), ft.TYPE_NAME.eq(pf.TYPE_NAME),
                ft.FIELD_NAME.eq(pf.FIELD_NAME))
            .join(gt).on(gt.GRAPH_NAME.eq(ft.GRAPH_NAME), gt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
            .join(tt).on(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
            .where(at, o.KIND.eq("FIELD_ARGUMENT"), gt.KIND.ne("INTERFACE"))
            .and(parentHasNoNeed(p, touchedAt))
            .and(sourcedAt(o, touchedAt));
    }

    /**
     * An argument of a field with no needs whose target is an interface or a union reaches each
     * member with a table, landing on the member's table: starting on the interface's own table where
     * it has one, which is the argument's source, and otherwise on the member's. A connection's
     * pagination arguments are among these; their needs stand until the connection's binding states
     * what they take.
     */
    private static Select<Record> argumentMember(GraphitronOccurrence o, GraphitronOccurrence p,
                                                 Condition at, LocalDateTime touchedAt) {
        var pf = GRAPHITRON_FIELD.as("pf");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var gt = GRAPHITRON_TYPE.as("gt");
        var pm = GRAPHQL_POLY_MEMBER.as("pm");
        var mt = GRAPHITRON_TABLETYPE.as("mt");
        var it = GRAPHITRON_TABLETYPE.as("it");
        Condition written = claims(o, pm.MEMBER_TYPE_NAME);
        return row(o, pm.MEMBER_TYPE_NAME, coalesce(it.TABLE_SOURCE_NAME, mt.TABLE_SOURCE_NAME),
                coalesce(it.TABLE_SCHEMA, mt.TABLE_SCHEMA), coalesce(it.TABLE_NAME, mt.TABLE_NAME),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(mt.TABLE_SOURCE_NAME),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(mt.TABLE_SCHEMA),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(mt.TABLE_NAME))
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(pf.GRAPH_NAME), ft.TYPE_NAME.eq(pf.TYPE_NAME),
                ft.FIELD_NAME.eq(pf.FIELD_NAME))
            .join(gt).on(gt.GRAPH_NAME.eq(ft.GRAPH_NAME), gt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME),
                gt.KIND.in("INTERFACE", "UNION"))
            .join(pm).on(pm.GRAPH_NAME.eq(ft.GRAPH_NAME), pm.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME))
            .join(mt).on(mt.GRAPH_NAME.eq(pm.GRAPH_NAME), mt.TYPE_NAME.eq(pm.MEMBER_TYPE_NAME))
            .leftJoin(it).on(it.GRAPH_NAME.eq(ft.GRAPH_NAME), it.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))
            .where(at, o.KIND.eq("FIELD_ARGUMENT"))
            .and(parentHasNoNeed(p, touchedAt))
            .and(sourcedAt(o, touchedAt));
    }

    /**
     * An argument of a field with no needs whose target is bound to no table and has no member that
     * is reaches its field's target type, starting and landing on its own source table, the table
     * a write mutates for a write's payload.
     */
    private static Select<Record> argumentUnbound(GraphitronOccurrence o, GraphitronOccurrence p,
                                                  Condition at, LocalDateTime touchedAt) {
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("st");
        var pf = GRAPHITRON_FIELD.as("pf");
        var ft = GRAPHITRON_FIELD_TARGET.as("ft");
        var tt = GRAPHITRON_TABLETYPE.as("tt");
        var pm = GRAPHQL_POLY_MEMBER.as("pm");
        var mt = GRAPHITRON_TABLETYPE.as("mt");
        Condition written = claims(o, ft.TARGET_TYPE_NAME);
        return row(o, ft.TARGET_TYPE_NAME, st.TABLE_SOURCE_NAME, st.TABLE_SCHEMA, st.TABLE_NAME,
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(st.TABLE_SOURCE_NAME),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(st.TABLE_SCHEMA),
                when(written, castNull(SQLDataType.VARCHAR)).otherwise(st.TABLE_NAME))
            .from(o)
            .join(st).on(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE),
                st.TOUCHED_AT.eq(touchedAt))
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pf).on(pf.GRAPH_NAME.eq(p.GRAPH_NAME), pf.COORDINATE.eq(p.ELEMENT_COORDINATE))
            .join(ft).on(ft.GRAPH_NAME.eq(pf.GRAPH_NAME), ft.TYPE_NAME.eq(pf.TYPE_NAME),
                ft.FIELD_NAME.eq(pf.FIELD_NAME))
            .where(at, o.KIND.eq("FIELD_ARGUMENT"))
            .and(parentHasNoNeed(p, touchedAt))
            .and(notExists(selectOne().from(tt)
                .where(tt.GRAPH_NAME.eq(ft.GRAPH_NAME), tt.TYPE_NAME.eq(ft.TARGET_TYPE_NAME))))
            .and(notExists(selectOne().from(pm)
                .join(mt).on(mt.GRAPH_NAME.eq(pm.GRAPH_NAME), mt.TYPE_NAME.eq(pm.MEMBER_TYPE_NAME))
                .where(pm.GRAPH_NAME.eq(ft.GRAPH_NAME), pm.CONTAINER_NAME.eq(ft.TARGET_TYPE_NAME))));
    }

    /** Whether the occurrence {@code o} has a table source this reading stated. */
    private static Condition sourcedAt(GraphitronOccurrence o, LocalDateTime touchedAt) {
        var st = GRAPHITRON_OCCURRENCE_SOURCE_TABLE.as("st0");
        return exists(selectOne().from(st)
            .where(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE),
                st.TOUCHED_AT.eq(touchedAt)));
    }

    /**
     * An input field reaches what its parent argument or input field reaches, from where it starts:
     * a path its parent writes moves none of what is under it. It lands where its parent lands, or on
     * its parent's start where the parent writes a path, and on no table yet where it writes one.
     */
    private static Select<Record> inputFieldCopy(GraphitronOccurrence o, GraphitronOccurrence p,
                                                 Condition at, LocalDateTime touchedAt) {
        var pn = GRAPHITRON_PATH_NEED_TABLE.as("pn");
        Condition written = claims(o, pn.TARGET_TYPE);
        Condition parentWalked = pn.LANDING_TABLE.isNull();
        return row(o, pn.TARGET_TYPE, pn.START_SOURCE_NAME, pn.START_SCHEMA, pn.START_TABLE,
                when(written, castNull(SQLDataType.VARCHAR))
                    .when(parentWalked, pn.START_SOURCE_NAME).otherwise(pn.LANDING_SOURCE_NAME),
                when(written, castNull(SQLDataType.VARCHAR))
                    .when(parentWalked, pn.START_SCHEMA).otherwise(pn.LANDING_SCHEMA),
                when(written, castNull(SQLDataType.VARCHAR))
                    .when(parentWalked, pn.START_TABLE).otherwise(pn.LANDING_TABLE))
            .from(o)
            .join(p).on(p.GRAPH_NAME.eq(o.GRAPH_NAME), p.COORDINATE.eq(o.PARENT_COORDINATE))
            .join(pn).on(pn.GRAPH_NAME.eq(p.GRAPH_NAME), pn.COORDINATE.eq(p.COORDINATE),
                pn.TOUCHED_AT.eq(touchedAt))
            .where(at, o.KIND.eq("INPUT_FIELD"))
            .and(sourcedAt(o, touchedAt));
    }
}
