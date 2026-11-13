package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCEPT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ACCEPT_KEY_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_KEY_FIELD_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_KEY_FIELD_BINDING_RULE;

/**
 * The entity key stage: how each field of a federation {@code @key}, authored or minted, binds at its
 * entity's table, which authored key each {@code @accept}'s vouchers decode to, and the refusals
 * judged from both, from the occurrence bindings {@link OccurrenceDepths} and {@link OccurrenceValues}
 * have just stated.
 *
 * <p>Runs as a stage of the graphitron gatherer after the two that write the occurrence bindings it
 * reads. Clears and re-derives the graph's {@code graphitron_key_field_binding} rows from
 * {@code graphitron_key_field_binding_rule} on {@link MintedKeys}' terms, the rows being a function of
 * relations the gatherer has just rewritten, and its {@code graphitron_accept_key} rows from
 * {@code graphitron_accept_key_rule} the same way, then writes its codes' rows through
 * {@link EntryDefects#derive} from {@code graphitron_entity_key_entry_defect_rule}, which reads the
 * bindings' absence, as its last statement; the gatherer's sweep removes the defects a reading no
 * longer finds.
 */
public final class EntityKeyAlternatives {

    private EntityKeyAlternatives() {}

    /** Writes the graph's key field bindings and accept keys, then its entity key defects marked with {@code readAt}. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime readAt) {
        var k = GRAPHITRON_KEY_FIELD_BINDING;
        var r = GRAPHITRON_KEY_FIELD_BINDING_RULE;
        dsl.deleteFrom(k).where(k.GRAPH_NAME.eq(graphName)).execute();
        dsl.insertInto(k)
            .columns(k.GRAPH_NAME, k.TYPE_NAME, k.DIRECTIVE_NAME, k.ORDINAL, k.POSITION, k.FIELD_NAME,
                k.KIND, k.TABLE_SOURCE_NAME, k.TABLE_SCHEMA, k.TABLE_NAME)
            .select(dsl.select(r.GRAPH_NAME, r.TYPE_NAME, r.DIRECTIVE_NAME, r.ORDINAL, r.POSITION,
                    r.FIELD_NAME, r.KIND, r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME)
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .execute();
        var a = GRAPHITRON_ACCEPT_KEY;
        var ar = GRAPHITRON_ACCEPT_KEY_RULE;
        dsl.deleteFrom(a).where(a.GRAPH_NAME.eq(graphName)).execute();
        dsl.insertInto(a)
            .columns(a.GRAPH_NAME, a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN, a.TYPE_NAME,
                a.DIRECTIVE_NAME, a.ORDINAL)
            .select(dsl.select(ar.GRAPH_NAME, ar.SOURCE_NAME, ar.SOURCE_LINE, ar.SOURCE_COLUMN,
                    ar.TYPE_NAME, ar.DIRECTIVE_NAME, ar.ORDINAL)
                .from(ar)
                .where(ar.GRAPH_NAME.eq(graphName)))
            .execute();
        EntryDefects.derive(dsl, graphName, readAt, EntryDefects.ENTITY_KEYS);
    }
}
