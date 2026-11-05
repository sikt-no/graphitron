package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_CLAIM_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes the producer claims into {@code graphitron_claim}: the kind of producer each field's written
 * directives claim it runs, from the rule view beside it, marked and swept.
 *
 * <p>A claim is what the field's written directives say, not which directive said it, so it is keyed
 * by the field, the kind and the element that claims it; where it was written rides along for a
 * defect to point at. Every claim here is written, so this stage claims the strongest priority. Runs
 * before the fetchers, whose producer rule takes a field with one claim, and before the entry
 * defects, which report a field with two.
 */
public final class ProducerClaims {

    /** The stage's name on the claims it writes and sweeps. */
    static final String STAGE = "ProducerClaims";

    /** What an author wrote, the strongest claim there is. */
    static final int WRITTEN = 0;

    private ProducerClaims() {}

    /** States the graph's claims and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_CLAIM;
        var r = GRAPHITRON_PRODUCER_CLAIM_RULE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.KIND, c.CLAIMER, c.PRIORITY, c.STAGE,
                c.SOURCE_NAME, c.SOURCE_LINE, c.SOURCE_COLUMN, c.TOUCHED_AT, c.ELEMENT_GRAPH_REF,
                c.ELEMENT_COORDINATE_REF)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.KIND, r.CLAIMER, val(WRITTEN, c.PRIORITY),
                    val(STAGE, c.STAGE), r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    val(touchedAt, c.TOUCHED_AT), r.ELEMENT_GRAPH_REF, r.ELEMENT_COORDINATE_REF)
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.KIND, c.CLAIMER)
            .doUpdate()
            .set(c.PRIORITY, excluded(c.PRIORITY))
            .set(c.STAGE, excluded(c.STAGE))
            .set(c.SOURCE_NAME, excluded(c.SOURCE_NAME))
            .set(c.SOURCE_LINE, excluded(c.SOURCE_LINE))
            .set(c.SOURCE_COLUMN, excluded(c.SOURCE_COLUMN))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .set(c.ELEMENT_GRAPH_REF, excluded(c.ELEMENT_GRAPH_REF))
            .set(c.ELEMENT_COORDINATE_REF, excluded(c.ELEMENT_COORDINATE_REF))
            .execute();
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.STAGE.eq(STAGE))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
