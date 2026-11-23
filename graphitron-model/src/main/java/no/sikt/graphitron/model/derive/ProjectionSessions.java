package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE_SESSION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE_SESSION_RULE;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_tabletype_session}, the table-bound types whose projection reads the
 * executing connection's session, from the one-step rule view beside it until a pass adds nothing.
 *
 * <p>The graph's rows are cleared first rather than marked and swept: the rule marks a type that calls
 * one the relation holds, so a mark an older reading left could hold itself up through a cycle of
 * calls.
 *
 * <p>A step of {@link OccurrenceBindings}, run once {@link OccurrenceConditions}, whose conditions and
 * fills seed it, and {@link OccurrenceProjections}, whose occurrences carry each type's inline calls,
 * have written and swept, so it reads this reading's rows alone; the readers it reads are the
 * occurrence-depths stage's.
 */
public final class ProjectionSessions {

    private ProjectionSessions() {}

    /** States the graph's table-bound types whose projection reads the session. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_TABLETYPE_SESSION;
        var r = GRAPHITRON_TABLETYPE_SESSION_RULE;
        dsl.deleteFrom(t).where(t.GRAPH_NAME.eq(graph)).execute();
        var held = GRAPHITRON_TABLETYPE_SESSION.as("held");
        int written;
        do {
            written = dsl.insertInto(t)
                .columns(t.GRAPH_NAME, t.TYPE_NAME, t.TOUCHED_AT)
                .select(selectDistinct(r.GRAPH_NAME, r.TYPE_NAME, val(touchedAt, t.TOUCHED_AT))
                    .from(r)
                    .where(r.GRAPH_NAME.eq(graph))
                    .and(notExists(selectOne().from(held)
                        .where(held.GRAPH_NAME.eq(r.GRAPH_NAME), held.TYPE_NAME.eq(r.TYPE_NAME)))))
                .execute();
        } while (written > 0);
    }
}
