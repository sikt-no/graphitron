package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SOURCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SOURCE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_field_source}: whether a source object arrives at
 * an output field, and how many.
 *
 * <p>The rule view beside the target, marked and swept: every row the reading derives is written
 * or touched with the reading's instant, and the graph's rows carrying another instant are swept. Runs
 * after {@link TypeArrivals}, whose table it reads for a field's parent.
 */
public final class FieldSources {

    private FieldSources() {}

    /** Marks every row the reading derives, then sweeps the graph's rows it did not. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var target = GRAPHITRON_FIELD_SOURCE;
        var rule = GRAPHITRON_FIELD_SOURCE_RULE;

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME, target.KIND,
                target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME, rule.KIND,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(target.KIND, excluded(target.KIND))
            .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
