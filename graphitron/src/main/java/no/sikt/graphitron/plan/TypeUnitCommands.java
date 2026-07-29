package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.TypeUnitCommand;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.HasInputRecordShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Produces the type-keyed command relation ({@link TypeUnitRelation}): one row per per-type
 * unit this run emits, replacing the generator families' own membership loops kind by kind.
 * Each kind states its own derivation honestly rather than dressing them all as one rule:
 *
 * <ul>
 *   <li><b>Input records</b>: the argument-reachability closure intersected with the
 *       record-shape capability ({@link GraphitronSchema#argumentReachableInputs()} and
 *       {@link HasInputRecordShape}); a reach fact, neither variant membership nor a
 *       coordinate fold.</li>
 * </ul>
 *
 * <p>Rows are sorted by type name within each kind, preserving the retired loops'
 * deterministic file output.
 */
public final class TypeUnitCommands {

    private TypeUnitCommands() {}

    public static TypeUnitRelation produce(GraphitronSchema schema, String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<TypeUnitCommand>();
        for (var entry : schema.types().entrySet()) {
            if (entry.getValue() instanceof HasInputRecordShape
                    && schema.argumentReachableInputs().contains(entry.getKey())) {
                rows.add(new TypeUnitCommand.InputRecordUnit(entry.getKey(),
                    units.inputRecord(entry.getKey())));
            }
        }
        rows.sort(Comparator.comparing(TypeUnitCommand::typeName));
        return new TypeUnitRelation(rows);
    }
}
