package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_COLUMN_SCOPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_COLUMN_SCOPE_RULE;

/**
 * The capture-cadence writer of {@code graphitron_field_column_scope}: which table the column
 * names written at a field's site resolve against.
 *
 * <p>One statement over the rule view beside the target, which is what a stage is here. The rule
 * stays stated once, in SQL, in the catalog where {@link ViewReferences} can read what it reads;
 * what this class adds is who evaluates it and when. Restating the three navigation rules as jOOQ
 * expressions would state them a second time with no oracle to prove the two statements agree,
 * where an {@code EXCEPT} between this target and its rule view is runnable for as long as both
 * exist.
 *
 * <p>Runs first in the derivation stratum, ahead of the hand-written producers rather than after
 * them, and that is a position its read set earns rather than a preference. Expanded through every
 * view it names, the rule bottoms out entirely in {@code graphitron_} and {@code graphql_} facts:
 * the gatherer's own stages, the type binding and the reference walk among them, and nothing any
 * later step of the pass writes. So there is no table here whose previous capture's rows it could
 * read, and running first is what lets a later step read these rows.
 */
public final class FieldColumnScopes {

    private FieldColumnScopes() {}

    /** Reconciles the graph's field-site column scopes: clears the partition, then re-derives it. */
    public static void derive(DSLContext dsl, String graphName) {
        var target = GRAPHITRON_FIELD_COLUMN_SCOPE;
        var rule = GRAPHITRON_FIELD_COLUMN_SCOPE_RULE;

        // Reconcile rather than append: the store persists across graphitron:dev rounds, so a
        // second capture of one graph is the ordinary case and an appending stage would fail on
        // the target's primary key.
        dsl.deleteFrom(target).where(target.GRAPH_NAME.eq(graphName)).execute();

        dsl.insertInto(target)
            .select(dsl.select(rule.fields()).from(rule).where(rule.GRAPH_NAME.eq(graphName)))
            .execute();
    }
}
