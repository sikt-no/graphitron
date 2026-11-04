package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_BINDING_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_BINDING_CLAIM_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_binding_claim}: the kind of binding each field, input field and argument
 * claims, from the rule view beside it, marked and swept.
 *
 * <p>A claim is what the element's written directives say, keyed by the element and the kind; what
 * the claim names and where the first claim of a kind was written ride along, the first for the
 * binding to resolve and the second for a defect to point at.
 */
public final class BindingClaims {

    private BindingClaims() {}

    /** States the graph's claims and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_BINDING_CLAIM;
        var r = GRAPHITRON_BINDING_CLAIM_RULE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.KIND, c.NAMED, c.SOURCE_NAME, c.SOURCE_LINE,
                c.SOURCE_COLUMN, c.TOUCHED_AT, c.ELEMENT_GRAPH_REF, c.ELEMENT_COORDINATE_REF)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.KIND, r.NAMED, r.SOURCE_NAME, r.SOURCE_LINE,
                    r.SOURCE_COLUMN, val(touchedAt, c.TOUCHED_AT), r.ELEMENT_GRAPH_REF,
                    r.ELEMENT_COORDINATE_REF)
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.KIND)
            .doUpdate()
            .set(c.NAMED, excluded(c.NAMED))
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
