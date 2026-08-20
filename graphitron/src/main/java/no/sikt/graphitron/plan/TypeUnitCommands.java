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
 *   <li><b>Schema shapes</b>: near-total variant membership. Every classified type gets one
 *       row except scalars (registered off the resolved constant, no per-type class),
 *       unclassified verdicts (the validator has already failed the run before emission) and
 *       the federation-internal {@code _}-prefixed names; the total switch maps each permit to
 *       its graphql-java form. The {@code registersFetchers} flag is the coordinate-grain fact
 *       "this class carries a {@code registerFetchers} body": true for connection and edge
 *       carriers unconditionally, for the fetcher-hosting classifications exactly when the type
 *       owns a classified coordinate, and for reach-fold names gated on
 *       {@link NestingReach#ownsFetchers}. {@code @error} types register their fixed
 *       path/message pair inline in the schema assembler, never through a
 *       {@code registerFetchers} body, so their rows carry {@code false}.</li>
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
        rows.addAll(schemaShapeRows(schema, units));
        return new TypeUnitRelation(rows);
    }

    /**
     * The schema-shape kind's rows, sorted by type name. The switch is total over the
     * classification permits with no default: a new permit is a compile-time decision here
     * (which form, or deliberately no row), never a silent skip at a renderer's
     * {@code instanceof} chain. A {@code null} form is the deliberate no-row verdict: scalars
     * register through their resolved constants, and an unclassified verdict never reaches
     * emission (the validator fails the run first).
     */
    private static List<TypeUnitCommand> schemaShapeRows(GraphitronSchema schema, GeneratedUnits units) {
        var reach = schema.nestingReach();
        var rows = new ArrayList<TypeUnitCommand>();
        schema.types().entrySet().stream()
            .sorted(java.util.Map.Entry.comparingByKey())
            .forEach(entry -> {
                String name = entry.getKey();
                if (name.startsWith("_")) {
                    return; // introspection / federation-injected internals: no user-surface class
                }
                var form = switch (entry.getValue()) {
                    case GraphitronType.TableType ignored          -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.NodeType ignored           -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.TableInterfaceType ignored -> TypeUnitCommand.SchemaShapeForm.INTERFACE;
                    case GraphitronType.ResultType ignored         -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.RootType ignored           -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.InterfaceType ignored      -> TypeUnitCommand.SchemaShapeForm.INTERFACE;
                    case GraphitronType.UnionType ignored          -> TypeUnitCommand.SchemaShapeForm.UNION;
                    case GraphitronType.ErrorType ignored          -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.InputType ignored          -> TypeUnitCommand.SchemaShapeForm.INPUT;
                    case GraphitronType.ConnectionType ignored     -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.EdgeType ignored           -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.PageInfoType ignored       -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.FacetsType ignored         -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.FacetValueType ignored     -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.NestingType ignored        -> TypeUnitCommand.SchemaShapeForm.OBJECT;
                    case GraphitronType.EnumType ignored           -> TypeUnitCommand.SchemaShapeForm.ENUM;
                    case GraphitronType.ScalarType ignored         -> null;
                    case GraphitronType.UnclassifiedType ignored   -> null;
                };
                if (form == null) {
                    return;
                }
                rows.add(new TypeUnitCommand.SchemaShapeUnit(name, units.schemaShape(name), form,
                    registersFetchers(schema, reach, name, entry.getValue())));
            });
        return rows;
    }

    /**
     * Whether {@code name}'s schema-shape class carries a {@code registerFetchers} body. One
     * rule per population, mirroring the retired registrations emitter's construction gates:
     * connection and edge carriers always (their delegate methods exist unconditionally);
     * fetcher-hosting classifications exactly when the type owns a classified coordinate (an
     * all-unclassified body would be empty and its call site an orphan); everything else via
     * the reach fold's {@link NestingReach#ownsFetchers} gate. A hosting classification that is
     * also reach-fold-visible answers from the hosting arm, matching the merged-view body the
     * registrations emitter renders for it.
     */
    private static boolean registersFetchers(GraphitronSchema schema, NestingReach reach,
                                             String name, GraphitronType type) {
        if (type instanceof GraphitronType.ConnectionType || type instanceof GraphitronType.EdgeType) {
            return true;
        }
        if (type instanceof GraphitronType.TableType
                || type instanceof GraphitronType.NodeType
                || type instanceof GraphitronType.RootType
                || type instanceof GraphitronType.ResultType) {
            return schema.fieldsOf(name).stream()
                .anyMatch(f -> !(f instanceof no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField));
        }
        return reach.representatives().containsKey(name)
            && NestingReach.ownsFetchers(reach.wiringFor(name).nestedFields());
    }

    /**
     * The fetchers kinds' rows: the variant-classified hosts sorted by name, then the reach
     * fold's nested population in walk order, then the {@code @error} hosts on their own arm,
     * then the connection pairs. A nested-reached name that also classifies as a fetcher-hosting
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
