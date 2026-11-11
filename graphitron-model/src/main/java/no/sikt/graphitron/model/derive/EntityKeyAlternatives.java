package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_KEY_FIELD_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_KEY_FIELD_BINDING_RULE;

/**
 * The entity key stage: how each field of an authored federation {@code @key} binds at its entity's
 * table, and the {@code @key} refusals judged from it, both from the occurrence bindings
 * {@link OccurrenceDepths} and {@link OccurrenceValues} have just stated.
 *
 * <p>Runs as a stage of the graphitron gatherer after the two that write the occurrence bindings it
 * reads. Clears and re-derives the graph's {@code graphitron_key_field_binding} rows from
 * {@code graphitron_key_field_binding_rule} on {@link MintedKeys}' terms, the rows being a function of
 * relations the gatherer has just rewritten, then writes its codes' rows through
 * {@link EntryDefects#derive} from {@code graphitron_entity_key_entry_defect_rule}, which reads the
 * bindings' absence, as its last statement; the gatherer's sweep removes the defects a reading no
 * longer finds.
 */
public final class EntityKeyAlternatives {

    private EntityKeyAlternatives() {}

    /** Writes the graph's key field bindings, then its entity key defects marked with {@code readAt}. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime readAt) {
        var k = GRAPHITRON_KEY_FIELD_BINDING;
        var r = GRAPHITRON_KEY_FIELD_BINDING_RULE;
        dsl.deleteFrom(k).where(k.GRAPH_NAME.eq(graphName)).execute();
        dsl.insertInto(k)
            .columns(k.GRAPH_NAME, k.TYPE_NAME, k.DIRECTIVE_NAME, k.ORDINAL, k.POSITION, k.FIELD_NAME,
                k.COORDINATE, k.BOUND, k.KIND, k.TABLE_SOURCE_NAME, k.TABLE_SCHEMA, k.TABLE_NAME)
            .select(dsl.select(r.GRAPH_NAME, r.TYPE_NAME, r.DIRECTIVE_NAME, r.ORDINAL, r.POSITION,
                    r.FIELD_NAME, r.COORDINATE, r.BOUND, r.KIND, r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA,
                    r.TABLE_NAME)
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .execute();
        EntryDefects.derive(dsl, graphName, readAt, EntryDefects.ENTITY_KEYS);
    }
}
