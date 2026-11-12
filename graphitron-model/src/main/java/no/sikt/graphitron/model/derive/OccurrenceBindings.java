package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

/**
 * The {@code occurrence-bindings} stage: what each occurrence binds once {@link OccurrenceDepths} has
 * resolved where every occurrence's rows come from. The producers' lifts, the node ids and the key
 * columns argMapping paths project out of them, the columns, the tables and the {@code @condition}
 * calls applied to them, each over the whole graph from its rule view, then swept.
 *
 * <p>Nothing in the depths reads these, so they are written once rather than a depth at a time.
 * Each step reads the ones before it: the columns skip what a node id binds, a table binding is
 * refused where a column or node id binds, and a condition is applied to the tables bound.
 */
public final class OccurrenceBindings {

    private OccurrenceBindings() {}

    /** Writes the stage's relations, then sweeps what this reading did not mark. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        OccurrenceLifts.write(dsl, graph, touchedAt);
        OccurrenceNodeIds.write(dsl, graph, touchedAt);
        LandingKeyColumns.write(dsl, graph, touchedAt);
        OccurrenceColumns.write(dsl, graph, touchedAt);
        OccurrenceTables.write(dsl, graph, touchedAt);
        OccurrenceConditions.write(dsl, graph, touchedAt);
        // The lifts need no sweep: their writer clears what this reading did not mark before it writes.
        OccurrenceConditions.sweep(dsl, graph, touchedAt);
        OccurrenceNodeIds.sweep(dsl, graph, touchedAt);
        LandingKeyColumns.sweep(dsl, graph, touchedAt);
        OccurrenceColumns.sweep(dsl, graph, touchedAt);
        OccurrenceTables.sweep(dsl, graph, touchedAt);
    }
}
