package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_BINDING_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_BINDING_CLAIM_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CLAIM;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes the binding claims: the kind of binding each field, input field and argument claims into
 * {@code graphitron_claim}, and what each names into {@code graphitron_binding_claim}, from the rule
 * view beside them, marked and swept.
 *
 * <p>A claim is what the element's written directives say, keyed by the element, the kind and the
 * element that claims it; what the claim names rides in the subtype for the binding to resolve, and
 * where it was written on the claim for a defect to point at. Every claim here is written, so this
 * stage claims the strongest priority.
 */
public final class BindingClaims {

    /** The stage's name on the claims it writes and sweeps. */
    static final String STAGE = "BindingClaims";

    /** What an author wrote, the strongest claim there is. */
    static final int WRITTEN = 0;

    private BindingClaims() {}

    /** States the graph's claims and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_CLAIM;
        var b = GRAPHITRON_BINDING_CLAIM;
        var r = GRAPHITRON_BINDING_CLAIM_RULE;
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
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.KIND, b.CLAIMER, b.NAMED, b.TARGET_NAME)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.KIND, r.CLAIMER, r.NAMED, r.TARGET_NAME)
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.KIND, b.CLAIMER)
            .doUpdate()
            .set(b.NAMED, excluded(b.NAMED))
            .set(b.TARGET_NAME, excluded(b.TARGET_NAME))
            .execute();
        // The subtype goes with its claim.
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.STAGE.eq(STAGE))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
