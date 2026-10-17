package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TARGET_COLUMN_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_field_target_column}: the column a scalar field
 * with no path reads on each table its read departs from. One statement over the rule view beside
 * the table it fills.
 *
 * <p>Called as a stage of the graphitron gatherer after {@link FieldSourceTables}, whose
 * departures it reads. Marked and swept: upserted on the reading's instant, then the rows this
 * reading did not name are deleted.
 */
public final class FieldTargetColumns {

    private FieldTargetColumns() {}

    /** Re-derives the graph's target columns and sweeps what it no longer names. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var target = GRAPHITRON_FIELD_TARGET_COLUMN;
        var rule = GRAPHITRON_FIELD_TARGET_COLUMN_RULE;

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME, target.FROM_SOURCE_NAME,
                target.FROM_SCHEMA, target.FROM_TABLE, target.COLUMN_NAME, target.MATCHED_BY,
                target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME, rule.FROM_SOURCE_NAME,
                    rule.FROM_SCHEMA, rule.FROM_TABLE, rule.COLUMN_NAME, rule.MATCHED_BY,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(target.COLUMN_NAME, excluded(target.COLUMN_NAME))
            .set(target.MATCHED_BY, excluded(target.MATCHED_BY))
            .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
