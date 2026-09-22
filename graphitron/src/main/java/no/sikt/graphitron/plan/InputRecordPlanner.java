package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.TypeUnitCommand;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.GraphitronSchema;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REACHABLE_INPUT;
import no.sikt.graphitron.rewrite.model.HasInputRecordShape;

import java.util.ArrayList;
import java.util.Set;
import java.util.Comparator;
import java.util.List;

/**
 * Plans the input-record carrier class each argument-reachable input type gets: one compilation
 * unit per row, at the address the row commits.
 *
 * <p>Its membership is a reach fact and nothing else, the store's argument-reachable input types
 * intersected with the record-shape capability ({@link HasInputRecordShape}). Neither variant
 * membership nor a coordinate fold, which is why it is its own planner: the fetcher family asks
 * which types host fetchers and the schema-shape family asks which have a graphql-java form, and
 * three unrelated questions in one producer meant a caller wanting one of them reached past the
 * other two.
 *
 * <p>Rows sort by type name, which is what makes the emitted file set the same twice.
 */
public final class InputRecordPlanner {

    private InputRecordPlanner() {}

    /**
     * The input-record carrier each argument-reachable input type gets, one compilation unit per
     * row, at the address the row commits.
     *
     * <p>The query below is this planner's own and is not offered to anyone else. What a consumer
     * needs is its business, so a second consumer writes its own rather than borrowing this one,
     * and a derivation two of them would share is a fact missing from the model rather than a
     * helper missing from this package. {@code graphitron_argument_reachable_input} is that
     * push-down, which is why asking it here is four lines.
     */
    public static List<TypeUnitCommand.InputRecordUnit> produce(StoreHandle store,
                                                                GraphitronSchema schema,
                                                                String outputPackage) {
        var argumentReachableInputs = argumentReachableInputs(store);
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<TypeUnitCommand.InputRecordUnit>();
        for (var entry : schema.types().entrySet()) {
            if (entry.getValue() instanceof HasInputRecordShape
                    && argumentReachableInputs.contains(entry.getKey())) {
                rows.add(new TypeUnitCommand.InputRecordUnit(entry.getKey(),
                    units.inputRecord(entry.getKey())));
            }
        }
        rows.sort(Comparator.comparing(TypeUnitCommand::typeName));
        return List.copyOf(rows);
    }

    /** What this planner asks the store, and the only place it is asked. */
    private static Set<String> argumentReachableInputs(StoreHandle store) {
        if (store == null) {
            return Set.of();
        }
        var r = GRAPHITRON_ARGUMENT_REACHABLE_INPUT;
        return Set.copyOf(store.dsl()
            .select(r.TYPE_NAME)
            .from(r)
            .where(r.GRAPH_NAME.eq(store.graphName()))
            .fetch(r.TYPE_NAME));
    }

}
