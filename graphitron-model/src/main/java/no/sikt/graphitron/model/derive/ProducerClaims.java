package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PRODUCER_CLAIM_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_producer_claim}: the kind of producer each field claims it runs, from the
 * rule view beside it, marked and swept.
 *
 * <p>A claim is what the field's written directives say, not which directive said it, so it is keyed
 * by the field and the kind; where the first claim of a kind was written rides along for a defect to
 * point at. Runs before the fetchers, whose producer rule takes a field with one claim, and before
 * the entry defects, which report a field with two.
 */
public final class ProducerClaims {

    private ProducerClaims() {}

    /** States the graph's claims and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_PRODUCER_CLAIM;
        var r = GRAPHITRON_PRODUCER_CLAIM_RULE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.KIND, c.SOURCE_NAME, c.SOURCE_LINE,
                c.SOURCE_COLUMN, c.TOUCHED_AT, c.ELEMENT_GRAPH_REF, c.ELEMENT_COORDINATE_REF)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.KIND, r.SOURCE_NAME, r.SOURCE_LINE,
                    r.SOURCE_COLUMN, val(touchedAt, c.TOUCHED_AT), r.ELEMENT_GRAPH_REF,
                    r.ELEMENT_COORDINATE_REF)
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.KIND)
            .doUpdate()
            .set(c.SOURCE_NAME, excluded(c.SOURCE_NAME))
            .set(c.SOURCE_LINE, excluded(c.SOURCE_LINE))
            .set(c.SOURCE_COLUMN, excluded(c.SOURCE_COLUMN))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .set(c.ELEMENT_GRAPH_REF, excluded(c.ELEMENT_GRAPH_REF))
            .set(c.ELEMENT_COORDINATE_REF, excluded(c.ELEMENT_COORDINATE_REF))
            .execute();
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
