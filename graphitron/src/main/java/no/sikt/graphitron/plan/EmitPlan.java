package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.GlobalCommand;
import no.sikt.graphitron.command.GlobalUnitKind;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.session.SessionHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * The emit decisions of one generation run, produced eagerly by the core before any rendering.
 * Commands are a derived artifact of this step, never stored on {@link GraphitronSchema}: the
 * fact store carries what the schema means, the plan carries what this run emits.
 *
 * <p>The plan holds the global command relation (one {@link GlobalCommand} row per global emit
 * family, keyed by {@link GlobalUnitKind}, each naming the exact units it commits), the
 * condition command relation ({@link ConditionRelation}: one row per covered
 * {@code (coordinate, resolvedTable)} key, with the committed subset this run renders glue for),
 * the projection command relation ({@link ProjectionRelation}: one row per projection unit,
 * produced after conditions because projection rows reference glue by condition row), and the
 * launcher command relation ({@link LauncherRelation}: one row per migrated root SELECT
 * coordinate, produced after conditions for the same reason, its WHERE slot is a glue ref), the
 * fetcher edge relation ({@link FetcherEdgeRelation}: one row per covered non-launcher
 * coordinate whose emitted fetcher methods reference other generated units, produced after
 * conditions because a polymorphic root's glue targets are derived from the condition rows),
 * and the type-keyed command relation ({@link TypeUnitRelation}: one row per per-type unit, the
 * generator families' membership loops replaced kind by kind).
 * The shell folds over the rows and renders; membership decisions that used to sit in the shell
 * (the federation {@code @oneOf} gate) or inside a generator's early return (entity dispatch on a
 * schema without entities, the node fetcher on a schema without node types, the dev executor on a
 * federated schema) are all made here. The schema-level inputs arrive as facts landed once by the
 * builder ({@code Bundle.federationLink()}, {@code Bundle.usesOneOf()}), not re-derived.
 */
public record EmitPlan(List<GlobalCommand> globals, ConditionRelation conditions,
                       ProjectionRelation projections, LauncherRelation launchers,
                       FetcherEdgeRelation fetcherEdges, TypeUnitRelation typeUnits) {

    public EmitPlan {
        globals = List.copyOf(globals);
        long distinctKinds = globals.stream().map(GlobalCommand::kind).distinct().count();
        if (distinctKinds != globals.size()) {
            throw new IllegalArgumentException("the global command relation is keyed by unit kind; a kind appeared twice");
        }
        if (conditions == null) {
            throw new IllegalArgumentException("the plan carries the condition relation; an empty relation is a value, not null");
        }
        if (projections == null) {
            throw new IllegalArgumentException("the plan carries the projection relation; an empty relation is a value, not null");
        }
        if (launchers == null) {
            throw new IllegalArgumentException("the plan carries the launcher relation; an empty relation is a value, not null");
        }
        if (fetcherEdges == null) {
            throw new IllegalArgumentException("the plan carries the fetcher edge relation; an empty relation is a value, not null");
        }
        if (typeUnits == null) {
            throw new IllegalArgumentException("the plan carries the type-unit relation; an empty relation is a value, not null");
        }
    }

    /**
     * Produces the plan for one run. {@code federationLink} and {@code usesOneOf} are the
     * bundle's schema-level facts; the schema's resolved session-hook carrier
     * ({@link GraphitronSchema#sessionHooks()}) decides the connection runtime's hook unit;
     * {@code outputPackage} anchors every unit name.
     */
    public static EmitPlan produce(GraphitronSchema schema,
                                   boolean federationLink,
                                   boolean usesOneOf,
                                   String outputPackage) {
        var units = new GeneratedUnits(outputPackage);
        var globals = new ArrayList<GlobalCommand>();

        globals.add(one(GlobalUnitKind.GRAPHITRON_VALUES, units.singleton(GeneratedUnits.SUB_UTIL, "GraphitronValues")));
        globals.add(one(GlobalUnitKind.LIGHT_FETCHER, units.singleton(GeneratedUnits.SUB_UTIL, "LightFetcher")));
        globals.add(one(GlobalUnitKind.NODE_ID_ENCODER, units.singleton(GeneratedUnits.SUB_UTIL, "NodeIdEncoder")));
        if (!schema.entitiesByType().isEmpty()) {
            // The dispatch row carries its schema-dependent outbound refs: one per-type
            // projection class per resolvable entity (node types included through the
            // @node-to-@key synthesis), sorted for a deterministic plan.
            globals.add(new GlobalCommand.EntityDispatch(
                units.singleton(GeneratedUnits.SUB_UTIL, "EntityFetcherDispatch"),
                schema.entitiesByType().keySet().stream().sorted()
                    .map(units::typeClass)
                    .toList()));
        }
        globals.add(one(GlobalUnitKind.CONNECTION_RESULT, units.connectionResult()));
        globals.add(one(GlobalUnitKind.CONNECTION_HELPER, units.connectionHelper()));
        // The runtime _Service.sdl helper serves only the federation build arm (the wrapped
        // `return` in GraphitronSchemaClassGenerator's two-arg build, itself inside `if
        // (federationLink)`). A non-federation schema that uses @oneOf has no _Service.sdl to
        // correct (its file arm prints the definition through SchemaPrinter already), so gating on
        // usesOneOf alone would commit a dead, uncalled helper into a non-federation consumer's util.
        if (federationLink && usesOneOf) {
            globals.add(one(GlobalUnitKind.ONE_OF_DIRECTIVE_SDL,
                units.singleton(GeneratedUnits.SUB_UTIL, "OneOfDirectiveSdl")));
        }
        globals.add(one(GlobalUnitKind.POLYMORPHIC_SELECTION_SET,
            units.singleton(GeneratedUnits.SUB_UTIL, "PolymorphicSelectionSet")));
        globals.add(one(GlobalUnitKind.SELECTION_OCCURRENCES,
            units.singleton(GeneratedUnits.SUB_UTIL, "SelectionOccurrences")));
        globals.add(one(GlobalUnitKind.ORDER_BY_RESULT, units.orderByResult()));
        globals.add(one(GlobalUnitKind.GRAPHITRON_CONTEXT, units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronContext")));
        globals.add(connectionRuntime(units, schema.sessionHooks()));
        globals.add(one(GlobalUnitKind.TRANSACTION_PROVIDER,
            units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronTransactionProvider")));
        globals.add(one(GlobalUnitKind.CONNECTION_INSTRUMENTATION,
            units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronConnectionInstrumentation")));
        globals.add(one(GlobalUnitKind.CONSTRAINT_VIOLATIONS,
            units.singleton(GeneratedUnits.SUB_SCHEMA, "ConstraintViolations")));
        globals.add(one(GlobalUnitKind.CLIENT_EXCEPTION,
            units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronClientException")));
        globals.add(one(GlobalUnitKind.ERROR_ROUTER, units.singleton(GeneratedUnits.SUB_SCHEMA, "ErrorRouter")));
        globals.add(one(GlobalUnitKind.OUTCOME, units.singleton(GeneratedUnits.SUB_SCHEMA, "Outcome")));
        globals.add(one(GlobalUnitKind.ERROR_MAPPINGS, units.singleton(GeneratedUnits.SUB_SCHEMA, "ErrorMappings")));
        globals.add(one(GlobalUnitKind.SCHEMA_CLASS, units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronSchema")));
        if (schema.types().values().stream().anyMatch(t -> t instanceof GraphitronType.NodeType)) {
            globals.add(one(GlobalUnitKind.QUERY_NODE_FETCHER, units.queryNodeFetcher()));
        }
        globals.add(one(GlobalUnitKind.FACADE, units.rootUnit("Graphitron")));
        if (!federationLink) {
            globals.add(one(GlobalUnitKind.DEV_EXECUTOR, units.rootUnit("GraphitronDevExecutor")));
        }
        var conditions = ConditionCommands.produce(schema, outputPackage);
        return new EmitPlan(globals, conditions,
            ProjectionCommands.produce(schema, conditions, outputPackage),
            LauncherCommands.produce(schema, conditions, outputPackage),
            FetcherEdgeCommands.produce(schema, conditions, outputPackage),
            TypeUnitCommands.produce(schema, outputPackage));
    }

    /** A fixed-substrate global command committing exactly one unit. */
    private static GlobalCommand one(GlobalUnitKind kind, UnitRef unit) {
        return new GlobalCommand.Fixed(kind, List.of(unit));
    }

    /**
     * The connection runtime's unit set: the three fixed units, plus the generated hook class
     * exactly when the resolved session-hook carrier emits one
     * ({@link SessionHooks#emitsHookImplementation()}, the same fact the generator gates on).
     * The {@link SessionHooks.NotConfigured} arm plans no hook unit at all: nothing is emitted
     * and nothing is held.
     */
    private static GlobalCommand connectionRuntime(GeneratedUnits units, SessionHooks sessionHooks) {
        var refs = new ArrayList<UnitRef>();
        refs.add(units.singleton(GeneratedUnits.SUB_SCHEMA, "PinnedConnection"));
        refs.add(units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronRuntime"));
        refs.add(units.tenantConnections());
        if (sessionHooks.emitsHookImplementation()) {
            refs.add(units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronSessionHook"));
        }
        return new GlobalCommand.Fixed(GlobalUnitKind.CONNECTION_RUNTIME, refs);
    }
}
