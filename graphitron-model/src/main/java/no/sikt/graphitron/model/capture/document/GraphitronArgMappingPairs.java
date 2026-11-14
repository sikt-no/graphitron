package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_argmapping_pair}: each pair of a written argMapping, named by its
 * mapping and the parameter it binds, inserted from {@code graphitron_argmapping_pair_rule} for one
 * graph.
 *
 * <p>A step of the graphitron anchor, right after the sites it names its pairs by. Stored as the
 * target the landing and the steps reference. Marked and swept: the statement upserts on the
 * reading's instant and the delete after it takes the pairs this reading no longer finds.
 */
final class GraphitronArgMappingPairs {

    private GraphitronArgMappingPairs() {}

    static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_ARGMAPPING_PAIR_RULE;
        var t = GRAPHITRON_ARGMAPPING_PAIR;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER,
                t.TARGET_KIND, t.TARGET_NAME, t.BOUND_TO, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                t.POSITION, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.DIRECTIVE_NAME, r.ORDINAL, r.OWNER,
                    r.TARGET_KIND, r.TARGET_NAME, r.BOUND_TO, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    r.POSITION, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.BOUND_TO, excluded(t.BOUND_TO))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.SOURCE_LINE, excluded(t.SOURCE_LINE))
            .set(t.SOURCE_COLUMN, excluded(t.SOURCE_COLUMN))
            .set(t.POSITION, excluded(t.POSITION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
