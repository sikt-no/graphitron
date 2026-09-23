package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_WRITE_PAYLOAD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION_WRITE_PAYLOAD_RULE;

/**
 * The capture-cadence writer of {@code graphitron_mutation_write_payload}: the write surface a
 * walker-driven DML mutation offers: which coordinate writes, with which verb, over which table,
 * through which argument.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when.
 *
 * <p>After {@link FieldScopeTables}, whose rows name the table a mutation writes, so a stage ahead
 * of it would read the previous capture's write targets.
 */
public final class MutationWritePayloads {

    private MutationWritePayloads() {}

    /** Reconciles the graph's partition: clears it, then re-derives it from the rule. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_MUTATION_WRITE_PAYLOAD;
        var rule = GRAPHITRON_MUTATION_WRITE_PAYLOAD_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
