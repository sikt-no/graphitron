package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SOURCE_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SOURCE_TABLE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_field_source_table}: the tables a field's read
 * departs from. One statement over the rule view beside the table it fills.
 *
 * <p>Called as a stage of the graphitron gatherer after the targets and the type bindings it reads,
 * and before the path resolution, whose head walk departs from these rows.
 * Marked and swept, on {@link FieldEndpoints}' terms.
 */
public final class FieldSourceTables {

    private FieldSourceTables() {}

    /** Re-derives the graph's departing tables and sweeps what it no longer names. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var target = GRAPHITRON_FIELD_SOURCE_TABLE;
        var rule = GRAPHITRON_FIELD_SOURCE_TABLE_RULE;

        dsl.insertInto(target)
            .columns(target.GRAPH_NAME, target.TYPE_NAME, target.FIELD_NAME,
                target.FROM_SOURCE_NAME, target.FROM_SCHEMA, target.FROM_TABLE, target.BASIS,
                target.TOUCHED_AT)
            .select(dsl
                .select(rule.GRAPH_NAME, rule.TYPE_NAME, rule.FIELD_NAME,
                    rule.FROM_SOURCE_NAME, rule.FROM_SCHEMA, rule.FROM_TABLE, rule.BASIS,
                    val(touchedAt, target.TOUCHED_AT))
                .from(rule)
                .where(rule.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(target.BASIS, excluded(target.BASIS))
            .set(target.TOUCHED_AT, excluded(target.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(target)
            .where(target.GRAPH_NAME.eq(graphName))
            .and(target.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
