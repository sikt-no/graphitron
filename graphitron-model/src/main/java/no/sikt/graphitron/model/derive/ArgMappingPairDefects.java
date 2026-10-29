package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_PAIR_DEFECT_RULE;

/**
 * The capture-cadence writer of {@code graphitron_argmapping_pair_defect}: the entry-grain detail
 * of the defects {@code graphitron_entry_defect} states once per mapping string.
 *
 * <p>One statement over the rule view beside the target. It runs after the string-grain stage
 * because its rows hang off that stage's: the same rule view produces both grains, so the string's
 * verdict and its entries' details cannot disagree.
 *
 * <p>Reconciles the graph's partition: clears it, then re-derives.
 */
public final class ArgMappingPairDefects {

    private ArgMappingPairDefects() {}

    /** Reconciles the graph's entry-grain defects: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_ARGMAPPING_PAIR_DEFECT;
        var rule = GRAPHITRON_ARGMAPPING_PAIR_DEFECT_RULE;

        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.SOURCE_NAME, target.SOURCE_LINE,
                target.SOURCE_COLUMN, target.POSITION, target.CODE, target.DETAIL)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.SOURCE_NAME, rule.SOURCE_LINE, rule.SOURCE_COLUMN,
                    rule.POSITION, rule.CODE, rule.DETAIL)
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
