package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_VALUE_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_VALUE_SLOT_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_VALUE_SLOT_PARAMETER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_VALUE_SLOT_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_value_slot} and its subtypes: where each value that is no node id
 * lands in Java, and whether it fits there, from the rule view beside them, one kind of place and call
 * at a time, each written by the stage that writes those places. A {@code PARAMETER} of a service's or
 * a root routine's call is written once that kind's fills are, ahead of the defect rule reading it; a
 * {@code MEMBER} once the member bindings of its kind are. Each write ends by sweeping the places of
 * its kind and call this reading did not mark, their subtype rows by the cascade, so a reader after it
 * sees only this reading's verdicts.
 */
public final class OccurrenceValueSlots {

    private OccurrenceValueSlots() {}

    static final String PARAMETER = "PARAMETER";
    static final String MEMBER = "MEMBER";

    /** Writes the places of {@code kind} that {@code statedBy}'s calls state, then sweeps the unmarked. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt, String kind, String statedBy) {
        var r = GRAPHITRON_OCCURRENCE_VALUE_SLOT_RULE;
        var s = GRAPHITRON_OCCURRENCE_VALUE_SLOT;
        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.COORDINATE, s.SLOT, s.KIND, s.STATED_BY, s.ELEMENT_CLASS, s.TAKES_MANY,
                s.ARRIVES_MANY, s.ARRIVAL_KIND, s.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.SLOT, r.KIND, r.STATED_BY, r.ELEMENT_CLASS,
                    r.TAKES_MANY, r.ARRIVES_MANY, r.ARRIVAL_KIND, val(touchedAt, s.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph), r.KIND.eq(kind), r.STATED_BY.eq(statedBy)))
            .onConflict(s.GRAPH_NAME, s.COORDINATE, s.SLOT)
            .doUpdate()
            .set(s.KIND, excluded(s.KIND))
            .set(s.STATED_BY, excluded(s.STATED_BY))
            .set(s.ELEMENT_CLASS, excluded(s.ELEMENT_CLASS))
            .set(s.TAKES_MANY, excluded(s.TAKES_MANY))
            .set(s.ARRIVES_MANY, excluded(s.ARRIVES_MANY))
            .set(s.ARRIVAL_KIND, excluded(s.ARRIVAL_KIND))
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .execute();
        if (PARAMETER.equals(kind)) {
            var p = GRAPHITRON_OCCURRENCE_VALUE_SLOT_PARAMETER;
            dsl.insertInto(p)
                .columns(p.GRAPH_NAME, p.CALL_COORDINATE, p.CALL, p.POSITION, p.COORDINATE, p.KIND, p.STATED_BY,
                    p.TOUCHED_AT)
                .select(dsl.select(r.GRAPH_NAME, r.CALL_COORDINATE, r.CALL, r.POSITION, r.COORDINATE, r.KIND,
                        r.STATED_BY, val(touchedAt, p.TOUCHED_AT))
                    .from(r)
                    .where(r.GRAPH_NAME.eq(graph), r.KIND.eq(kind), r.STATED_BY.eq(statedBy)))
                .onConflict(p.GRAPH_NAME, p.CALL_COORDINATE, p.CALL, p.POSITION)
                .doUpdate()
                .set(p.COORDINATE, excluded(p.COORDINATE))
                .set(p.KIND, excluded(p.KIND))
                .set(p.STATED_BY, excluded(p.STATED_BY))
                .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT))
                .execute();
        } else {
            var m = GRAPHITRON_OCCURRENCE_VALUE_SLOT_MEMBER;
            dsl.insertInto(m)
                .columns(m.GRAPH_NAME, m.COORDINATE, m.BOUND, m.KIND, m.STATED_BY, m.TOUCHED_AT)
                .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.KIND, r.STATED_BY,
                        val(touchedAt, m.TOUCHED_AT))
                    .from(r)
                    .where(r.GRAPH_NAME.eq(graph), r.KIND.eq(kind), r.STATED_BY.eq(statedBy)))
                .onConflict(m.GRAPH_NAME, m.COORDINATE, m.BOUND)
                .doUpdate()
                .set(m.KIND, excluded(m.KIND))
                .set(m.STATED_BY, excluded(m.STATED_BY))
                .set(m.TOUCHED_AT, excluded(m.TOUCHED_AT))
                .execute();
        }
        // A subtype row hangs under its slot by kind and owner, so the slot's sweep takes it too.
        dsl.deleteFrom(s)
            .where(s.GRAPH_NAME.eq(graph))
            .and(s.KIND.eq(kind))
            .and(s.STATED_BY.eq(statedBy))
            .and(s.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
