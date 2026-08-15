package no.sikt.graphitron.rewrite.derive;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.WALK_TYPE_BACKING_CLASS;

/**
 * Reifies a {@link TypeBackingClasses} as the {@code walk_type_backing_class} rows the backing
 * derivation's shadow diffs against. The production write site is the capture-and-detect pass
 * ({@code FactCapture.detect}), at capture cadence, inside the capture's graph-scoped ownership;
 * the relation's family header carries the cadence and removal criterion.
 */
public final class TypeBackingClassRows {

    private TypeBackingClassRows() {}

    /** Replaces {@code graphName}'s backing rows with {@code bindings}, atomically. */
    public static void write(DSLContext dsl, String graphName, TypeBackingClasses bindings) {
        dsl.transaction(tx -> {
            DSLContext txDsl = tx.dsl();
            txDsl.deleteFrom(WALK_TYPE_BACKING_CLASS)
                .where(WALK_TYPE_BACKING_CLASS.GRAPH_NAME.eq(graphName))
                .execute();
            var rows = bindings.byTypeName().entrySet().stream()
                .map(binding -> {
                    var row = txDsl.newRecord(WALK_TYPE_BACKING_CLASS);
                    row.setGraphName(graphName);
                    row.setTypeName(binding.getKey());
                    row.setClassName(binding.getValue());
                    return row;
                })
                .toList();
            txDsl.batchInsert(rows).execute();
        });
    }
}
