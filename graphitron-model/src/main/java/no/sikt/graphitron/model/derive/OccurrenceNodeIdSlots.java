package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Record11;
import org.jooq.Select;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_MEMBER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD_COLUMN_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_node_id_slot} and its three subtypes, where each node id lands
 * in Java, from one rule per subtype, marked and swept.
 *
 * <p>A step of {@link OccurrenceBuilds}' stage, after its own built classes and member bindings, which
 * the member and record rules read, and before {@link OccurrenceNodeIdShapes}, which reads the slots.
 */
public final class OccurrenceNodeIdSlots {

    private OccurrenceNodeIdSlots() {}

    /** States every place the graph's node ids land, each supertype row before its subtype's. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var p = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER_RULE;
        var sp = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PARAMETER;
        writeSlots(dsl, dsl
            .select(p.GRAPH_NAME, p.COORDINATE, p.SLOT, inline("PARAMETER"), p.LIST_DEPTH, p.ERASED_CLASS,
                p.ELEMENT_CLASS, p.DELIVERY, p.TYPE_SOURCE_NAME, p.DECLARED_TYPE, val(touchedAt, sp.TOUCHED_AT))
            .from(p)
            .where(p.GRAPH_NAME.eq(graph)));
        dsl.insertInto(sp)
            .columns(sp.GRAPH_NAME, sp.CALL_COORDINATE, sp.CALL, sp.POSITION, sp.COORDINATE, sp.KIND,
                sp.TOUCHED_AT)
            .select(dsl
                .select(p.GRAPH_NAME, p.CALL_COORDINATE, p.CALL, p.POSITION, p.COORDINATE, inline("PARAMETER"),
                    val(touchedAt, sp.TOUCHED_AT))
                .from(p)
                .where(p.GRAPH_NAME.eq(graph)))
            .onConflict(sp.GRAPH_NAME, sp.CALL_COORDINATE, sp.CALL, sp.POSITION)
            .doUpdate()
            .set(sp.COORDINATE, excluded(sp.COORDINATE))
            .set(sp.TOUCHED_AT, excluded(sp.TOUCHED_AT))
            .execute();
        var j = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION_RULE;
        var pj = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION;
        dsl.insertInto(pj)
            .columns(pj.GRAPH_NAME, pj.CALL_COORDINATE, pj.CALL, pj.POSITION, pj.COORDINATE, pj.BOUND,
                pj.KEY_POSITION, pj.TOUCHED_AT)
            .select(dsl
                .select(j.GRAPH_NAME, j.CALL_COORDINATE, j.CALL, j.POSITION, j.COORDINATE, j.BOUND,
                    j.KEY_POSITION, val(touchedAt, pj.TOUCHED_AT))
                .from(j)
                .where(j.GRAPH_NAME.eq(graph)))
            .onConflict(pj.GRAPH_NAME, pj.CALL_COORDINATE, pj.CALL, pj.POSITION, pj.BOUND)
            .doUpdate()
            .set(pj.COORDINATE, excluded(pj.COORDINATE))
            .set(pj.KEY_POSITION, excluded(pj.KEY_POSITION))
            .set(pj.TOUCHED_AT, excluded(pj.TOUCHED_AT))
            .execute();

        var m = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_MEMBER_RULE;
        var sm = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_MEMBER;
        writeSlots(dsl, dsl
            .select(m.GRAPH_NAME, m.COORDINATE, m.BOUND, inline("MEMBER"), m.LIST_DEPTH, m.ERASED_CLASS,
                m.ELEMENT_CLASS, m.DELIVERY, m.TYPE_SOURCE_NAME, m.DECLARED_TYPE, val(touchedAt, sm.TOUCHED_AT))
            .from(m)
            .where(m.GRAPH_NAME.eq(graph)));
        dsl.insertInto(sm)
            .columns(sm.GRAPH_NAME, sm.COORDINATE, sm.BOUND, sm.KIND, sm.TOUCHED_AT)
            .select(dsl
                .select(m.GRAPH_NAME, m.COORDINATE, m.BOUND, inline("MEMBER"), val(touchedAt, sm.TOUCHED_AT))
                .from(m)
                .where(m.GRAPH_NAME.eq(graph)))
            .onConflict(sm.GRAPH_NAME, sm.COORDINATE, sm.BOUND)
            .doUpdate()
            .set(sm.TOUCHED_AT, excluded(sm.TOUCHED_AT))
            .execute();

        var r = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD_RULE;
        var sr = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD;
        writeSlots(dsl, dsl
            .select(r.GRAPH_NAME, r.COORDINATE, r.CLASS_NAME, inline("RECORD"), r.LIST_DEPTH, r.CLASS_NAME,
                inline(null, r.CLASS_NAME), inline(null, r.CLASS_NAME), r.TYPE_SOURCE_NAME, r.DECLARED_TYPE,
                val(touchedAt, sr.TOUCHED_AT))
            .from(r)
            .where(r.GRAPH_NAME.eq(graph)));
        dsl.insertInto(sr)
            .columns(sr.GRAPH_NAME, sr.COORDINATE, sr.CLASS_NAME, sr.PARENT_COORDINATE, sr.KIND, sr.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.CLASS_NAME, r.PARENT_COORDINATE, inline("RECORD"),
                    val(touchedAt, sr.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(sr.GRAPH_NAME, sr.COORDINATE, sr.CLASS_NAME)
            .doUpdate()
            .set(sr.PARENT_COORDINATE, excluded(sr.PARENT_COORDINATE))
            .set(sr.TOUCHED_AT, excluded(sr.TOUCHED_AT))
            .execute();
        var rc = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD_COLUMN;
        var rcr = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD_COLUMN_RULE;
        dsl.insertInto(rc)
            .columns(rc.GRAPH_NAME, rc.COORDINATE, rc.CLASS_NAME, rc.BOUND, rc.KEY_POSITION,
                rc.COLUMN_SOURCE_NAME, rc.COLUMN_SCHEMA, rc.COLUMN_TABLE, rc.COLUMN_NAME, rc.TOUCHED_AT)
            .select(dsl
                .select(rcr.GRAPH_NAME, rcr.COORDINATE, rcr.CLASS_NAME, rcr.BOUND, rcr.KEY_POSITION,
                    rcr.COLUMN_SOURCE_NAME, rcr.COLUMN_SCHEMA, rcr.COLUMN_TABLE, rcr.COLUMN_NAME,
                    val(touchedAt, rc.TOUCHED_AT))
                .from(rcr)
                .where(rcr.GRAPH_NAME.eq(graph)))
            .onConflict(rc.GRAPH_NAME, rc.COORDINATE, rc.CLASS_NAME, rc.BOUND, rc.KEY_POSITION)
            .doUpdate()
            .set(rc.COLUMN_SOURCE_NAME, excluded(rc.COLUMN_SOURCE_NAME))
            .set(rc.COLUMN_SCHEMA, excluded(rc.COLUMN_SCHEMA))
            .set(rc.COLUMN_TABLE, excluded(rc.COLUMN_TABLE))
            .set(rc.COLUMN_NAME, excluded(rc.COLUMN_NAME))
            .set(rc.TOUCHED_AT, excluded(rc.TOUCHED_AT))
            .execute();
    }

    /**
     * The supertype rows one rule states: graph, coordinate, slot, kind, list depth, erased class,
     * element class, delivery, the declared type's key and the mark, in that order.
     */
    private static void writeSlots(DSLContext dsl, Select<? extends Record11<String, String, String,
            String, Integer, String, String, String, String, String, LocalDateTime>> rows) {
        var s = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT;
        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.COORDINATE, s.SLOT, s.KIND, s.LIST_DEPTH, s.ERASED_CLASS,
                s.ELEMENT_CLASS, s.DELIVERY, s.TYPE_SOURCE_NAME, s.DECLARED_TYPE, s.TOUCHED_AT)
            .select(rows)
            .onConflict(s.GRAPH_NAME, s.COORDINATE, s.SLOT)
            .doUpdate()
            .set(s.KIND, excluded(s.KIND))
            .set(s.LIST_DEPTH, excluded(s.LIST_DEPTH))
            .set(s.ERASED_CLASS, excluded(s.ERASED_CLASS))
            .set(s.ELEMENT_CLASS, excluded(s.ELEMENT_CLASS))
            .set(s.DELIVERY, excluded(s.DELIVERY))
            .set(s.TYPE_SOURCE_NAME, excluded(s.TYPE_SOURCE_NAME))
            .set(s.DECLARED_TYPE, excluded(s.DECLARED_TYPE))
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .execute();
    }

    /**
     * Deletes the slots this reading did not mark, their subtype rows by the cascade, and the
     * projections and record columns it did not mark under a slot it did.
     */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var pj = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_PROJECTION;
        dsl.deleteFrom(pj)
            .where(pj.GRAPH_NAME.eq(graph))
            .and(pj.TOUCHED_AT.ne(touchedAt))
            .execute();
        var rc = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD_COLUMN;
        dsl.deleteFrom(rc)
            .where(rc.GRAPH_NAME.eq(graph))
            .and(rc.TOUCHED_AT.ne(touchedAt))
            .execute();
        var s = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT;
        dsl.deleteFrom(s)
            .where(s.GRAPH_NAME.eq(graph))
            .and(s.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
