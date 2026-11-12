package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_VOUCHER_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes the {@code VOUCHER} rows of {@code graphitron_occurrence_binding}: each occurrence of a field
 * marked {@code @issue} binds the voucher at the table its path lands on, from the rule view beside
 * it, marked and swept. The kind has no subtype; what the voucher carries is the voucher stage's.
 *
 * <p>A step of {@link OccurrenceBindings}, over the whole graph once {@link OccurrenceDepths} has
 * written the needs and join paths whose landings it reads; the binding claims, whose {@code VOUCHER}
 * claim it reads, are written before both.
 */
public final class OccurrenceVouchers {

    private OccurrenceVouchers() {}

    /** States the graph's voucher bindings. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var r = GRAPHITRON_OCCURRENCE_VOUCHER_RULE;
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, inline("VOUCHER"), val(touchedAt, b.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.COORDINATE))
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();
    }

    /** Deletes the VOUCHER bindings this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq("VOUCHER"))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
