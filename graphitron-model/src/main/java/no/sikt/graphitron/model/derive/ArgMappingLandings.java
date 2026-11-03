package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_LANDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_LANDING_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_argmapping_landing}: where each written argMapping entry's
 * argument path lands, under each occurrence of its call, inserted from
 * {@code graphitron_argmapping_landing_rule} for one graph.
 *
 * <p>A step of the graphitron assembly, right after the occurrences it walks. Marked and swept: the
 * statement upserts on the reading's instant and the delete after it takes the landings this
 * reading no longer finds.
 */
public final class ArgMappingLandings {

    private ArgMappingLandings() {}

    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_ARGMAPPING_LANDING_RULE;
        var t = GRAPHITRON_ARGMAPPING_LANDING;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.CALL_COORDINATE, t.LANDING_COORDINATE, t.TRAILING_NAME, t.REPEATS_OWN_NAME,
                t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    r.POSITION, r.CALL_COORDINATE, r.LANDING_COORDINATE, r.TRAILING_NAME,
                    r.REPEATS_OWN_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.POSITION,
                t.CALL_COORDINATE)
            .doUpdate()
            .set(t.LANDING_COORDINATE, excluded(t.LANDING_COORDINATE))
            .set(t.TRAILING_NAME, excluded(t.TRAILING_NAME))
            .set(t.REPEATS_OWN_NAME, excluded(t.REPEATS_OWN_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
