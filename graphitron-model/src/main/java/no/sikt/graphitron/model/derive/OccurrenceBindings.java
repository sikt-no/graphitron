package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

/**
 * The {@code occurrence-bindings} stage: what each occurrence binds once {@link OccurrenceDepths} has
 * resolved where every occurrence's rows come from. The producers' lifts, the node ids, the columns
 * a node id's key is carried back to through its path's one foreign key, and the key
 * columns argMapping paths project out of them, the columns, the nesting fields read off their
 * parent's row, the vouchers, the {@code @externalField} expressions and their calls, the tables and
 * the {@code @condition}
 * calls applied to them, each over the whole graph from its rule view, then swept; and last which
 * projection reads each field occurrence, a depth at a time, and the table-bound types whose
 * projection reads the session, a pass at a time until one adds nothing.
 *
 * <p>Nothing in the depths reads these, so they are written once rather than a depth at a time.
 * Each step reads the ones before it: the columns skip what a node id binds, a table binding is
 * refused where a column or node id binds, and a condition is applied to the tables bound. Last,
 * the defects of a node id no binding could be written for and of an expression no call could be
 * pinned for, from the stage's own rule, and the key columns under each unresolved projection.
 */
public final class OccurrenceBindings {

    private OccurrenceBindings() {}

    /** Writes the stage's relations, then sweeps what this reading did not mark. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        // The nesting fields first: a producer beneath one is lifted off the row it reads.
        OccurrenceNestings.write(dsl, graph, touchedAt);
        OccurrenceLifts.write(dsl, graph, touchedAt);
        OccurrenceNodeIds.write(dsl, graph, touchedAt);
        OccurrenceNodeIdCarriedColumns.write(dsl, graph, touchedAt);
        LandingKeyColumns.write(dsl, graph, touchedAt);
        OccurrenceColumns.write(dsl, graph, touchedAt);
        OccurrenceVouchers.write(dsl, graph, touchedAt);
        OccurrenceExpressions.write(dsl, graph, touchedAt);
        OccurrenceTables.write(dsl, graph, touchedAt);
        OccurrenceFilterTerms.write(dsl, graph, touchedAt);
        OccurrenceConditions.write(dsl, graph, touchedAt);
        // The lifts need no sweep: their writer clears what this reading did not mark before it writes.
        OccurrenceConditions.sweep(dsl, graph, touchedAt);
        OccurrenceNodeIdCarriedColumns.sweep(dsl, graph, touchedAt);
        OccurrenceNodeIds.sweep(dsl, graph, touchedAt);
        LandingKeyColumns.sweep(dsl, graph, touchedAt);
        OccurrenceColumns.sweep(dsl, graph, touchedAt);
        OccurrenceNestings.sweep(dsl, graph, touchedAt);
        OccurrenceVouchers.sweep(dsl, graph, touchedAt);
        OccurrenceExpressions.sweep(dsl, graph, touchedAt);
        OccurrenceTables.sweep(dsl, graph, touchedAt);
        OccurrenceFilterTerms.sweep(dsl, graph, touchedAt);
        // Which projection reads each field occurrence, once the nesting fields hold this reading's
        // rows alone; then the types whose projection reads the session, closing over the inline calls
        // of each projection and seeded by the conditions.
        OccurrenceProjections.derive(dsl, graph, touchedAt);
        ProjectionSessions.derive(dsl, graph, touchedAt);
        EntryDefects.derive(dsl, graph, touchedAt, EntryDefects.BINDINGS);
        EntryDefectKeyColumns.derive(dsl, graph, touchedAt, EntryDefects.BINDINGS.stage());
    }
}
