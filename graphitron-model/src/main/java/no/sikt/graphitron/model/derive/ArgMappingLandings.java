package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Select;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_LANDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_LANDING_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_STEP_HEAD_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_STEP_NEXT_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_argmapping_step} and {@code graphitron_argmapping_landing}: each
 * step a written argMapping entry's argument path takes, and where it lands, under each occurrence of
 * its call, for one graph.
 *
 * <p>A step of the graphitron assembly, right after the occurrences it walks. The steps are taken a
 * name at a time, the heads from {@code graphitron_argmapping_step_head_rule} and each later count
 * from {@code graphitron_argmapping_step_next_rule} over this reading's steps one count down, so
 * every statement is over one graph's rows; a path ends because each step consumes a name. The
 * landing is then the deepest step, inserted from {@code graphitron_argmapping_landing_rule}. Both
 * are marked and swept, the steps before the landing reads them, so a step an older reading took
 * cannot pass for the deepest.
 */
public final class ArgMappingLandings {

    private ArgMappingLandings() {}

    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        steps(dsl, graph, touchedAt);

        var r = GRAPHITRON_ARGMAPPING_LANDING_RULE;
        var t = GRAPHITRON_ARGMAPPING_LANDING;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER, t.PARAMETER_NAME,
                t.CALL_COORDINATE, t.LANDING_COORDINATE, t.TRAILING_NAME, t.REPEATS_OWN_NAME,
                t.DESCENDS, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.DIRECTIVE_NAME, r.ORDINAL,
                    r.OWNER, r.PARAMETER_NAME, r.CALL_COORDINATE, r.LANDING_COORDINATE, r.TRAILING_NAME,
                    r.REPEATS_OWN_NAME, r.DESCENDS, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER, t.PARAMETER_NAME,
                t.CALL_COORDINATE)
            .doUpdate()
            .set(t.LANDING_COORDINATE, excluded(t.LANDING_COORDINATE))
            .set(t.TRAILING_NAME, excluded(t.TRAILING_NAME))
            .set(t.REPEATS_OWN_NAME, excluded(t.REPEATS_OWN_NAME))
            .set(t.DESCENDS, excluded(t.DESCENDS))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** The heads, then each count from the one before, then the sweep of what no count reached. */
    private static void steps(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var h = GRAPHITRON_ARGMAPPING_STEP_HEAD_RULE;
        upsert(dsl, dsl.select(h.GRAPH_NAME, h.COORDINATE, h.DIRECTIVE_NAME, h.ORDINAL,
                h.OWNER, h.PARAMETER_NAME, h.CALL_COORDINATE, h.CONSUMED, h.BOUND_TO, h.SEGMENTS, h.REST,
                h.STEP_COORDINATE, h.REPEATS_OWN_NAME, val(touchedAt, GRAPHITRON_ARGMAPPING_STEP.TOUCHED_AT))
            .from(h)
            .where(h.GRAPH_NAME.eq(graph)));
        // Each count takes this reading's paths one name further, until none has a name left.
        var n = GRAPHITRON_ARGMAPPING_STEP_NEXT_RULE;
        for (int count = 1; ; count++) {
            int taken = upsert(dsl, dsl.select(n.GRAPH_NAME, n.COORDINATE, n.DIRECTIVE_NAME,
                    n.ORDINAL, n.OWNER, n.PARAMETER_NAME, n.CALL_COORDINATE, n.CONSUMED, n.BOUND_TO,
                    n.SEGMENTS, n.REST, n.STEP_COORDINATE, n.REPEATS_OWN_NAME,
                    val(touchedAt, GRAPHITRON_ARGMAPPING_STEP.TOUCHED_AT))
                .from(n)
                .where(n.GRAPH_NAME.eq(graph))
                .and(n.FROM_CONSUMED.eq(val(count)))
                .and(n.FROM_TOUCHED_AT.eq(val(touchedAt))));
            if (taken == 0) {
                break;
            }
        }
        var s = GRAPHITRON_ARGMAPPING_STEP;
        dsl.deleteFrom(s)
            .where(s.GRAPH_NAME.eq(graph))
            .and(s.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /** Upserts {@code rows}, in the step table's column order, answering how many it touched. */
    private static int upsert(DSLContext dsl, Select<? extends Record> rows) {
        var s = GRAPHITRON_ARGMAPPING_STEP;
        return dsl.insertInto(s)
            .columns(List.of(s.GRAPH_NAME, s.COORDINATE, s.DIRECTIVE_NAME, s.ORDINAL,
                s.OWNER, s.PARAMETER_NAME, s.CALL_COORDINATE, s.CONSUMED, s.BOUND_TO, s.SEGMENTS, s.REST,
                s.STEP_COORDINATE, s.REPEATS_OWN_NAME, s.TOUCHED_AT))
            .select(rows)
            .onConflict(s.GRAPH_NAME, s.COORDINATE, s.DIRECTIVE_NAME, s.ORDINAL, s.OWNER, s.PARAMETER_NAME,
                s.CALL_COORDINATE, s.CONSUMED)
            .doUpdate()
            .set(s.BOUND_TO, excluded(s.BOUND_TO))
            .set(s.SEGMENTS, excluded(s.SEGMENTS))
            .set(s.REST, excluded(s.REST))
            .set(s.STEP_COORDINATE, excluded(s.STEP_COORDINATE))
            .set(s.REPEATS_OWN_NAME, excluded(s.REPEATS_OWN_NAME))
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .execute();
    }
}
