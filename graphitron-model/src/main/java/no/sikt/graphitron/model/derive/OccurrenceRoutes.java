package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONNECTION_PART;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONNECTION_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * The {@code occurrence-routes} stage: the connection each root service call of a database-per-tenant
 * graph runs on, into {@code graphitron_occurrence_connection}, the parts its key is made of, into
 * {@code graphitron_occurrence_connection_part}, and a defect for each call it cannot say that for, so
 * every such call has one or the other; marked and swept.
 *
 * <p>Runs after {@link OccurrenceTenants}, off the tenant slots, the optional and list element
 * occurrences, the calls' fills and the producers' results.
 */
public final class OccurrenceRoutes {

    private OccurrenceRoutes() {}

    private static final String ROUTED = "ROUTED";

    /** States the graph's connections, their parts and their defects, and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_CONNECTION;
        var p = GRAPHITRON_OCCURRENCE_CONNECTION_PART;
        var r = GRAPHITRON_OCCURRENCE_CONNECTION_RULE;
        // A connection whose outcome changed keeps its key, and a part under the old outcome would hold
        // the update back; it is no longer true, so it goes.
        dsl.deleteFrom(p)
            .where(p.GRAPH_NAME.eq(graph))
            .andNotExists(dsl.selectOne().from(r)
                .where(r.GRAPH_NAME.eq(p.GRAPH_NAME), r.COORDINATE.eq(p.COORDINATE), r.CALL.eq(p.CALL),
                    r.OUTCOME.eq(ROUTED)))
            .execute();
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.CALL, c.OUTCOME, c.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.CALL, r.OUTCOME, val(touchedAt, c.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph), r.OUTCOME.isNotNull()))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.CALL)
            .doUpdate()
            .set(c.OUTCOME, excluded(c.OUTCOME))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .execute();
        dsl.insertInto(p)
            .columns(p.GRAPH_NAME, p.COORDINATE, p.CALL, p.PART, p.OUTCOME, p.WHEN_EMPTY, p.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.CALL, inline("TENANT"), r.OUTCOME, r.WHEN_EMPTY,
                    val(touchedAt, p.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph), r.OUTCOME.eq(ROUTED)))
            .onConflict(p.GRAPH_NAME, p.COORDINATE, p.CALL, p.PART)
            .doUpdate()
            .set(p.WHEN_EMPTY, excluded(p.WHEN_EMPTY))
            .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(p).where(p.GRAPH_NAME.eq(graph), p.TOUCHED_AT.ne(touchedAt)).execute();
        dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graph), c.TOUCHED_AT.ne(touchedAt)).execute();
        EntryDefects.derive(dsl, graph, touchedAt, EntryDefects.ROUTES);
    }
}
