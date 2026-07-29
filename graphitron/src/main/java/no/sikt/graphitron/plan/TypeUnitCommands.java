package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.TypeUnitCommand;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.NestingReach;
import no.sikt.graphitron.rewrite.model.GraphitronType;
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
 *   <li><b>Fetchers</b>: genuinely mixed. The fetcher-hosting classifications are variant
 *       membership (table, node, root and producer-result types, unconditionally, empty
 *       coordinate set included; {@code @error} types; connection carriers as the two-ref
 *       pair); the nesting/pivot-reached population is the schema's reach fold
 *       ({@link GraphitronSchema#nestingReach()}) minus the variant-classified names (the
 *       retired two-pass loop's merged-view seed), gated on
 *       {@link NestingReach#ownsFetchers}.</li>
 * </ul>
 *
 * <p>Rows sort by type name within each kind's population, preserving the retired loops'
 * deterministic file output (the nested population keeps the reach walk's first-occurrence
 * order, which the retired pass produced by construction).
 */
public final class TypeUnitCommands {

    private TypeUnitCommands() {}

    public static TypeUnitRelation produce(GraphitronSchema schema, String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var rows = new ArrayList<TypeUnitCommand>();

        var inputRecords = new ArrayList<TypeUnitCommand>();
        for (var entry : schema.types().entrySet()) {
            if (entry.getValue() instanceof HasInputRecordShape
                    && schema.argumentReachableInputs().contains(entry.getKey())) {
                inputRecords.add(new TypeUnitCommand.InputRecordUnit(entry.getKey(),
                    units.inputRecord(entry.getKey())));
            }
        }
        inputRecords.sort(Comparator.comparing(TypeUnitCommand::typeName));
        rows.addAll(inputRecords);

        rows.addAll(fetchersRows(schema, units));
        return new TypeUnitRelation(rows);
    }

    /**
     * The fetchers kind's rows: the variant-classified hosts sorted by name, then the reach
     * fold's nested population in walk order, then the {@code @error} hosts, then the
     * connection pairs. A nested-reached name that also classifies as a fetcher-hosting
     * variant gets exactly the variant row (the merged view); one that classifies as any OTHER
     * variant would key-collide in the relation and fail loudly, where the retired loop
     * silently emitted a same-named second class.
     */
    private static List<TypeUnitCommand> fetchersRows(GraphitronSchema schema, GeneratedUnits units) {
        var rows = new ArrayList<TypeUnitCommand>();
        var hosting = new java.util.HashSet<String>();
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType
                      || e.getValue() instanceof GraphitronType.RootType
                      || e.getValue() instanceof GraphitronType.ResultType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .forEach(name -> {
                hosting.add(name);
                rows.add(new TypeUnitCommand.FetchersUnit(name, units.fetchers(name)));
            });
        var reach = schema.nestingReach();
        for (var name : reach.reachedTypeNames()) {
            if (hosting.contains(name)) {
                continue;
            }
            if (NestingReach.ownsFetchers(reach.wiringFor(name).nestedFields())) {
                rows.add(new TypeUnitCommand.FetchersUnit(name, units.fetchers(name)));
            }
        }
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.ErrorType)
            .map(java.util.Map.Entry::getKey)
            .sorted()
            .forEach(name -> rows.add(new TypeUnitCommand.FetchersUnit(name, units.fetchers(name))));
        schema.types().values().stream()
            .filter(t -> t instanceof GraphitronType.ConnectionType)
            .map(t -> (GraphitronType.ConnectionType) t)
            .sorted(Comparator.comparing(GraphitronType.ConnectionType::name))
            .forEach(ct -> rows.add(new TypeUnitCommand.ConnectionFetchersUnit(ct.name(),
                units.fetchers(ct.name()), units.fetchers(ct.edgeTypeName()))));
        return rows;
    }
}
