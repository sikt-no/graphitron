package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PARTICIPANT_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_participant}: the types each polymorphic occurrence's rows may
 * be, and how a row is told apart as each, from the rule view beside it, marked and swept.
 *
 * <p>Runs after the occurrences, off them, the table types, the polymorphic membership and the two
 * discriminator directives' entries, with the catalog's columns and enum literals.
 */
public final class OccurrenceParticipants {

    private OccurrenceParticipants() {}

    /** States the graph's participants, and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var p = GRAPHITRON_OCCURRENCE_PARTICIPANT;
        var r = GRAPHITRON_OCCURRENCE_PARTICIPANT_RULE;
        dsl.insertInto(p)
            .columns(p.GRAPH_NAME, p.COORDINATE, p.PARTICIPANT_TYPE, p.RECOGNIZED_BY, p.TABLE_SOURCE_NAME,
                p.TABLE_SCHEMA, p.TABLE_NAME, p.BASE_SOURCE_NAME, p.BASE_SCHEMA, p.BASE_NAME,
                p.DISCRIMINATOR_COLUMN, p.DISCRIMINATOR_VALUE, p.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.PARTICIPANT_TYPE, r.RECOGNIZED_BY, r.TABLE_SOURCE_NAME,
                    r.TABLE_SCHEMA, r.TABLE_NAME, r.BASE_SOURCE_NAME, r.BASE_SCHEMA, r.BASE_NAME,
                    r.DISCRIMINATOR_COLUMN, r.DISCRIMINATOR_VALUE, val(touchedAt, p.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(p.GRAPH_NAME, p.COORDINATE, p.PARTICIPANT_TYPE)
            .doUpdate()
            .set(p.RECOGNIZED_BY, excluded(p.RECOGNIZED_BY))
            .set(p.TABLE_SOURCE_NAME, excluded(p.TABLE_SOURCE_NAME))
            .set(p.TABLE_SCHEMA, excluded(p.TABLE_SCHEMA))
            .set(p.TABLE_NAME, excluded(p.TABLE_NAME))
            .set(p.BASE_SOURCE_NAME, excluded(p.BASE_SOURCE_NAME))
            .set(p.BASE_SCHEMA, excluded(p.BASE_SCHEMA))
            .set(p.BASE_NAME, excluded(p.BASE_NAME))
            .set(p.DISCRIMINATOR_COLUMN, excluded(p.DISCRIMINATOR_COLUMN))
            .set(p.DISCRIMINATOR_VALUE, excluded(p.DISCRIMINATOR_VALUE))
            .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(p)
            .where(p.GRAPH_NAME.eq(graph))
            .and(p.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
