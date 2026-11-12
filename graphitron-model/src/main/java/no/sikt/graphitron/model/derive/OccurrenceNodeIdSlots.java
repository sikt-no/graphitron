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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NODE_ID_SLOT_RECORD;
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

    /** Deletes the slots this reading did not mark, their subtype rows by the cascade. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var s = GRAPHITRON_OCCURRENCE_NODE_ID_SLOT;
        dsl.deleteFrom(s)
            .where(s.GRAPH_NAME.eq(graph))
            .and(s.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
