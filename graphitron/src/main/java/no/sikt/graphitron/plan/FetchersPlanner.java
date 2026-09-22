package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.TypeUnitCommand;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.NestingReach;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plans the fetcher-family compilation units a type gets: its {@code <Type>Fetchers} class, the
 * {@code @error} host beside it, and a connection carrier's pair.
 *
 * <p>Three arms in one planner because they answer one question, which types host fetchers, and
 * split the answer by what the host is rather than by what decides it.
 */
public final class FetchersPlanner {

    private FetchersPlanner() {}

    /**
     * The fetchers kinds' rows: the variant-classified hosts sorted by name, then the reach
     * fold's nested population in walk order, then the {@code @error} hosts on their own arm,
     * then the connection pairs. A nested-reached name that also classifies as a fetcher-hosting
     * variant gets exactly the variant row (the merged view); one that classifies as any OTHER
     * variant would key-collide in the relation and fail loudly, where the retired loop
     * silently emitted a same-named second class.
     */

    public static List<TypeUnitCommand> produce(GraphitronSchema schema, String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
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
        // The @error population gets its own arm: its rows carry the ErrorMappings ref the
        // emitted message() body names when the type declares a client-message override. The
        // population comes from the model's own name-ordered fold, the same one the mappings
        // emitter mints its per-type dispatch tables over, so the two cannot disagree about which
        // names exist.
        schema.errorTypes().keySet().forEach(name -> rows.add(new TypeUnitCommand.ErrorFetchersUnit(
            name, units.fetchers(name), units.errorMappings())));
        schema.types().values().stream()
            .filter(t -> t instanceof GraphitronType.ConnectionType)
            .map(t -> (GraphitronType.ConnectionType) t)
            .sorted(Comparator.comparing(GraphitronType.ConnectionType::name))
            .forEach(ct -> rows.add(new TypeUnitCommand.ConnectionFetchersUnit(ct.name(),
                units.fetchers(ct.name()), units.fetchers(ct.edgeTypeName()))));
        return rows;
    }
}
