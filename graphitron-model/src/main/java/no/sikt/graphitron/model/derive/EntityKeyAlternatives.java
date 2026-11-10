package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

/**
 * The entity key stage: the federation {@code @key} refusals, judged from how each key field binds
 * at its entity's table, which {@link OccurrenceDepths} and {@link OccurrenceValues} have just
 * stated.
 *
 * <p>Runs as a stage of the graphitron gatherer after the two that write the occurrence bindings it reads, and writes
 * its codes' rows through {@link EntryDefects#derive} from {@code graphitron_entity_key_entry_defect_rule}
 * as its last statement; the gatherer's sweep removes the ones a reading no longer finds.
 */
public final class EntityKeyAlternatives {

    private EntityKeyAlternatives() {}

    /** Writes the graph's entity key defects, marked with {@code readAt}. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime readAt) {
        EntryDefects.derive(dsl, graphName, readAt, EntryDefects.ENTITY_KEYS);
    }
}
