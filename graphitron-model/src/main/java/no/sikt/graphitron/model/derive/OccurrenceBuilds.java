package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_MEMBER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BUILT_CLASS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BUILT_CLASS_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_built_class}, the class each input object is built into, and
 * the {@code MEMBER} bindings of {@code graphitron_occurrence_binding_member}, the member of that
 * class each input field fills, from the rule views beside them, marked and swept, one kind of row at
 * a time: {@code SERVICE} rows, which a service, write or routine call reaches, by
 * {@link OccurrenceProducers} ahead of the depth loop, so a record's fields find its table there;
 * {@code CONDITION} rows, which only condition or expression calls reach, by the {@code occurrence-builds}
 * stage, after {@link OccurrenceBindings} pins those calls.
 *
 * <p>Each writer steps through the depths itself: a member's and a grouping's class, and a field's
 * member, read the built class of the occurrence one depth up, which this reading has written by
 * then. Only that reading's rows are read, so a parent an older reading left is not built on before
 * the sweep removes it.
 */
public final class OccurrenceBuilds {

    private OccurrenceBuilds() {}

    /** The rows the producers stage states, a service's, write's or routine's. */
    static final String SERVICE = "SERVICE";

    /** The rows this stage states, only conditions' and expressions'. */
    static final String CONDITION = "CONDITION";

    /**
     * The stage: the conditions' built classes and member bindings, then where each node id lands and
     * the shape it lands in, each swept, then the defects judging them.
     */
    public static void derive(DSLContext dsl, String graph, LocalDateTime readAt) {
        write(dsl, graph, readAt, CONDITION);
        sweep(dsl, graph, readAt, CONDITION);
        OccurrenceNodeIdSlots.write(dsl, graph, readAt);
        OccurrenceNodeIdSlots.sweep(dsl, graph, readAt);
        OccurrenceNodeIdShapes.write(dsl, graph, readAt);
        OccurrenceNodeIdShapes.sweep(dsl, graph, readAt);
        EntryDefects.derive(dsl, graph, readAt, EntryDefects.BUILDS);
        EntryDefectColumns.derive(dsl, graph, readAt, EntryDefects.BUILDS.stage());
        EntryDefectMembers.derive(dsl, graph, readAt, EntryDefectMembers.BUILDS, EntryDefects.BUILDS.stage());
    }

    /** States the built classes and member bindings of every depth of {@code graph} that {@code statedBy} states. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt, String statedBy) {
        var o = GRAPHITRON_OCCURRENCE;
        Integer deepest = dsl.select(max(o.DEPTH)).from(o)
            .where(o.GRAPH_NAME.eq(graph)).and(o.TOUCHED_AT.eq(touchedAt))
            .fetchOne(0, Integer.class);
        if (deepest == null) {
            return;
        }
        for (int depth = 0; depth <= deepest; depth++) {
            writeMembers(dsl, graph, depth, touchedAt, statedBy);
            writeClasses(dsl, graph, depth, touchedAt, statedBy);
        }
    }

    private static void writeClasses(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt,
                                     String statedBy) {
        var c = GRAPHITRON_OCCURRENCE_BUILT_CLASS;
        var r = GRAPHITRON_OCCURRENCE_BUILT_CLASS_RULE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.CLASS_NAME, c.DECLARED_VIA, c.MEMBER_NAMES, c.BUILT_AT, c.TOUCHED_AT,
                c.CLASS_SOURCE_REF, c.CLASS_NAME_REF, c.CONSTRUCTIBLE, c.RECORD_SOURCE_NAME, c.RECORD_SCHEMA,
                c.RECORD_TABLE, c.STATED_BY)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.CLASS_NAME, r.DECLARED_VIA, r.MEMBER_NAMES, r.BUILT_AT,
                    val(touchedAt, c.TOUCHED_AT), r.CLASS_SOURCE_REF, r.CLASS_NAME_REF, r.CONSTRUCTIBLE,
                    r.RECORD_SOURCE_NAME, r.RECORD_SCHEMA, r.RECORD_TABLE, r.STATED_BY)
                .from(r)
                .where(r.GRAPH_NAME.eq(graph), r.DEPTH.eq(depth), r.STATED_BY.eq(statedBy),
                    r.PARENT_TOUCHED_AT.isNull().or(r.PARENT_TOUCHED_AT.eq(touchedAt))))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.CLASS_NAME)
            .doUpdate()
            .set(c.DECLARED_VIA, excluded(c.DECLARED_VIA))
            .set(c.MEMBER_NAMES, excluded(c.MEMBER_NAMES))
            .set(c.BUILT_AT, excluded(c.BUILT_AT))
            .set(c.CLASS_SOURCE_REF, excluded(c.CLASS_SOURCE_REF))
            .set(c.CLASS_NAME_REF, excluded(c.CLASS_NAME_REF))
            .set(c.CONSTRUCTIBLE, excluded(c.CONSTRUCTIBLE))
            .set(c.RECORD_SOURCE_NAME, excluded(c.RECORD_SOURCE_NAME))
            .set(c.RECORD_SCHEMA, excluded(c.RECORD_SCHEMA))
            .set(c.RECORD_TABLE, excluded(c.RECORD_TABLE))
            .set(c.STATED_BY, excluded(c.STATED_BY))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .execute();
    }

    private static void writeMembers(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt,
                                     String statedBy) {
        var o = GRAPHITRON_OCCURRENCE;
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var m = GRAPHITRON_OCCURRENCE_BINDING_MEMBER;
        var r = GRAPHITRON_OCCURRENCE_BINDING_MEMBER_RULE;
        var current = r.GRAPH_NAME.eq(graph).and(o.DEPTH.eq(depth)).and(r.PARENT_TOUCHED_AT.eq(touchedAt))
            .and(r.STATED_BY.eq(statedBy));
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl
                .selectDistinct(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.KIND, val(touchedAt, b.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(current))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();
        dsl.insertInto(m)
            .columns(m.GRAPH_NAME, m.COORDINATE, m.BOUND, m.KIND, m.PARENT_COORDINATE,
                m.SLOT_SOURCE_NAME, m.SLOT_METHOD_NAME, m.SLOT_DESCRIPTOR, m.SLOT_POSITION, m.STATED_BY,
                m.RECORD_SOURCE_NAME, m.RECORD_SCHEMA, m.RECORD_TABLE, m.TOUCHED_AT, m.RECORD_SOURCE_REF,
                m.RECORD_SCHEMA_REF, m.RECORD_TABLE_REF)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.KIND, r.PARENT_COORDINATE,
                    r.SLOT_SOURCE_NAME, r.SLOT_METHOD_NAME, r.SLOT_DESCRIPTOR, r.SLOT_POSITION, r.STATED_BY,
                    r.RECORD_SOURCE_NAME, r.RECORD_SCHEMA, r.RECORD_TABLE, val(touchedAt, m.TOUCHED_AT),
                    r.RECORD_SOURCE_NAME, r.RECORD_SCHEMA, r.RECORD_TABLE)
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(current))
            .onConflict(m.GRAPH_NAME, m.COORDINATE, m.BOUND)
            .doUpdate()
            .set(m.PARENT_COORDINATE, excluded(m.PARENT_COORDINATE))
            .set(m.SLOT_SOURCE_NAME, excluded(m.SLOT_SOURCE_NAME))
            .set(m.SLOT_METHOD_NAME, excluded(m.SLOT_METHOD_NAME))
            .set(m.SLOT_DESCRIPTOR, excluded(m.SLOT_DESCRIPTOR))
            .set(m.SLOT_POSITION, excluded(m.SLOT_POSITION))
            .set(m.STATED_BY, excluded(m.STATED_BY))
            .set(m.RECORD_SOURCE_NAME, excluded(m.RECORD_SOURCE_NAME))
            .set(m.RECORD_SCHEMA, excluded(m.RECORD_SCHEMA))
            .set(m.RECORD_TABLE, excluded(m.RECORD_TABLE))
            .set(m.TOUCHED_AT, excluded(m.TOUCHED_AT))
            .set(m.RECORD_SOURCE_REF, excluded(m.RECORD_SOURCE_REF))
            .set(m.RECORD_SCHEMA_REF, excluded(m.RECORD_SCHEMA_REF))
            .set(m.RECORD_TABLE_REF, excluded(m.RECORD_TABLE_REF))
            .execute();
    }

    /**
     * Deletes the member bindings and built classes of {@code statedBy}'s kind no depth of this
     * reading marked, the members' subtype rows by the cascade from their bindings; the other kind's
     * rows are their writer's to sweep.
     */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt, String statedBy) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var m = GRAPHITRON_OCCURRENCE_BINDING_MEMBER;
        var c = GRAPHITRON_OCCURRENCE_BUILT_CLASS;
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq("MEMBER"))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .andExists(dsl.selectOne().from(m)
                .where(m.GRAPH_NAME.eq(b.GRAPH_NAME), m.COORDINATE.eq(b.COORDINATE), m.BOUND.eq(b.BOUND),
                    m.STATED_BY.eq(statedBy)))
            .execute();
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.STATED_BY.eq(statedBy))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
