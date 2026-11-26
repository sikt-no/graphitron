package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_READ_SCOPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_READ_SCOPE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_read_scope}, the scope of the tables each root read reads in a
 * graph with a tenant column, from the rule view beside it, marked and swept.
 *
 * <p>A step of {@link OccurrenceBindings}, after {@link OccurrenceTables} is swept, since the scope is
 * read off the table bindings this reading holds and no others.
 */
public final class OccurrenceReadScopes {

    private OccurrenceReadScopes() {}

    /** States each root read's scope, then sweeps the rows this reading did not mark. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var s = GRAPHITRON_OCCURRENCE_READ_SCOPE;
        var r = GRAPHITRON_OCCURRENCE_READ_SCOPE_RULE;
        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.COORDINATE, s.SCOPE, s.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.SCOPE, val(touchedAt, s.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(s.GRAPH_NAME, s.COORDINATE)
            .doUpdate()
            .set(s.SCOPE, excluded(s.SCOPE))
            .set(s.TOUCHED_AT, excluded(s.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(s).where(s.GRAPH_NAME.eq(graph), s.TOUCHED_AT.ne(touchedAt)).execute();
    }
}
