package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.command.CallWrap;
import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.GlobalCommand;
import no.sikt.graphitron.command.GlobalUnitKind;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.Ordering;
import no.sikt.graphitron.command.ProjectionCommand;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.command.TenantStrategy;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The plan-sourced arm of the sourcing seam: projects the {@link CompileDependencyGraph} off the
 * {@link EmitPlan}'s relations. Nodes are the union of every relation's committed
 * {@link UnitRef}s; edges are read off the rows that already carry them (a launcher row's source
 * projection and WHERE glue, a projection row's callees and glue calls, a condition row's
 * decode/context facts, the fetcher edge relation's targets, the sealed
 * {@link GlobalCommand} arms' fixed-substrate wiring), never re-derived from the classified
 * model.
 *
 * <p><b>Edge policy.</b> Two edge classes, and {@link #project} returns them separably:
 *
 * <ul>
 *   <li><b>Precise edges</b> mirror references the emitted source actually makes: the relation
 *       rows' cross-unit refs and the arm-entailed wiring of the schema-dependent globals (the
 *       facade's schema-class and runtime reads, the node dispatch pair, the entity dispatch
 *       row's targets).</li>
 *   <li><b>Declared superset edges</b> over-approximate deliberately, and only where the target's
 *       ABI cannot move on a schema edit (so the extra edge never fires a recompile) or the
 *       target grows per type and the leaf grain is the honest bound: the frozen-scaffold
 *       blanket (every fetchers unit into each {@link #FROZEN_SCAFFOLD_KINDS} singleton and the
 *       {@code GraphitronContext} interface; every projection unit into the client exception and
 *       the selection-occurrence helpers), the wiring hub (the schema class into every fetchers
 *       and schema-shape unit; each schema shape into its own fetchers and {@code LightFetcher}),
 *       the connection-runtime substrate's internal wiring, the fetcher edge relation's declared
 *       families, and the leaf-derived {@code NodeIdEncoder} edges below.</li>
 * </ul>
 *
 * The three-leg oracle in the incremental harness prices both classes: every projected edge
 * endpoint must name an emitted unit, every emitted cross-unit reference must be in the graph,
 * and the graph's surplus over the emitted references must sit inside the declared superset, so
 * silent over-collection fails a test instead of accreting.
 *
 * <p><b>Leaf-derived {@code NodeIdEncoder} edges.</b> The one per-type-growing singleton is
 * reached from the model leaves whose emitted fetchers encode or decode node ids
 * ({@link ChildField.SingleRecordIdField}, {@link ChildField.SingleRecordIdFieldFromReturning},
 * the column-backed leaves with {@link CallSiteCompaction.NodeIdEncodeKeys} compaction, and the
 * DML mutation arms' encode/decode plumbing), not from rows: boundaries decode and encode, so
 * encode-ness is a call-site fact that stays at the leaf rather than smearing across a relation.
 * The DML arms' edge is leaf-grain (whether a particular write actually touches the encoder
 * depends on its input shape), so it rides the declared superset; the read-side encode leaves
 * are precise. The condition glue's decode edge, by contrast, is row data
 * ({@code ConditionCommand.decodesNodeId()}).
 *
 * <p>Two external call shapes contribute no edge, matching the emitted source: a service
 * {@code MethodRef} (the developer's own class) and a {@code SelectTerm.HelperCall} (the
 * {@code @externalField} helper class), both consumer code on the resolved classpath rather
 * than generated units.
 */
public final class PlanCompileGraph {

    private PlanCompileGraph() {}

    /**
     * The global kinds whose singletons are ABI-frozen runtime scaffolds: the same bytecode
     * surface is emitted for every schema, so a blanket edge from every fetchers unit into them
     * is a pruning-harmless superset (ABI gating never fires on a node whose ABI is stable).
     * {@code NODE_ID_ENCODER} is deliberately absent: it gains an encode/decode method pair per
     * {@code @node} type, so it is reached by the precise and leaf-derived edges instead of the
     * blanket.
     */
    public static final Set<GlobalUnitKind> FROZEN_SCAFFOLD_KINDS = Collections.unmodifiableSet(EnumSet.of(
        GlobalUnitKind.GRAPHITRON_VALUES,
        GlobalUnitKind.LIGHT_FETCHER,
        GlobalUnitKind.CONNECTION_RESULT,
        GlobalUnitKind.CONNECTION_HELPER,
        GlobalUnitKind.POLYMORPHIC_SELECTION_SET,
        GlobalUnitKind.SELECTION_OCCURRENCES,
        GlobalUnitKind.ORDER_BY_RESULT,
        GlobalUnitKind.OUTCOME,
        GlobalUnitKind.CONSTRAINT_VIOLATIONS,
        GlobalUnitKind.CLIENT_EXCEPTION,
        GlobalUnitKind.ERROR_ROUTER,
        GlobalUnitKind.ERROR_MAPPINGS));

    /**
     * A projected graph and the declared-superset subgraph it over-approximates through. The
     * production consumer reads only {@link #graph}; the bounded-gap oracle leg reads
     * {@link #declaredSuperset} as the data form of "which surplus is declared".
     */
    public record Projection(CompileDependencyGraph graph, CompileDependencyGraph declaredSuperset) {}

    /** The production entry point: the full graph for one run's plan. */
    public static CompileDependencyGraph fromPlan(EmitPlan plan, GraphitronSchema schema) {
        return project(plan, schema).graph();
    }

    /** The graph plus its declared-superset view, for the oracle's bounded-gap leg. */
    public static Projection project(EmitPlan plan, GraphitronSchema schema) {
        return new Builder(plan, schema).build();
    }

    /** The committed frozen-scaffold units of one plan, resolved by kind (never re-minted). */
    public static Set<UnitRef> frozenScaffoldUnits(EmitPlan plan) {
        var frozen = new LinkedHashSet<UnitRef>();
        for (GlobalCommand command : plan.globals()) {
            if (FROZEN_SCAFFOLD_KINDS.contains(command.kind())) {
                frozen.addAll(command.units());
            }
        }
        return frozen;
    }

    private static final class Builder {

        private final EmitPlan plan;
        private final GraphitronSchema schema;
        private final MapCompileDependencyGraph.Accumulator graph = new MapCompileDependencyGraph.Accumulator();
        private final MapCompileDependencyGraph.Accumulator superset = new MapCompileDependencyGraph.Accumulator();
        private final Map<GlobalUnitKind, GlobalCommand> byKind = new LinkedHashMap<>();
        private final Map<String, UnitRef> fetchersByTypeName = new LinkedHashMap<>();
        private final Map<String, UnitRef> fetchersBySimpleName = new LinkedHashMap<>();

        private Builder(EmitPlan plan, GraphitronSchema schema) {
            this.plan = plan;
            this.schema = schema;
            for (GlobalCommand command : plan.globals()) {
                byKind.put(command.kind(), command);
            }
            for (var row : plan.typeUnits().fetchers()) {
                fetchersByTypeName.put(row.typeName(), row.unit());
            }
            for (var row : plan.typeUnits().connectionFetchers()) {
                fetchersByTypeName.put(row.typeName(), row.connection());
            }
            for (UnitRef unit : plan.typeUnits().fetchersUnits()) {
                fetchersBySimpleName.put(unit.simpleName(), unit);
            }
        }

        private Projection build() {
            addNodes();
            addGlobalEdges();
            addConditionEdges();
            addProjectionEdges();
            addLauncherEdges();
            addFetcherEdgeRows();
            addEncoderLeafEdges();
            addBlanketEdges();
            return new Projection(graph.build(), superset.build());
        }

        /** An edge mirroring a reference the emitted source makes. */
        private void precise(UnitRef from, UnitRef to) {
            graph.addEdge(from, to);
        }

        /** A declared superset edge: in the graph, and in the bounded-gap leg's cover. */
        private void declared(UnitRef from, UnitRef to) {
            graph.addEdge(from, to);
            superset.addEdge(from, to);
        }

        /** The single committed unit of a kind, when the plan carries the kind's row. */
        private Optional<UnitRef> refOf(GlobalUnitKind kind) {
            var command = byKind.get(kind);
            return command == null ? Optional.empty() : Optional.of(command.units().get(0));
        }

        private UnitRef require(GlobalUnitKind kind) {
            return refOf(kind).orElseThrow(() -> new IllegalStateException(
                "the plan carries no " + kind + " row; the projection expected the unconditional kind"));
        }

        /** A connection-runtime substrate unit, addressed by simple name within the row's units. */
        private Optional<UnitRef> runtimeUnit(String simpleName) {
            var command = byKind.get(GlobalUnitKind.CONNECTION_RUNTIME);
            if (command == null) {
                return Optional.empty();
            }
            return command.units().stream().filter(u -> u.simpleName().equals(simpleName)).findFirst();
        }

        // --------------------------------------------------------------------------------------
        // Nodes: the union of every relation's committed refs.
        // --------------------------------------------------------------------------------------

        private void addNodes() {
            for (GlobalCommand command : plan.globals()) {
                command.units().forEach(graph::addNode);
            }
            plan.conditions().units().forEach(graph::addNode);
            plan.projections().units().forEach(graph::addNode);
            plan.typeUnits().inputRecordUnits().forEach(graph::addNode);
            plan.typeUnits().fetchersUnits().forEach(graph::addNode);
            plan.typeUnits().schemaShapeUnits().forEach(graph::addNode);
        }

        // --------------------------------------------------------------------------------------
        // Globals: a total switch over the sealed arms; fixed-substrate wiring is arm-entailed.
        // --------------------------------------------------------------------------------------

        private void addGlobalEdges() {
            for (GlobalCommand command : plan.globals()) {
                switch (command) {
                    case GlobalCommand.EntityDispatch dispatch -> {
                        for (UnitRef target : dispatch.dispatchTargets()) {
                            precise(dispatch.unit(), target);
                        }
                        precise(dispatch.unit(), require(GlobalUnitKind.NODE_ID_ENCODER));
                        precise(dispatch.unit(), require(GlobalUnitKind.GRAPHITRON_CONTEXT));
                    }
                    case GlobalCommand.Fixed fixed -> addFixedKindEdges(fixed);
                }
            }
        }

        /**
         * The fixed-substrate arms' wiring, total over the kinds with no default: a new kind is
         * a compile-time wiring decision here, never a silently edge-less node. The
         * self-contained scaffolds contribute no outbound edge (their emitted internals are
         * schema-independent, so they never enter a schema-driven delta; the oracle exempts them
         * as sources for the same reason). The connection-runtime substrate's internal wiring is
         * declared rather than precise because parts of it are configuration-conditional (the
         * session-hook bake, the tenant-typed provider reads) while every unit in it is
         * schema-invariant, so the over-approximation can never fire a recompile.
         */
        private void addFixedKindEdges(GlobalCommand.Fixed fixed) {
            switch (fixed.kind()) {
                case GRAPHITRON_VALUES, LIGHT_FETCHER, NODE_ID_ENCODER, CONNECTION_RESULT,
                     CONNECTION_HELPER, ONE_OF_DIRECTIVE_SDL, POLYMORPHIC_SELECTION_SET,
                     SELECTION_OCCURRENCES, ORDER_BY_RESULT, GRAPHITRON_CONTEXT,
                     CONSTRAINT_VIOLATIONS, CLIENT_EXCEPTION, ERROR_ROUTER, OUTCOME,
                     ERROR_MAPPINGS -> { }
                case ENTITY_FETCHER_DISPATCH -> throw new IllegalStateException(
                    "the entity dispatch family is the sealed relation's data-carrying arm;"
                        + " a fixed row of this kind is unconstructible");
                case CONNECTION_RUNTIME -> {
                    var pinned = runtimeUnit("PinnedConnection");
                    var runtime = runtimeUnit("GraphitronRuntime");
                    var tenantConnections = runtimeUnit("TenantConnections");
                    var hookImpl = runtimeUnit("GraphitronSessionHook");
                    pinned.ifPresent(p -> hookImpl.ifPresent(h -> declared(p, h)));
                    runtime.ifPresent(r -> {
                        pinned.ifPresent(p -> declared(r, p));
                        refOf(GlobalUnitKind.CONNECTION_INSTRUMENTATION).ifPresent(i -> declared(r, i));
                    });
                    tenantConnections.ifPresent(t -> {
                        runtime.ifPresent(r -> declared(t, r));
                        pinned.ifPresent(p -> declared(t, p));
                        refOf(GlobalUnitKind.TRANSACTION_PROVIDER).ifPresent(tp -> declared(t, tp));
                    });
                }
                case TRANSACTION_PROVIDER -> runtimeUnit("PinnedConnection")
                    .ifPresent(p -> declared(fixed.units().get(0), p));
                case CONNECTION_INSTRUMENTATION -> {
                    var instrumentation = fixed.units().get(0);
                    runtimeUnit("GraphitronRuntime").ifPresent(r -> declared(instrumentation, r));
                    runtimeUnit("PinnedConnection").ifPresent(p -> declared(instrumentation, p));
                    // Lazy acquisition on every path: the instrumentation publishes the
                    // per-operation carrier on both topologies.
                    runtimeUnit("TenantConnections").ifPresent(t -> declared(instrumentation, t));
                    refOf(GlobalUnitKind.TRANSACTION_PROVIDER).ifPresent(tp -> declared(instrumentation, tp));
                }
                case SCHEMA_CLASS -> {
                    var schemaClass = fixed.units().get(0);
                    refOf(GlobalUnitKind.QUERY_NODE_FETCHER).ifPresent(n -> precise(schemaClass, n));
                    refOf(GlobalUnitKind.ONE_OF_DIRECTIVE_SDL).ifPresent(o -> precise(schemaClass, o));
                }
                case QUERY_NODE_FETCHER -> {
                    var nodeFetcher = fixed.units().get(0);
                    precise(nodeFetcher, require(GlobalUnitKind.NODE_ID_ENCODER));
                    precise(nodeFetcher, require(GlobalUnitKind.GRAPHITRON_CONTEXT));
                    refOf(GlobalUnitKind.ENTITY_FETCHER_DISPATCH).ifPresent(d -> precise(nodeFetcher, d));
                }
                case FACADE -> {
                    var facade = fixed.units().get(0);
                    precise(facade, require(GlobalUnitKind.SCHEMA_CLASS));
                    precise(facade, require(GlobalUnitKind.GRAPHITRON_CONTEXT));
                    // No instrumentation edge: the owned factory writes only name-keyed
                    // contextArguments and the singleton, so the facade references the runtime
                    // (Graphitron.runtime(...)) but never the instrumentation class.
                    runtimeUnit("GraphitronRuntime").ifPresent(r -> precise(facade, r));
                }
                case DEV_EXECUTOR -> {
                    var devExecutor = fixed.units().get(0);
                    precise(devExecutor, require(GlobalUnitKind.FACADE));
                    runtimeUnit("GraphitronRuntime").ifPresent(r -> precise(devExecutor, r));
                    refOf(GlobalUnitKind.CONNECTION_INSTRUMENTATION).ifPresent(i -> precise(devExecutor, i));
                    refOf(GlobalUnitKind.TRANSACTION_PROVIDER).ifPresent(tp -> precise(devExecutor, tp));
                }
            }
        }

        // --------------------------------------------------------------------------------------
        // Conditions: the rows carry the decode/context facts.
        // --------------------------------------------------------------------------------------

        private void addConditionEdges() {
            for (var row : plan.conditions().rows()) {
                UnitRef glue = row.glue().owner();
                if (row.decodesNodeId()) {
                    precise(glue, require(GlobalUnitKind.NODE_ID_ENCODER));
                    precise(glue, require(GlobalUnitKind.CLIENT_EXCEPTION));
                }
                if (row.readsRequestContext()) {
                    precise(glue, require(GlobalUnitKind.GRAPHITRON_CONTEXT));
                }
            }
        }

        // --------------------------------------------------------------------------------------
        // Projections: callees and glue calls ride the contributions; no ancestry recovery.
        // --------------------------------------------------------------------------------------

        private void addProjectionEdges() {
            for (ProjectionCommand row : plan.projections().rows()) {
                UnitRef unit = row.unit();
                declared(unit, require(GlobalUnitKind.CLIENT_EXCEPTION));
                declared(unit, require(GlobalUnitKind.SELECTION_OCCURRENCES));
                for (Contribution contribution : row.contributions()) {
                    switch (contribution) {
                        case Contribution.Call call -> {
                            precise(unit, call.callee());
                            switch (call.wrap()) {
                                case CallWrap.Multiset multiset -> {
                                    if (multiset.filter() != null) {
                                        precise(unit, multiset.filter().method().owner());
                                    }
                                }
                                case CallWrap.LookupMultiset lookup -> {
                                    if (lookup.filter() != null) {
                                        precise(unit, lookup.filter().method().owner());
                                    }
                                }
                                case CallWrap.Splice ignored -> { }
                                case CallWrap.PivotMultiset ignored -> { }
                            }
                        }
                        // Project terms build from this unit's own table context; the one
                        // external arm (SelectTerm.HelperCall, the @externalField helper class)
                        // contributes no edge, like the service MethodRef.
                        case Contribution.Project ignored -> { }
                    }
                }
            }
        }

        // --------------------------------------------------------------------------------------
        // Launchers: the rows' source, WHERE, tenancy and result slots carry the refs.
        // --------------------------------------------------------------------------------------

        private void addLauncherEdges() {
            for (LauncherCommand row : plan.launchers().rows()) {
                UnitRef owner = row.unit().owner();
                addSourceEdges(owner, row.source());
                if (row.where() != null) {
                    precise(owner, row.where().method().owner());
                }
                if (row.tenancy() instanceof TenantStrategy.Fanned fanned) {
                    precise(owner, fanned.carrier());
                }
                switch (row.result()) {
                    case ResultShape.SingleRecord ignored -> { }
                    case ResultShape.LoaderDelegated ignored -> { }
                    case ResultShape.RecordList list -> addOrderingEdges(owner, list.ordering());
                    case ResultShape.Connection connection -> {
                        addOrderingEdges(owner, connection.ordering());
                        precise(owner, connection.helper());
                        precise(owner, connection.carrier());
                        if (connection.facets() != null) {
                            precise(owner, connection.facets().base().method().owner());
                            connection.facets().facets().forEach(entry ->
                                precise(owner, entry.condition().method().owner()));
                        }
                    }
                }
            }
        }

        /**
         * The source arms' projection refs, total over {@link LaunchSource}. The service
         * {@code MethodRef} is external (the developer's class, already compiled), so the pure
         * delegation arm contributes no edge.
         */
        private void addSourceEdges(UnitRef owner, LaunchSource source) {
            switch (source) {
                case LaunchSource.AnchorTable anchor -> precise(owner, anchor.projection());
                case LaunchSource.RoutineChain routine -> precise(owner, routine.projection());
                case LaunchSource.CorrelatedChain chain -> precise(owner, chain.projection());
                case LaunchSource.CorrelatedLookupChain lookup -> {
                    precise(owner, lookup.projection());
                    precise(owner, lookup.inputRows().owner());
                }
                case LaunchSource.ServiceCall ignored -> { }
                case LaunchSource.ServiceTableLift lift -> precise(owner, lift.projection());
                case LaunchSource.PivotAggregate pivot -> precise(owner, pivot.projection());
                case LaunchSource.KeyedLookup keyed -> {
                    precise(owner, keyed.projection());
                    precise(owner, keyed.inputRows().owner());
                }
                case LaunchSource.DiscriminatedCorrelatedChain chain ->
                    addDiscriminatedEdges(owner, chain.discriminated());
                case LaunchSource.DiscriminatedTable discriminated ->
                    addDiscriminatedEdges(owner, discriminated);
                case LaunchSource.ProjectedReentry reentry -> precise(owner, reentry.projection());
                case LaunchSource.DiscriminatedReentry reentry ->
                    addDiscriminatedEdges(owner, reentry.discriminated());
            }
        }

        private void addDiscriminatedEdges(UnitRef owner, LaunchSource.DiscriminatedTable discriminated) {
            for (var branch : discriminated.branches()) {
                switch (branch) {
                    case LaunchSource.DiscriminatedTable.Branch.SingleTable single ->
                        precise(owner, single.projection());
                    // A joined-table participant contributes detail columns, not a projection call.
                    case LaunchSource.DiscriminatedTable.Branch.JoinedDetail ignored -> { }
                }
            }
        }

        private void addOrderingEdges(UnitRef owner, Ordering ordering) {
            switch (ordering) {
                case null -> { }
                case Ordering.Columns ignored -> { }
                case Ordering.Helper helper -> {
                    // The helper method lives on the owner itself (a dropped self-edge); the
                    // OrderByResult carrier is the cross-unit read.
                    precise(owner, helper.method().owner());
                    precise(owner, helper.resultType());
                }
            }
        }

        // --------------------------------------------------------------------------------------
        // The fetcher edge relation: declared families, targets ride the rows.
        // --------------------------------------------------------------------------------------

        private void addFetcherEdgeRows() {
            for (var row : plan.fetcherEdges().rows()) {
                for (UnitRef target : row.targets()) {
                    declared(row.owner(), target);
                }
            }
        }

        // --------------------------------------------------------------------------------------
        // Leaf-derived NodeIdEncoder edges (see the class javadoc's edge policy).
        // --------------------------------------------------------------------------------------

        private void addEncoderLeafEdges() {
            for (var type : schema.types().values()) {
                for (var field : schema.fieldsOf(type.name())) {
                    addEncoderLeafEdges(field);
                }
            }
        }

        private void addEncoderLeafEdges(GraphitronField field) {
            if (field instanceof ChildField.NestingField nesting) {
                for (var nested : nesting.nestedFields()) {
                    addEncoderLeafEdges(nested);
                }
            }
            switch (field) {
                // Read-side encodes: the bound column fetcher encodes, precisely.
                case ChildField.SingleRecordIdField f -> encoderEdge(f.parentTypeName(), false);
                case ChildField.SingleRecordIdFieldFromReturning f -> encoderEdge(f.parentTypeName(), false);
                case ChildField.ColumnBackedField f -> {
                    if (f.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                        encoderEdge(f.parentTypeName(), false);
                    }
                }
                case ChildField.ColumnBackedReferenceField f -> {
                    if (f.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys) {
                        encoderEdge(f.parentTypeName(), false);
                    }
                }
                // Write-side plumbing: whether one write actually encodes its RETURNING keys or
                // decodes a @nodeId input depends on the return arm and the input shape, so the
                // leaf-grain edge is a declared superset.
                case MutationField.DmlTableField f -> encoderEdge(f.parentTypeName(), true);
                case MutationField.MutationDmlRecordField f -> encoderEdge(f.parentTypeName(), true);
                case MutationField.MutationBulkDmlRecordField f -> encoderEdge(f.parentTypeName(), true);
                default -> { }
            }
        }

        private void encoderEdge(String parentTypeName, boolean declaredSuperset) {
            UnitRef fetchers = fetchersByTypeName.get(parentTypeName);
            if (fetchers == null) {
                return;
            }
            UnitRef encoder = require(GlobalUnitKind.NODE_ID_ENCODER);
            if (declaredSuperset) {
                declared(fetchers, encoder);
            } else {
                precise(fetchers, encoder);
            }
        }

        // --------------------------------------------------------------------------------------
        // The blanket: the declared over-approximation, expressed over the plan's rows.
        // --------------------------------------------------------------------------------------

        private void addBlanketEdges() {
            UnitRef schemaClass = require(GlobalUnitKind.SCHEMA_CLASS);
            UnitRef context = require(GlobalUnitKind.GRAPHITRON_CONTEXT);
            UnitRef lightFetcher = require(GlobalUnitKind.LIGHT_FETCHER);
            Set<UnitRef> frozen = frozenScaffoldUnits(plan);
            for (UnitRef fetchers : plan.typeUnits().fetchersUnits()) {
                for (UnitRef scaffold : frozen) {
                    declared(fetchers, scaffold);
                }
                declared(fetchers, context);
                declared(schemaClass, fetchers);
            }
            for (var row : plan.typeUnits().schemaShapes()) {
                declared(schemaClass, row.unit());
                declared(row.unit(), lightFetcher);
                if (row.registersFetchers()) {
                    UnitRef own = fetchersBySimpleName.get(row.typeName() + GeneratedUnits.FETCHERS_SUFFIX);
                    if (own != null) {
                        declared(row.unit(), own);
                    }
                }
            }
        }
    }
}
