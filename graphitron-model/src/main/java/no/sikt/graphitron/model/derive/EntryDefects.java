package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_RULE;

/**
 * The capture-cadence writer of {@code graphitron_entry_defect}: the written graphitron directives
 * the generator will not emit for, at the positions they were written.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. What this
 * class adds is who evaluates the rule and when.
 *
 * <p><b>Stored because it is read interactively and costs a walk.</b> Most of the rule is cheap,
 * the write arms reading entries the gatherer already anchored. The chain arms are not: they reach
 * {@code graphitron_field_chain_link_resolution}, whose two recursive walks run over every chain in
 * the graph, and a per-file predicate cannot push into a recursion behind a view boundary. As a
 * view the whole thing was re-walked on every read, and the read that matters is the editor's, one
 * file's diagnostics on the interactive path. The LSP's scan-count gate is what caught it, and
 * what it measured is recorded with the item's other measurements rather than here, a ceiling
 * being a number that moves.
 *
 * <p>The deciding argument is not the number, though, and would hold at a smaller one: the walk
 * already runs once per capture. {@link FieldChainLinkResolutions} stores every reading either walk
 * reaches, {@link FieldTableLinks} keeps the ones that survive, and a chain defect is the rest.
 * Computing them again per read is a second evaluation of a rule the capture has already run, which
 * is the shape this whole arc exists to remove rather than a cost to be weighed. The same holds
 * inside this stage: the chain arms read the stored walk rather than walking, because a walk named
 * from a correlated subquery here is walked once per chain it drives.
 */
public final class EntryDefects {

    private EntryDefects() {}

    /** Reconciles the graph's entry defects: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_ENTRY_DEFECT;
        var rule = GRAPHITRON_ENTRY_DEFECT_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .columns(
                target.GRAPH_NAME, target.SOURCE_NAME, target.SOURCE_LINE, target.SOURCE_COLUMN,
                target.CODE, target.TYPE_NAME, target.FIELD_NAME, target.DETAIL)
            .select(dsl
                .select(
                    rule.GRAPH_NAME, rule.SOURCE_NAME, rule.SOURCE_LINE, rule.SOURCE_COLUMN,
                    rule.CODE, rule.TYPE_NAME, rule.FIELD_NAME, rule.DETAIL)
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
