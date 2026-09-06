package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CHAIN_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.partitionBy;
import static org.jooq.impl.DSL.rowNumber;

/**
 * The capture-cadence writer of a field's table chain as ordered applications.
 *
 * <p>A field's chain is the concatenation, in written order, of each directive application's
 * contribution: {@code @reference} adds hops and {@code @routine} adds its result table as a node.
 * The manual states that the order is load-bearing, and no relation carried it. The two decode
 * relations each number their own applications, so a field carrying {@code @reference},
 * {@code @routine}, {@code @reference} holds ordinals 0, 0 and 1 across two relations with nothing
 * relating them.
 *
 * <p>The order was captured all along. {@code graphql_field_directive} holds every application with
 * its source position, so the sequence is a rank over positions rather than something a walk has to
 * rediscover. What was missing is a relation that states it, and the difference is not cosmetic: the
 * one reader that recovers the order by comparing positions gets it wrong, anchoring on the routine
 * and admitting only applications that follow it, so on the manual's own sandwich example it reports
 * two nodes where the manual describes four.
 *
 * <p>Stored rather than left to each reader on {@code graphitron_field_navigation}'s terms: a reader
 * joins on the answer, and a rank over source positions is not a column an index can lead with.
 *
 * <p>Two directives contribute and {@code @referenceFor} is deliberately not one of them. It states
 * one participant's own path rather than the field's chain, so it is a route of its own rather than
 * a step of this one.
 *
 * <p>Runs as the first stage of the graphitron gatherer after the directive decode flushes, because
 * it reads the transcription alone and everything downstream that wants an ordered chain wants it
 * before its own rule runs.
 */
public final class FieldChainApplications {

    private FieldChainApplications() {}

    /** Clears and re-derives the graph's chain applications; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName) {
        // Safe to clear where the anchors are not: nothing keys into this relation, so emptying it
        // takes nothing with it, and a chain an edit reordered has to stop being the old order.
        dsl.deleteFrom(GRAPHITRON_FIELD_CHAIN_APPLICATION)
            .where(GRAPHITRON_FIELD_CHAIN_APPLICATION.GRAPH_NAME.eq(graphName)).execute();

        var d = GRAPHQL_FIELD_DIRECTIVE;
        // Position first, which is the written order the manual means. The ordinal breaks a tie the
        // transcription should never produce, two applications of one directive at one position, and
        // is there so the rank is total: a rank with ties would number two rows the same and the
        // primary key would refuse the second, turning a capture oddity into a failed capture.
        var position = rowNumber()
            .over(partitionBy(d.GRAPH_NAME, d.TYPE_NAME, d.FIELD_NAME)
                .orderBy(d.SOURCE_LINE.asc().nullsLast(), d.SOURCE_COLUMN.asc().nullsLast(),
                    d.DIRECTIVE_NAME.asc(), d.ORDINAL.asc()))
            .minus(inline(1));

        dsl.insertInto(GRAPHITRON_FIELD_CHAIN_APPLICATION)
            .columns(GRAPHITRON_FIELD_CHAIN_APPLICATION.GRAPH_NAME,
                GRAPHITRON_FIELD_CHAIN_APPLICATION.TYPE_NAME,
                GRAPHITRON_FIELD_CHAIN_APPLICATION.FIELD_NAME,
                GRAPHITRON_FIELD_CHAIN_APPLICATION.CHAIN_POSITION,
                GRAPHITRON_FIELD_CHAIN_APPLICATION.DIRECTIVE_NAME,
                GRAPHITRON_FIELD_CHAIN_APPLICATION.ORDINAL)
            .select(dsl
                .select(d.GRAPH_NAME, d.TYPE_NAME, d.FIELD_NAME, position, d.DIRECTIVE_NAME,
                    d.ORDINAL)
                .from(d)
                .where(d.GRAPH_NAME.eq(graphName))
                .and(d.DIRECTIVE_NAME.in(inline("reference"), inline("routine"))))
            .execute();
    }
}
