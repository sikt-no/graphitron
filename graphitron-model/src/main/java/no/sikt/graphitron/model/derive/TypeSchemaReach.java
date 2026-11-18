package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_SCHEMA_REACH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_SCHEMA_REACH_RULE;
import static org.jooq.impl.DSL.val;

/**
 * Fills {@code graphitron_type_schema_reach}: the closure of the types the schema leads to from an
 * operation root, marked and swept.
 *
 * <p>A field nothing reaches is one nothing in the schema leads to (Alf, 2026-11-19): its type is no
 * root and no field of a reached type returns it, itself or as a member of a union or interface it
 * returns. That is a fact of the types, stated here before the results flow, so the producer rule,
 * the result rule and the prune in {@link Fetchers} read it rather than the results' progress.
 *
 * <p>{@code graphitron_type_schema_reach_rule} is one round of it, and this merges the round until it
 * changes nothing, as {@link Results} does. A view cannot state the fixpoint, and H2 evaluates a
 * recursive view again for every row joined to it and does not deduplicate. The rule steps only from
 * rows marked with the reading's own instant, {@code store_graph.last_captured}, so a type only an
 * earlier reading reached hands nothing on. A row this reading reaches again keeps its key and only
 * takes the new mark; the sweep at the end removes what this reading did not reach.
 */
public final class TypeSchemaReach {

    private TypeSchemaReach() {}

    /** Far more rounds than any schema is deep; reaching it means the rule stopped converging. */
    private static final int ROUND_LIMIT = 1000;

    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        for (int round = 0; round(dsl, graph, touchedAt) > 0; round++) {
            if (round == ROUND_LIMIT) {
                throw new IllegalStateException("graphitron_type_schema_reach did not converge for " + graph
                    + " in " + ROUND_LIMIT + " rounds");
            }
        }
        var reach = GRAPHITRON_TYPE_SCHEMA_REACH;
        dsl.deleteFrom(reach).where(reach.GRAPH_NAME.eq(graph)).and(reach.TOUCHED_AT.ne(touchedAt))
            .execute();
    }

    /**
     * Merges one round of the rule and answers how many rows it added or marked. A row this reading
     * already marked changes nothing, which is what lets the count reach zero.
     */
    private static int round(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var reach = GRAPHITRON_TYPE_SCHEMA_REACH;
        var rule = GRAPHITRON_TYPE_SCHEMA_REACH_RULE;
        var reached = dsl.selectDistinct(rule.GRAPH_NAME, rule.TYPE_NAME)
            .from(rule).where(rule.GRAPH_NAME.eq(graph)).asTable("s");
        var mark = val(touchedAt, reach.TOUCHED_AT);
        return dsl.mergeInto(reach)
            .using(reached)
            .on(reach.GRAPH_NAME.eq(reached.field(rule.GRAPH_NAME))
                .and(reach.TYPE_NAME.eq(reached.field(rule.TYPE_NAME))))
            .whenMatchedAnd(reach.TOUCHED_AT.ne(mark)).thenUpdate().set(reach.TOUCHED_AT, mark)
            .whenNotMatchedThenInsert(reach.GRAPH_NAME, reach.TYPE_NAME, reach.TOUCHED_AT)
            .values(reached.field(rule.GRAPH_NAME), reached.field(rule.TYPE_NAME), mark)
            .execute();
    }
}
