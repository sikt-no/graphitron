package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_SITE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_argmapping_site}: where each written argMapping sits, inserted
 * from {@code graphitron_argmapping_site_rule} for one graph.
 *
 * <p>A step of the graphitron-ast anchor. Stored rather than read through the rule because every
 * reader of a mapping joins it into views of its own, and the climb evaluated inside each of them
 * costs an order of magnitude over a keyed table. Marked and swept: the statement upserts on the
 * reading's instant and the delete after it takes the mappings this reading no longer places.
 */
final class GraphitronArgMappingSites {

    private GraphitronArgMappingSites() {}

    static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_ARGMAPPING_SITE_RULE;
        var t = GRAPHITRON_ARGMAPPING_SITE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.DIRECTIVE_NAME,
                t.ORDINAL, t.COORDINATE, t.ELEMENT_KIND, t.REFERENCE_LINE, t.REFERENCE_COLUMN,
                t.STEP_POSITION, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    r.DIRECTIVE_NAME, r.ORDINAL, r.COORDINATE, r.ELEMENT_KIND, r.REFERENCE_LINE,
                    r.REFERENCE_COLUMN, r.STEP_POSITION, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.DIRECTIVE_NAME, excluded(t.DIRECTIVE_NAME))
            .set(t.ORDINAL, excluded(t.ORDINAL))
            .set(t.COORDINATE, excluded(t.COORDINATE))
            .set(t.ELEMENT_KIND, excluded(t.ELEMENT_KIND))
            .set(t.REFERENCE_LINE, excluded(t.REFERENCE_LINE))
            .set(t.REFERENCE_COLUMN, excluded(t.REFERENCE_COLUMN))
            .set(t.STEP_POSITION, excluded(t.STEP_POSITION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
