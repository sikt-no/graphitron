package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LOOKUP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_LOOKUP_KEY_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The {@code occurrence-lookup-keys} stage: the keys a caller narrows each list by, into
 * {@code graphitron_occurrence_lookup_key}, from its rule view over the whole graph, then swept; last,
 * the defects of a {@code @lookupKey} those keys cannot state, from the stage's own rule.
 *
 * <p>After {@link OccurrenceBindings}, since a key is an argument's or input field's column or node id
 * binding at the table the list's own binding reads, or a node id {@link OccurrenceNodeIdCarriedColumns}
 * carries back to it. A key whose position moves keeps no row at its
 * old position: the rows this reading does not restate go before it writes, so the unique key on the
 * key occurrence never meets a stale row.
 */
public final class OccurrenceLookupKeys {

    private OccurrenceLookupKeys() {}

    /** Writes the stage's relation and its defects, marked with this reading. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var k = GRAPHITRON_OCCURRENCE_LOOKUP_KEY;
        var r = GRAPHITRON_OCCURRENCE_LOOKUP_KEY_RULE;
        dsl.deleteFrom(k)
            .where(k.GRAPH_NAME.eq(graph))
            .andNotExists(select(r.GRAPH_NAME).from(r)
                .where(r.GRAPH_NAME.eq(k.GRAPH_NAME), r.COORDINATE.eq(k.COORDINATE), r.BOUND.eq(k.BOUND),
                    r.POSITION.eq(k.POSITION), r.KEY_COORDINATE.eq(k.KEY_COORDINATE), r.KEY_BOUND.eq(k.KEY_BOUND),
                    r.KEY_KIND.eq(k.KEY_KIND)))
            .execute();
        dsl.insertInto(k)
            .columns(k.GRAPH_NAME, k.COORDINATE, k.BOUND, k.POSITION, k.KEY_COORDINATE, k.KEY_BOUND, k.KEY_KIND,
                k.TOUCHED_AT)
            .select(select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.POSITION, r.KEY_COORDINATE, r.KEY_BOUND, r.KEY_KIND,
                    val(touchedAt, k.TOUCHED_AT))
                .from(r).where(r.GRAPH_NAME.eq(graph)))
            .onConflict(k.GRAPH_NAME, k.COORDINATE, k.BOUND, k.POSITION)
            .doUpdate()
            .set(k.TOUCHED_AT, excluded(k.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(k).where(k.GRAPH_NAME.eq(graph), k.TOUCHED_AT.ne(touchedAt)).execute();
        EntryDefects.derive(dsl, graph, touchedAt, EntryDefects.LOOKUP_KEYS);
    }
}
