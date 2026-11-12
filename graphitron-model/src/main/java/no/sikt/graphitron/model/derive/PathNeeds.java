package no.sikt.graphitron.model.derive;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_OBJECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED_TABLE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_path_need}: every type an occurrence with a table
 * source must reach, with the source table it starts on and the table it lands on, marked and swept.
 *
 * <p>Written a depth at a time over the occurrences. Each rule claims what it finds in
 * {@code graphitron_path_need_claim}, one rule view per rule, and the needs are written from the
 * claims that take effect, as {@code graphitron_path_need_table_rule} states, a record a service call
 * takes overriding every other rule on its occurrence. A field reaches its target where the target has a table of its own
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
 * for the depth above. {@link #sweep} runs as each depth completes.
 */
public final class PathNeeds {

    private PathNeeds() {}

    /** The rules a need is claimed by, each stated by its own view. */
    private static final List<String> RULES = List.of("fieldTableBound", "fieldMember", "fieldUnbound",
        "argumentCopy", "recordParameter", "argumentTableBound", "argumentMember", "argumentUnbound",
        "inputFieldCopy");

    /** States the needs of the occurrences at {@code depth}. */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        requireOneSourceTablePerField(dsl, graph, touchedAt, depth);
        for (String rule : RULES) {
            writeClaims(dsl, graph, depth, touchedAt, rule);
        }
        var c = GRAPHITRON_PATH_NEED_CLAIM;
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .and(OccurrenceProducers.atDepth(c.GRAPH_NAME, c.COORDINATE, depth))
            .execute();
        write(dsl, graph, depth, touchedAt);
        writeObject(dsl, graph, depth, touchedAt);
    }

    /** One rule's claims on the occurrences at {@code depth}. */
    private static void writeClaims(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt,
                                    String rule) {
        var snake = new StringBuilder();
        for (char ch : rule.toCharArray()) {
            snake.append(Character.isUpperCase(ch) ? "_" + Character.toLowerCase(ch) : String.valueOf(ch));
        }
        var v = table(name(("graphitron_path_need_claim_" + snake + "_rule").toUpperCase()));
        Field<String> vGraph = field(name(v.getName(), "GRAPH_NAME"), String.class);
        Field<String> vCoordinate = field(name(v.getName(), "COORDINATE"), String.class);
        var c = GRAPHITRON_PATH_NEED_CLAIM;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.TARGET_TYPE, c.RULE, c.START_SOURCE_NAME, c.START_SCHEMA,
                c.START_TABLE, c.LANDING_SOURCE_NAME, c.LANDING_SCHEMA, c.LANDING_TABLE, c.TOUCHED_AT)
            .select(selectDistinct(vGraph, vCoordinate,
                    field(name(v.getName(), "TARGET_TYPE"), String.class),
                    field(name(v.getName(), "RULE"), String.class),
                    field(name(v.getName(), "START_SOURCE_NAME"), String.class),
                    field(name(v.getName(), "START_SCHEMA"), String.class),
                    field(name(v.getName(), "START_TABLE"), String.class),
                    field(name(v.getName(), "LANDING_SOURCE_NAME"), String.class),
                    field(name(v.getName(), "LANDING_SCHEMA"), String.class),
                    field(name(v.getName(), "LANDING_TABLE"), String.class),
                    val(touchedAt, c.TOUCHED_AT))
                .from(v)
                .where(vGraph.eq(graph), OccurrenceProducers.atDepth(vGraph, vCoordinate, depth)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.TARGET_TYPE, c.RULE)
            .doUpdate()
            .set(c.START_SOURCE_NAME, excluded(c.START_SOURCE_NAME))
            .set(c.START_SCHEMA, excluded(c.START_SCHEMA))
            .set(c.START_TABLE, excluded(c.START_TABLE))
            .set(c.LANDING_SOURCE_NAME, excluded(c.LANDING_SOURCE_NAME))
            .set(c.LANDING_SCHEMA, excluded(c.LANDING_SCHEMA))
            .set(c.LANDING_TABLE, excluded(c.LANDING_TABLE))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .execute();
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
    public static void sweep(DSLContext dsl, String graph, Integer depth, LocalDateTime touchedAt) {
        var n = GRAPHITRON_PATH_NEED;
        dsl.deleteFrom(n)
            .where(n.GRAPH_NAME.eq(graph))
            .and(n.TOUCHED_AT.ne(touchedAt))
            .and(OccurrenceProducers.atDepth(n.GRAPH_NAME, n.COORDINATE, depth))
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
     * Upserts one depth's needs from the claims that took effect, the need and then its table side,
     * each start, target type and landing referenced where it stands.
     */
    private static void write(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var r = GRAPHITRON_PATH_NEED_TABLE_RULE;
        var o = GRAPHITRON_OCCURRENCE.as("o");
        var n = GRAPHITRON_PATH_NEED;
        var t = GRAPHITRON_TYPE.as("t");
        Condition at = r.GRAPH_NAME.eq(graph).and(o.DEPTH.eq(depth));
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
            .select(selectDistinct(r.GRAPH_NAME, r.COORDINATE, r.TARGET_TYPE, inline(TABLE),
                    val(touchedAt, n.TOUCHED_AT), t.GRAPH_NAME, t.TYPE_NAME)
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .leftJoin(t).on(t.GRAPH_NAME.eq(r.GRAPH_NAME), t.TYPE_NAME.eq(r.TARGET_TYPE))
                .where(at))
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
            .select(selectDistinct(r.GRAPH_NAME, r.COORDINATE, r.TARGET_TYPE, inline(TABLE),
                    r.START_SOURCE_NAME, r.START_SCHEMA, r.START_TABLE, r.LANDING_SOURCE_NAME,
                    r.LANDING_SCHEMA, r.LANDING_TABLE, val(touchedAt, nt.TOUCHED_AT),
                    s.GRAPH_NAME, s.COORDINATE, s.TABLE_SOURCE_NAME, s.TABLE_SCHEMA, s.TABLE_NAME,
                    lt.SOURCE_NAME, lt.TABLE_SCHEMA, lt.TABLE_NAME)
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .leftJoin(s).on(s.GRAPH_NAME.eq(r.GRAPH_NAME), s.COORDINATE.eq(r.COORDINATE),
                    s.TABLE_SOURCE_NAME.eq(r.START_SOURCE_NAME), s.TABLE_SCHEMA.eq(r.START_SCHEMA),
                    s.TABLE_NAME.eq(r.START_TABLE), s.TOUCHED_AT.eq(touchedAt))
                .leftJoin(lt).on(lt.SOURCE_NAME.eq(r.LANDING_SOURCE_NAME), lt.TABLE_SCHEMA.eq(r.LANDING_SCHEMA),
                    lt.TABLE_NAME.eq(r.LANDING_TABLE))
                .where(at))
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
}
