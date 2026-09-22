package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.TypeUnitCommand;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.NestingReach;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plans the schema-shape class each classified type gets: one compilation unit per type that has
 * a graphql-java form, at the address its row commits.
 *
 * <p>Its own planner because its membership is its own question. The five emitters that read these
 * rows want nothing else, and asking them through a producer that also plans input records made
 * them reach past four arms they have no use for and take a store-less overload to avoid a handle
 * they could not supply.
 */
public final class SchemaShapePlanner {

    private SchemaShapePlanner() {}

    /**
     * The schema-shape kind's rows, sorted by type name. The switch is total over the
     * classification permits with no default: a new permit is a compile-time decision here
     * (which form, or deliberately no row), never a silent skip at a renderer's
     * {@code instanceof} chain. A {@code null} form is the deliberate no-row verdict: scalars
     * register through their resolved constants, and an unclassified verdict never reaches
     * emission (the validator fails the run first).
     */
    public static List<TypeUnitCommand.SchemaShapeUnit> produce(GraphitronSchema schema,
                                                            String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var reach = schema.nestingReach();
        var rows = new ArrayList<TypeUnitCommand.SchemaShapeUnit>();
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
        return List.copyOf(rows);
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
}
