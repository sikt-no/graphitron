package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_REACH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_REACH_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_type_reach}: how the authored schema reaches each
 * object and interface type.
 *
 * <p>The rule view beside the target, marked and swept: every row the reading derives is written
 * or touched with the reading's instant, and the graph's rows carrying another instant are swept. Runs
 * ahead of {@link TypeArrivals}, whose recursion walks this table and nothing else.
 */
public final class TypeReaches {

    private TypeReaches() {}

    /** Marks every row the reading derives, then sweeps the graph's rows it did not. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var target = GRAPHITRON_TYPE_REACH;
        var rule = GRAPHITRON_TYPE_REACH_RULE;

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.TYPE_NAME, target.EDGES, target.PARENT_NAME, target.PARENT_LIST, target.BATCHED, target.IS_ROOT,
                target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.EDGES, rule.PARENT_NAME, rule.PARENT_LIST, rule.BATCHED, rule.IS_ROOT,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(target.EDGES, excluded(target.EDGES))
            .set(target.PARENT_NAME, excluded(target.PARENT_NAME))
            .set(target.PARENT_LIST, excluded(target.PARENT_LIST))
            .set(target.BATCHED, excluded(target.BATCHED))
            .set(target.IS_ROOT, excluded(target.IS_ROOT))
            .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
