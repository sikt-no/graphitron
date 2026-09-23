package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_WRITE_DESTINATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_WRITE_DESTINATION_RULE;

/**
 * The capture-cadence writer of {@code graphitron_mutation_write_destination}: what each column a
 * write payload contributes is for, one row per contributing occurrence per decode slot: the
 * finished partition, and what an emitter assembles a statement out of.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link MutationPayloadKeyMemberships} and {@link MutationPayloadColumns}, the last
 * stage of the write chain and the deepest rule in the store, which is why it runs last:
 * everything it reads has to be written first.
 */
public final class MutationWriteDestinations {

    private MutationWriteDestinations() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_MUTATION_WRITE_DESTINATION;
        var rule = GRAPHITRON_MUTATION_WRITE_DESTINATION_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
