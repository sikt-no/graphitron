package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TABLE_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_TABLE_LINK_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_field_table_link}: where each link of a field's
 * chain departs and arrives, toward each target the field has.
 *
 * <p>The rule is {@code graphitron_field_table_link_rule} and this stage only runs it. What it adds
 * is who evaluates it and when, which is the whole of the difference between a rule and a stage;
 * the {@code EXCEPT} between the view and this table stays runnable for as long as both exist, and
 * {@code FieldTableLinksTest} runs it.
 *
 * <p>Called as a stage of the graphitron gatherer after {@link FieldEndpoints} and
 * {@link FieldRoutines}, whose rows the rule reads. Neither the stage nor the rule filters those on
 * this reading's instant, and neither needs to: both of those stages sweep their own stale rows
 * before this one runs, so what the rule reads is this reading's and nothing else.
 *
 * <p>Marked and swept, on {@link FieldEndpoints}' terms. One statement rather than five per position
 * of the deepest chain in the graph, and the resolution that used to be a loop is a walk stated in
 * the catalog where a parse can read what it reads.
 */
public final class FieldTableLinks {

    private FieldTableLinks() {}

    /** Re-derives the graph's resolved chain links; see the class javadoc. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var t = GRAPHITRON_FIELD_TABLE_LINK;
        var r = GRAPHITRON_FIELD_TABLE_LINK_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME,
                t.TARGET_SOURCE_NAME, t.TARGET_SCHEMA, t.TARGET_TABLE, t.POSITION,
                t.VIA, t.KEY_MATCHED_BY,
                t.CONSTRAINT_SOURCE_NAME, t.CONSTRAINT_SCHEMA, t.CONSTRAINT_TABLE,
                t.CONSTRAINT_NAME, t.FK_ON_FROM,
                t.FROM_SOURCE_NAME, t.FROM_SCHEMA, t.FROM_TABLE,
                t.TO_SOURCE_NAME, t.TO_SCHEMA, t.TO_TABLE, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.TYPE_NAME, r.FIELD_NAME,
                    r.TARGET_SOURCE_NAME, r.TARGET_SCHEMA, r.TARGET_TABLE, r.POSITION,
                    r.VIA, r.KEY_MATCHED_BY,
                    r.CONSTRAINT_SOURCE_NAME, r.CONSTRAINT_SCHEMA, r.CONSTRAINT_TABLE,
                    r.CONSTRAINT_NAME, r.FK_ON_FROM,
                    r.FROM_SOURCE_NAME, r.FROM_SCHEMA, r.FROM_TABLE,
                    r.TO_SOURCE_NAME, r.TO_SCHEMA, r.TO_TABLE,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(t.VIA, excluded(t.VIA))
            .set(t.KEY_MATCHED_BY, excluded(t.KEY_MATCHED_BY))
            .set(t.CONSTRAINT_SOURCE_NAME, excluded(t.CONSTRAINT_SOURCE_NAME))
            .set(t.CONSTRAINT_SCHEMA, excluded(t.CONSTRAINT_SCHEMA))
            .set(t.CONSTRAINT_TABLE, excluded(t.CONSTRAINT_TABLE))
            .set(t.CONSTRAINT_NAME, excluded(t.CONSTRAINT_NAME))
            .set(t.FK_ON_FROM, excluded(t.FK_ON_FROM))
            .set(t.FROM_SOURCE_NAME, excluded(t.FROM_SOURCE_NAME))
            .set(t.FROM_SCHEMA, excluded(t.FROM_SCHEMA))
            .set(t.FROM_TABLE, excluded(t.FROM_TABLE))
            .set(t.TO_SOURCE_NAME, excluded(t.TO_SOURCE_NAME))
            .set(t.TO_SCHEMA, excluded(t.TO_SCHEMA))
            .set(t.TO_TABLE, excluded(t.TO_TABLE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
