package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_ARRIVAL;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_ARRIVAL_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_type_arrival}: how many objects of each authored
 * object and interface type can reach one of its fields in one request.
 *
 * <p>The rule view beside the target, marked and swept: every row the reading derives is written
 * or touched with the reading's instant, and the graph's rows carrying another instant are swept. The
 * rule is recursive, walking a type up its single reaching edges over the table
 * {@link TypeReaches} writes, which is the reason it is stored at all: H2 evaluates a recursive
 * view again for every row joined to it, and {@link FieldSources} joins it for every field.
 */
public final class TypeArrivals {

    private TypeArrivals() {}

    /** Marks every row the reading derives, then sweeps the graph's rows it did not. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var target = GRAPHITRON_TYPE_ARRIVAL;
        var rule = GRAPHITRON_TYPE_ARRIVAL_RULE;

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.TYPE_NAME, target.ARRIVAL,
                target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.ARRIVAL,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(target.ARRIVAL, excluded(target.ARRIVAL))
            .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
