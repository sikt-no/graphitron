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
 * <p>A step of the graphitron anchor. Stored rather than read through the rule because every
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
            .columns(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER, t.KIND,
                t.ELEMENT_KIND, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.DIRECTIVE_NAME, r.ORDINAL, r.OWNER,
                    r.KIND, r.ELEMENT_KIND, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER)
            .doUpdate()
            .set(t.KIND, excluded(t.KIND))
            .set(t.ELEMENT_KIND, excluded(t.ELEMENT_KIND))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
