package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ArgPath;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.ServiceCallCarrier;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.DomainReturnType;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.UpdateRows;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.TargetShape;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.model.TenantScopes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * The per-field tenant-binding fold: assigns every classified {@link OutputField} its
 * {@link TenantBinding} arm, and every federation entity type its
 * {@link TenantBinding.EntityRepBound}, from the column mappings the classification already
 * carries. Computed once post-walk (an ancestor-context fact, like {@code ArrivalIndex});
 * threaded onto {@link GraphitronSchema#tenantBindings()} for the validator and the
 * tenant-routing emitters to read one fact.
 *
 * <p>{@link #EMPTY} for single-tenant builds ({@link TenantScopes.None}): the axis is absent,
 * not "everything {@link TenantBinding.Untenanted}".
 *
 * <p>{@link #rejections()} carries the typed {@code noTenantBinding} findings (a field or
 * dispatch surface reaching a tenant-scoped table with no binding in scope), the
 * {@code unroutedServiceCall} findings (a root service handed a connection whose arguments name
 * no tenant), and the tenancy markers' ladder and sweep rejections. The validator drains them
 * through its tenant mirror; nothing here demotes a classified verdict.
 */
public record TenantBindingIndex(
    Map<FieldCoordinates, TenantBinding> byCoordinate,
    Map<String, TenantBinding.EntityRepBound> byEntityType,
    List<ValidationError> rejections
) {

    /** The absent axis: single-tenant builds and test-constructed schemas. */
    public static final TenantBindingIndex EMPTY =
        new TenantBindingIndex(Map.of(), Map.of(), List.of());

    public TenantBindingIndex {
        byCoordinate = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byCoordinate));
        byEntityType = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byEntityType));
        rejections = List.copyOf(rejections);
    }

    /**
     * Computes the axis over the classified schema. Returns {@link #EMPTY} when no
     * {@code <tenantColumn>} is configured. The direct-binding surface is read off the minted
     * {@link OperationMemberRelation} rows (the coordinate's condition, lookup and write
     * members), so the fold sees the coordinate's whole operation set rather than one summary
     * arm's payload. A condition member's slots are read from {@code columnBindings}, the column
     * each filter slot binds whatever an authored {@code @condition} does to its predicate.
     *
     * <p>{@code reachableTypes} is the classification walk's domain,
     * {@link SchemaReachability#reachableTypeNames} under the classifier's node predicate: the
     * ancestor-context fold judges paths over the types that execute, so an unreached type's
     * fields say nothing about them.
     */
    public static TenantBindingIndex compute(
            GraphQLSchema sdl,
            Set<String> reachableTypes,
            Map<FieldCoordinates, GraphitronField> fields,
            Map<String, EntityResolution> entitiesByType,
            Map<String, GraphitronType> types,
            TenantScopes scopes,
            OperationMemberRelation operationMembers,
            ColumnBindingLedger columnBindings) {
        if (sdl == null) {
            return EMPTY;
        }
        if (!(scopes instanceof TenantScopes.Configured configured)) {
            // The axis is absent, but a tenancy marker must not be silently ignored: the author
            // asked for tenant routing in a build with no tenants to route over.
            var markerRejections = rejectMarkersWithoutTenancy(sdl);
            return markerRejections.isEmpty()
                ? EMPTY
                : new TenantBindingIndex(Map.of(), Map.of(), markerRejections);
        }
        return new Fold(sdl, reachableTypes, fields, entitiesByType, types, configured,
            operationMembers, columnBindings).run();
    }

    /**
     * A field-level tenancy marker, with the two texts its completeness sweep rejects with: one
     * for a single-tenant build, where the marker decides nothing, and one for a coordinate that
     * never reached the marker's ladder in {@link Fold#armOf}. Reaching the ladder is the
     * marker's verdict, since every ladder ends in an arm or a marker-specific rejection.
     */
    private record TenancyMarker(String directive,
                                 java.util.function.UnaryOperator<String> withoutTenancy,
                                 java.util.function.UnaryOperator<String> unreached) {}

    private static final List<TenancyMarker> TENANCY_MARKERS = List.of(
        new TenancyMarker(BuildContext.DIR_TENANT_FAN_OUT,
            coordinate -> "'" + coordinate + "' declares @tenantFanOut, but this build configures no"
                + " <tenantColumn>: there are no tenants to fan out over. Configure"
                + " database-per-tenant routing or remove the directive.",
            coordinate -> "'" + coordinate + "' declares @tenantFanOut, but the coordinate never"
                + " reached the fan-out classification: either the field failed"
                + " classification on its own (see its error), or its parent is a"
                + " class-, record-, or nesting-backed type, where the fanned"
                + " fetcher boundary is deferred in v1. Move the field to a root or"
                + " @table-backed parent, or remove the directive."),
        new TenancyMarker(BuildContext.DIR_GLOBAL_DATA,
            coordinate -> "'" + coordinate + "' declares @globalData, but this build configures no"
                + " <tenantColumn>: every field already runs on the one database. Remove the"
                + " directive.",
            coordinate -> "'" + coordinate + "' declares @globalData, but the coordinate never"
                + " reached the tenant-binding classification: either the field failed"
                + " classification on its own (see its error), or it is an interface field or a"
                + " field of a nesting type. @globalData is supported on root @service fields"
                + " only; remove the directive."));

    /**
     * Every tenancy marker application, as {@code (marker, "Type.field")}. Walks every
     * {@link graphql.schema.GraphQLFieldsContainer}, objects <em>and</em> interfaces: the markers
     * are legal on interface field definitions, graphql-java does not copy interface-field
     * directives onto implementors, and field classification only models object coordinates, so
     * an interface marker reaches a verdict through no other route.
     */
    private static void forEachMarkerApplication(GraphQLSchema sdl,
                                                 java.util.function.BiConsumer<TenancyMarker, String> action) {
        for (var type : sdl.getAllTypesAsList()) {
            if (type.getName().startsWith("__")
                    || !(type instanceof graphql.schema.GraphQLFieldsContainer container)) continue;
            for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                for (TenancyMarker marker : TENANCY_MARKERS) {
                    if (field.hasAppliedDirective(marker.directive())) {
                        action.accept(marker, container.getName() + "." + field.getName());
                    }
                }
            }
        }
    }

    private static ValidationError markerRejection(List<String> directives, String coordinate,
                                                   String reason) {
        return new ValidationError(coordinate, Rejection.directiveConflict(directives, reason),
            graphql.language.SourceLocation.EMPTY);
    }

    /** Every tenancy marker application in a single-tenant build is a validate-time error. */
    private static List<ValidationError> rejectMarkersWithoutTenancy(GraphQLSchema sdl) {
        var rejections = new ArrayList<ValidationError>();
        forEachMarkerApplication(sdl, (marker, coordinate) -> rejections.add(markerRejection(
            List.of(marker.directive()), coordinate, marker.withoutTenancy().apply(coordinate))));
        return rejections;
    }

    /**
     * The stateful fold over one schema: computes each field's direct binding from its own
     * carriers, then resolves the ancestor tenant context over the SDL's field edges, restricted
     * to the walk's domain. A type has a context when every path from a root reaches it through
     * an edge that establishes one; a cycle adds no path from a root, so it is judged by the
     * edges entering it from outside. The context and the two any-path ancestor facts are each
     * one forward closure over the same edges ({@link #closeForward}), computed once dispatch is
     * classified.
     */
    private static final class Fold {
        private final GraphQLSchema sdl;
        private final Map<FieldCoordinates, GraphitronField> fields;
        private final Map<String, EntityResolution> entitiesByType;
        private final Map<String, GraphitronType> types;
        private final TenantScopes.Configured scopes;
        private final OperationMemberRelation operationMembers;
        private final ColumnBindingLedger columnBindings;

        private final Set<String> roots = new HashSet<>();
        private final String mutationRootName;
        /** The walk's reachable types; edges whose parent lies outside it are not recorded. */
        private final Set<String> domain;
        /** target typename -> reaching in-domain field edges (parent typename + field name). */
        private final Map<String, List<FieldCoordinates>> reachingEdges = new HashMap<>();
        /** parent typename -> its in-domain field edges; the inverse of {@link #reachingEdges}. */
        private final Map<String, List<FieldCoordinates>> outgoingEdges = new HashMap<>();
        /** field edge -> the types it can materialize ({@link #structuralClosure} of its type). */
        private final Map<FieldCoordinates, Set<String>> edgeTargets = new HashMap<>();
        private final Map<String, Set<String>> closureCache = new HashMap<>();
        /** Types some path from a root reaches without crossing an establishing edge. */
        private Set<String> withoutContext;
        /** Types reached, along any path, below a tenant-divining edge. */
        private Set<String> belowBoundEdge;
        /** Types reached, along any path, below a {@code @tenantFanOut} edge. */
        private Set<String> belowFannedEdge;

        /** Node dispatch facts, computed once: type name -> decoded tenant position. */
        private final Map<String, Integer> nodePositions = new LinkedHashMap<>();
        private boolean nodeDispatchRoutable = true;

        private final Map<FieldCoordinates, TenantBinding> byCoordinate = new LinkedHashMap<>();
        private final Map<String, TenantBinding.EntityRepBound> byEntityType = new LinkedHashMap<>();
        private final List<ValidationError> rejections = new ArrayList<>();
        /** Per tenancy marker, the coordinates that reached its ladder: the sweep's verdict set. */
        private final Map<String, Set<String>> markerVerdicts = new HashMap<>();

        Fold(GraphQLSchema sdl,
             Set<String> domain,
             Map<FieldCoordinates, GraphitronField> fields,
             Map<String, EntityResolution> entitiesByType,
             Map<String, GraphitronType> types,
             TenantScopes.Configured scopes,
             OperationMemberRelation operationMembers,
             ColumnBindingLedger columnBindings) {
            this.sdl = sdl;
            this.domain = domain;
            this.fields = fields;
            this.entitiesByType = entitiesByType;
            this.types = types;
            this.scopes = scopes;
            if (operationMembers == OperationMemberRelation.EMPTY) {
                // The fold and the routing emitter must read one production. The emitter reads
                // GraphitronSchema.operationMembersOf, whose EMPTY sentinel falls back to the
                // leaf projection; rejecting the sentinel here keeps the two surfaces provably
                // the same walk-minted rows (hand-built schemas configure no tenant scopes and
                // never reach this fold).
                throw new IllegalArgumentException(
                    "tenant-binding fold requires the walk-minted operation member relation");
            }
            this.operationMembers = operationMembers;
            if (columnBindings == ColumnBindingLedger.EMPTY) {
                // Same ground as the member relation: a hand-built schema that never ran the walk
                // would otherwise read every condition path as binding nothing.
                throw new IllegalArgumentException(
                    "tenant-binding fold requires the walk-minted column-binding ledger");
            }
            this.columnBindings = columnBindings;
            this.mutationRootName = sdl.getMutationType() == null ? null : sdl.getMutationType().getName();
            recordRoot(sdl.getQueryType());
            recordRoot(sdl.getMutationType());
            recordRoot(sdl.getSubscriptionType());
            buildEdges();
        }

        private void recordRoot(GraphQLObjectType root) {
            if (root != null) roots.add(root.getName());
        }

        TenantBindingIndex run() {
            classifyNodeDispatch();
            classifyEntityDispatch();
            // The ancestor facts read the dispatch facts just filled, and armOf reads them.
            foldAncestorContexts();
            for (var entry : fields.entrySet()) {
                if (!(entry.getValue() instanceof OutputField out)) continue;
                FieldCoordinates coord = entry.getKey();
                TenantBinding arm = armOf(coord, out);
                if (arm != null) {
                    byCoordinate.put(coord, arm);
                }
            }
            sweepUnreachedMarkers();
            return new TenantBindingIndex(byCoordinate, byEntityType, rejections);
        }

        /**
         * Completeness backstop: every tenancy marker application must reach its ladder, which
         * ends in an arm or a marker-specific rejection. A marked coordinate the classification
         * never modelled as an {@link OutputField} (an interface field, a nesting type's member, a
         * projected leaf, an already-unclassified field) would otherwise be silently ignored; the
         * sweep turns it into a validate-time rejection.
         */
        private void sweepUnreachedMarkers() {
            forEachMarkerApplication(sdl, (marker, coordinate) -> {
                if (!markerVerdicts.getOrDefault(marker.directive(), Set.of()).contains(coordinate)) {
                    rejections.add(markerRejection(List.of(marker.directive()), coordinate,
                        marker.unreached().apply(coordinate)));
                }
            });
        }

        private void recordMarkerVerdict(String directive, String coordinate) {
            markerVerdicts.computeIfAbsent(directive, k -> new HashSet<>()).add(coordinate);
        }

        // ===== Per-field arm assignment =====

        private TenantBinding armOf(FieldCoordinates coord, OutputField out) {
            String coordinate = coord.getTypeName() + "." + coord.getFieldName();
            // A tenancy marker routes through its own ladder ahead of everything below, so a
            // marked field always gets a marker-specific verdict or rejection, never the generic
            // cross-scope or noTenantBinding message.
            boolean fanMarked = fanMarked(coord);
            boolean globalMarked = globalMarked(coord);
            if (fanMarked && globalMarked) {
                recordMarkerVerdict(BuildContext.DIR_TENANT_FAN_OUT, coordinate);
                recordMarkerVerdict(BuildContext.DIR_GLOBAL_DATA, coordinate);
                rejections.add(markerRejection(
                    List.of(BuildContext.DIR_GLOBAL_DATA, BuildContext.DIR_TENANT_FAN_OUT), coordinate,
                    "'" + coordinate + "' combines @globalData with @tenantFanOut: one says the"
                        + " field's data is global and runs on the default source, the other unions"
                        + " it across every tenant. Remove one of the directives."));
                return null;
            }
            if (fanMarked) {
                recordMarkerVerdict(BuildContext.DIR_TENANT_FAN_OUT, coordinate);
                return fanOutArmOf(coord, out);
            }
            if (globalMarked) {
                recordMarkerVerdict(BuildContext.DIR_GLOBAL_DATA, coordinate);
                return globalDataArmOf(coord, out);
            }
            // Every table the field's own SQL touches, not just a Record-shaped return target:
            // multi-table polymorphic fields hit their participant tables and pivot fields their
            // attribute table, so a Plain domain return must not read as "touches nothing".
            List<TableRef> reach = reachedTables(out);
            boolean anyTenant = reach.stream().anyMatch(this::tenantScoped);
            boolean anyGlobal = reach.stream().anyMatch(t -> !tenantScoped(t));
            if (anyTenant && anyGlobal) {
                // One statement cannot span the per-tenant and default sources; a binding would
                // not make this routable, so it rejects ahead of the ArgumentBound arm.
                rejections.add(new ValidationError(
                    coordinate,
                    Rejection.noTenantBinding(
                        coordinate,
                        reach.stream().filter(this::tenantScoped).findFirst().orElseThrow().tableName(),
                        "its SQL touches tenant-scoped and global tables in one statement ("
                            + reach.stream().map(TableRef::tableName).distinct()
                                .collect(java.util.stream.Collectors.joining(", "))
                            + "); database-per-tenant cannot serve a cross-scope read on one"
                            + " connection."),
                    graphql.language.SourceLocation.EMPTY));
                return null;
            }
            var members = operationMembers.membersOf(coord);
            var direct = directBinding(coord, members);
            // A root service handed a connection runs its own SQL on that connection, and that SQL
            // is opaque, so it needs a tenant whatever graphitron's own reach says. Not where the
            // reach holds a global table: graphitron re-reads that return on the same connection,
            // and global tables live on the default source, so the structure decides the field.
            boolean connectionService = roots.contains(coord.getTypeName())
                && out instanceof ServiceField && bindsConnection(out) && !anyGlobal;
            boolean needsTenant = anyTenant || connectionService;
            if (needsTenant && !direct.declines().isEmpty()) {
                // A declined shape names the tenant column but cannot route on it. Each decline
                // carries its own detail rather than falling through to the generic
                // "nothing names the tenant" text, which would send an author looking for a
                // binding they already wrote. Only where the statement needs a tenant at all:
                // a field whose own SQL stays on the default source has nothing to route, so
                // the shape that could not route it is moot.
                if (!anyTenant) {
                    // Nothing in reach is tenant-scoped, so NoTenantBinding's "reaches table"
                    // opening would be false; the service arm carries the declines instead.
                    rejections.add(new ValidationError(coordinate,
                        Rejection.unroutedServiceCall(coordinate, scopes.columnName(),
                            direct.declines(), direct.evidence()),
                        graphql.language.SourceLocation.EMPTY));
                    return null;
                }
                String tenantTable = reach.stream().filter(this::tenantScoped).findFirst()
                    .orElseThrow().tableName();
                for (String detail : direct.declines()) {
                    rejections.add(new ValidationError(
                        coordinate,
                        Rejection.noTenantBinding(coordinate, tenantTable, detail),
                        graphql.language.SourceLocation.EMPTY));
                }
                return null;
            }
            if (direct.divines()) {
                return new TenantBinding.ArgumentBound(direct.slots());
            }
            if (hasKind(members, OperationMember.Kind.NODE_RESOLVE)) {
                // Node dispatch spans types; the arm exists iff every tenant-scoped node
                // type's key embeds the tenant column (rejections fired once in
                // classifyNodeDispatch). No tenant-scoped node types at all means node
                // dispatch never leaves the default source.
                return nodePositions.isEmpty()
                    ? TenantBinding.Untenanted.INSTANCE
                    : TenantBinding.NodeIdBound.INSTANCE;
            }
            // A method call handed a connection-bound value (a DSLContext, or the $session handle
            // its connection's mount returned) runs on whichever connection it is handed, so which
            // connection serves it is semantics, not plumbing: under a tenant context the call runs
            // on the inherited tenant's connection. Decided ahead of the reach-derived Untenanted
            // arm, whose "touches no tables" reading is about graphitron's SQL only, a service's
            // own SQL being opaque. Not where the field's own reach is global: graphitron re-reads
            // that global return table on the same connection, and global tables live on the
            // default source, so the field keeps the Untenanted arm below.
            if (!anyGlobal && bindsConnection(out) && tenantContextOf(coord.getTypeName())) {
                return new TenantBinding.Inherited(coord.getTypeName());
            }
            if (!needsTenant) {
                return TenantBinding.Untenanted.INSTANCE;
            }
            if (tenantContextOf(coord.getTypeName())) {
                return new TenantBinding.Inherited(coord.getTypeName());
            }
            if (connectionService) {
                // The service fix, not the generic one: whatever the reach, what routes the call
                // is a tenant in its arguments, or the author's statement that its data is global.
                rejections.add(new ValidationError(coordinate,
                    Rejection.unroutedServiceCall(coordinate, scopes.columnName(), List.of(),
                        direct.evidence()),
                    graphql.language.SourceLocation.EMPTY));
                return null;
            }
            rejections.add(new ValidationError(
                coordinate,
                Rejection.noTenantBinding(
                    coordinate,
                    reach.stream().filter(this::tenantScoped).findFirst().orElseThrow().tableName(),
                    "no argument or input field maps to tenant column '"
                        + scopes.columnName() + "', and no ancestor established a tenant"
                        + " context."),
                graphql.language.SourceLocation.EMPTY));
            return null;
        }

        // ===== The @globalData arm =====

        /** Whether the coordinate's SDL field definition carries the {@code @globalData} marker. */
        private boolean globalMarked(FieldCoordinates coord) {
            GraphQLFieldDefinition def = fieldDefinition(coord);
            return def != null && def.hasAppliedDirective(BuildContext.DIR_GLOBAL_DATA);
        }

        /**
         * The {@code @globalData} rejection ladder, closed and validate-time: a marked field
         * either survives every rung and classifies {@link TenantBinding.Untenanted}, or rejects
         * with a marker-specific message; the first rung that applies wins. The marker is
         * accepted exactly where an unrouted connection-binding root service would otherwise
         * reject. The reach and argument rungs refuse it wherever the build can see tenant data,
         * so what it vouches for is only the service's own SQL, the one thing the build cannot
         * see. Computes reach itself, so a cross-scope field gets a rung's text rather than the
         * generic cross-scope rejection.
         */
        private TenantBinding globalDataArmOf(FieldCoordinates coord, OutputField out) {
            String coordinate = coord.getTypeName() + "." + coord.getFieldName();
            String declares = "'" + coordinate + "' declares @globalData, but ";
            if (!roots.contains(coord.getTypeName())) {
                return rejectGlobalData(coordinate, declares + "@globalData is supported on root"
                    + " fields only; a child service runs on its parent's tenant. Remove the"
                    + " directive.");
            }
            if (!(out instanceof ServiceField)) {
                return rejectGlobalData(coordinate, declares + "only a @service field's SQL is"
                    + " opaque to the build; graphitron decides this field's source from the tables"
                    + " it reads. Remove the directive.");
            }
            if (!bindsConnection(out)) {
                return rejectGlobalData(coordinate, declares + "the service is handed no"
                    + " connection, so there is nothing to route; remove the directive.");
            }
            List<TableRef> reach = reachedTables(out);
            String returned = GraphQLTypeUtil.unwrapAll(fieldDefinition(coord).getType()).getName();
            var tenantTable = reach.stream().filter(this::tenantScoped).findFirst();
            if (tenantTable.isPresent()) {
                return rejectGlobalData(coordinate, declares + "the field returns tenant-scoped"
                    + " @table type '" + returned + "' (table '" + tenantTable.get().tableName()
                    + "'); its rows cannot be re-read on the default source.");
            }
            if (!reach.isEmpty()) {
                return rejectGlobalData(coordinate, declares + "the field returns global @table"
                    + " type '" + returned + "', which already runs on the default source; the"
                    + " structure decides this field, remove the directive.");
            }
            var direct = directBinding(coord, operationMembers.membersOf(coord));
            if (direct.divines() || !direct.declines().isEmpty()) {
                String named = direct.slots().isEmpty()
                    ? "tenant column '" + scopes.columnName() + "', through a shape that cannot"
                        + " route the call"
                    : direct.slots().stream().map(TenantBinding.BoundSlot::slotName).distinct()
                        .collect(java.util.stream.Collectors.joining(", "));
                return rejectGlobalData(coordinate, declares + "the arguments name a tenant ("
                    + named + "), which contradicts @globalData. Remove the directive.");
            }
            if (!direct.evidence().isEmpty()) {
                return rejectGlobalData(coordinate, declares + "the arguments carry tenant-scoped"
                    + " values (" + String.join(", ", direct.evidence()) + "), so the service"
                    + " works on tenant data. Take the node table's jOOQ record with"
                    + " @nodeId(typeName:), or bind a jOOQ record field to '" + scopes.columnName()
                    + "', and remove the directive.");
            }
            return TenantBinding.Untenanted.INSTANCE;
        }

        private TenantBinding rejectGlobalData(String coordinate, String reason) {
            rejections.add(markerRejection(List.of(BuildContext.DIR_GLOBAL_DATA), coordinate, reason));
            return null;
        }

        // ===== The @tenantFanOut arm =====

        /** Whether the coordinate's SDL field definition carries the {@code @tenantFanOut} marker. */
        private boolean fanMarked(FieldCoordinates coord) {
            GraphQLFieldDefinition def = fieldDefinition(coord);
            return def != null && def.hasAppliedDirective(BuildContext.DIR_TENANT_FAN_OUT);
        }

        private GraphQLFieldDefinition fieldDefinition(FieldCoordinates coord) {
            return sdl.getType(coord.getTypeName()) instanceof graphql.schema.GraphQLFieldsContainer container
                ? container.getFieldDefinition(coord.getFieldName())
                : null;
        }

        /**
         * The {@code @tenantFanOut} rejection ladder, closed and validate-time: a marked field
         * either survives every rung and classifies {@link TenantBinding.FanOut}, or rejects with
         * a fan-out-specific message. The {@code @service} rung runs ahead of
         * the reach-derived rungs because a plain service return's reach is structurally empty and
         * would misreport as "nothing to fan out over".
         */
        private TenantBinding fanOutArmOf(FieldCoordinates coord, OutputField out) {
            String coordinate = coord.getTypeName() + "." + coord.getFieldName();
            if (coord.getTypeName().equals(mutationRootName)) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' is a mutation field: @tenantFanOut is a query-side union;"
                        + " a write fanned across every claimed tenant is not supported.");
            }
            GraphQLFieldDefinition fieldDef = fieldDefinition(coord);
            if (fieldDef.hasAppliedDirective(BuildContext.DIR_SERVICE)) {
                return rejectFanOut(coordinate,
                    List.of(BuildContext.DIR_TENANT_FAN_OUT, BuildContext.DIR_SERVICE),
                    "'" + coordinate + "' combines @tenantFanOut with @service: fan-out generates"
                        + " the field's SQL itself, and the service fan-out combination is"
                        + " deferred. Remove one of the directives.");
            }
            if (fieldDef.hasAppliedDirective(BuildContext.DIR_ROUTINE)) {
                return rejectFanOut(coordinate,
                    List.of(BuildContext.DIR_TENANT_FAN_OUT, BuildContext.DIR_ROUTINE),
                    "'" + coordinate + "' combines @tenantFanOut with @routine: fan-out generates"
                        + " the field's SQL itself, and a database routine's SQL is not"
                        + " graphitron's to run per tenant. Remove one of the directives.");
            }
            var members = operationMembers.membersOf(coord);
            if (hasKind(members, OperationMember.Kind.LOOKUP)) {
                return rejectFanOut(coordinate,
                    List.of(BuildContext.DIR_TENANT_FAN_OUT, BuildContext.DIR_LOOKUP_KEY),
                    "'" + coordinate + "' combines @tenantFanOut with @lookupKey: lookup enforces"
                        + " one row per input key, and fanning yields up to one row per tenant per"
                        + " key, silently breaking that invariant.");
            }
            // Connection-ness is a target-axis fact, deliberately not the paginate member: the
            // member is gated on a carried window payload, and a connection-shaped coordinate
            // without one (the batched polymorphic connection) must still reject on this rung.
            if (out.target().shape() instanceof TargetShape.Connection) {
                return rejectFanOut(coordinate,
                    List.of(BuildContext.DIR_TENANT_FAN_OUT, BuildContext.DIR_AS_CONNECTION),
                    "'" + coordinate + "' combines @tenantFanOut with @asConnection: pagination"
                        + " across a cross-tenant union is deferred; return a plain list.");
            }
            var unwrapped = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
            if (unwrapped instanceof GraphQLInterfaceType || unwrapped instanceof GraphQLUnionType) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' fans out an interface- or union-typed field: the"
                        + " polymorphic family is rejected in v1 (a cross-tenant union does not"
                        + " compose with multi-table dispatch staging). Fan out a concrete"
                        + " tenant-scoped object type.");
            }
            // The v1 emission surface: root fields, and children of table-backed parents (where
            // the marker forces the same fetcher boundary @splitQuery does). A class-, record-,
            // or nesting-backed parent's children resolve through emission arms the fanned
            // fetcher does not intercept, so the marker there must reject loudly rather than
            // be silently ignored.
            GraphitronType parentType = types.get(coord.getTypeName());
            if (!roots.contains(coord.getTypeName())
                    && !(parentType instanceof GraphitronType.TableType)
                    && !(parentType instanceof GraphitronType.NodeType)) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' fans out a field of a parent that is not a root or"
                        + " @table-backed type: v1 supports @tenantFanOut on root fields and on"
                        + " fields of @table parents; class-, record-, and nesting-backed parents"
                        + " are deferred.");
            }
            if (!(GraphQLTypeUtil.unwrapNonNull(fieldDef.getType()) instanceof graphql.schema.GraphQLList)) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' is not list-shaped: fan-out unions per-tenant results"
                        + " into one list, so the field must return a list of a tenant-scoped"
                        + " type.");
            }
            // Reach here is single-table by construction: the polymorphic rung above rejected
            // every participant-set shape and the non-list/pivot rungs the attribute shapes, so
            // a surviving field's reach is its Record return target alone and the generic
            // cross-scope (tenant-and-global-in-one-statement) case cannot arise.
            List<TableRef> reach = reachedTables(out);
            boolean anyTenant = reach.stream().anyMatch(this::tenantScoped);
            if (!anyTenant) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' reaches no tenant-scoped table: its data is global,"
                        + " so there is nothing to fan out over.");
            }
            var slots = directBinding(coord, members).slots();
            if (!slots.isEmpty()) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' already binds the tenant column through "
                        + slots.stream().map(TenantBinding.BoundSlot::slotName).distinct()
                            .collect(java.util.stream.Collectors.joining(", "))
                        + ": the tenant is already divined, and fanning would contradict it."
                        + " Remove the directive or the binding argument.");
            }
            if (anyFannedAncestor(coord.getTypeName())) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' sits below another @tenantFanOut field: each unioned row"
                        + " already carries its tenant, so a nested fan-out would double-fan an"
                        + " already fanned context.");
            }
            if (anyBoundAncestor(coord.getTypeName())) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' sits under a tenant-bound ancestor: the tenant is"
                        + " already divined and handed down on at least one reaching path, and"
                        + " fanning would contradict it.");
            }
            return TenantBinding.FanOut.INSTANCE;
        }

        /**
         * True when some path reaching {@code typeName} crosses a tenant-divining edge (a
         * direct binding, or routable node dispatch). The any-path mirror of
         * {@link #tenantContextOf}'s every-path fold, matching the nested-marker rung's posture:
         * a marked field rejects if fanning would contradict a divined tenant on <em>any</em>
         * path (rejections are conservative), while the {@link TenantBinding.Inherited} verdict
         * keeps demanding every-path certainty. A path through a cycle counts like any other.
         */
        private boolean anyBoundAncestor(String typeName) {
            return belowBoundEdge.contains(typeName);
        }

        /**
         * Whether the edge's own field divines a tenant: the direct-binding and routable
         * node-dispatch facts behind {@link #anyBoundAncestor}.
         */
        private boolean edgeDivinesTenant(FieldCoordinates edge) {
            if (fields.get(edge) instanceof OutputField) {
                var members = operationMembers.membersOf(edge);
                if (directBinding(edge, members).divines()) {
                    return true;
                }
                if (hasKind(members, OperationMember.Kind.NODE_RESOLVE)) {
                    return nodeDispatchRoutable && !nodePositions.isEmpty();
                }
            }
            return false;
        }

        private TenantBinding rejectFanOut(String coordinate, List<String> directives, String reason) {
            rejections.add(markerRejection(directives, coordinate, reason));
            return null;
        }

        /**
         * True when some path reaching {@code typeName} crosses a fanned field. The
         * fanned-ancestor fact behind the nested-{@code @tenantFanOut} rejection; any-path (a
         * rejection concern), unlike {@link #tenantContextOf}'s every-path fold. The children's
         * {@link TenantBinding.Inherited} classification reads the same marker through
         * {@link #edgeEstablishesContext}, so the two facts derive from one predicate
         * ({@link #fanMarked}) and cannot drift. A marked field on a cycle lies below itself, so
         * it reads as nested.
         */
        private boolean anyFannedAncestor(String typeName) {
            return belowFannedEdge.contains(typeName);
        }

        /**
         * Whether the field's method call is handed a connection-bound value, a {@code DSLContext}
         * or the {@code $session} handle. For a
         * {@link no.sikt.graphitron.rewrite.model.MethodBackedField}: a
         * {@link no.sikt.graphitron.rewrite.model.MethodRef.Service} whose
         * {@link no.sikt.graphitron.rewrite.model.MethodRef.CallShape#needsDsl()} holds (a
         * {@code DSLContext} method parameter, or a holder constructor taking one), any other
         * method ref with a {@link no.sikt.graphitron.rewrite.model.ParamSource.DslContext}
         * parameter, or a parameter sourced
         * {@link no.sikt.graphitron.rewrite.model.ParamSource.SessionHandle}. For a
         * {@link no.sikt.graphitron.rewrite.model.ServiceField}: a carrier entry of
         * {@link no.sikt.graphitron.rewrite.model.MappingEntry.FromDsl} or
         * {@link no.sikt.graphitron.rewrite.model.MappingEntry.FromSessionHandle}, across the
         * constructor and method rounds.
         */
        private static boolean bindsConnection(OutputField out) {
            if (out instanceof no.sikt.graphitron.rewrite.model.MethodBackedField mbf) {
                var method = mbf.method();
                boolean needsDsl = method instanceof no.sikt.graphitron.rewrite.model.MethodRef.Service svc
                    ? svc.callShape().needsDsl()
                    : method.params().stream().anyMatch(p ->
                        p.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.DslContext);
                return needsDsl || method.params().stream().anyMatch(p ->
                    p.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.SessionHandle);
            }
            if (out instanceof no.sikt.graphitron.rewrite.model.ServiceField sf) {
                var carrier = sf.serviceMethodCall();
                var entries = new java.util.ArrayList<no.sikt.graphitron.rewrite.model.MappingEntry>(
                    carrier.methodArgs());
                if (carrier instanceof no.sikt.graphitron.rewrite.model.ServiceMethodCall.Instance inst) {
                    entries.addAll(inst.ctorArgs());
                }
                return entries.stream().anyMatch(e ->
                    e instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromDsl
                        || e instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromSessionHandle);
            }
            return false;
        }

        /**
         * The tables the field's own SQL touches: the Record-shaped return target, a multi-table
         * polymorphic field's table-backed participants (including a joined participant's detail
         * table), or a pivot field's attribute table. Empty for fields that execute no SQL of
         * their own (scalars, node dispatch, plain service returns).
         */
        private static List<TableRef> reachedTables(OutputField out) {
            if (out.domainReturnType() instanceof DomainReturnType.Record r) {
                return List.of(r.table());
            }
            var tables = new ArrayList<TableRef>();
            switch (out) {
                case QueryField.QueryInterfaceField f -> addParticipantTables(f.participants(), tables);
                case QueryField.QueryUnionField f -> addParticipantTables(f.participants(), tables);
                case QueryField.QueryServicePolymorphicField f -> addParticipantTables(f.participants(), tables);
                case MutationField.MutationServicePolymorphicField f -> addParticipantTables(f.participants(), tables);
                case ChildField.InterfaceField f -> addParticipantTables(f.participants(), tables);
                case ChildField.UnionField f -> addParticipantTables(f.participants(), tables);
                case ChildField.BatchedInterfaceField f -> addParticipantTables(f.participants(), tables);
                case ChildField.BatchedUnionField f -> addParticipantTables(f.participants(), tables);
                case ChildField.PivotSpecField f -> tables.add(f.pivot().table());
                // A DML write whose return is an encoded id claims no Record return target, so
                // the early Record arm above misses it; the statement still runs against the
                // write target, and that is what decides whether it needs a tenant.
                case MutationField.DmlTableField f -> tables.add(f.write().table());
                default -> { }
            }
            return tables;
        }

        private static void addParticipantTables(List<ParticipantRef> participants, List<TableRef> out) {
            for (ParticipantRef participant : participants) {
                if (participant instanceof ParticipantRef.TableBacked tb) {
                    out.add(tb.table());
                }
            }
        }

        private boolean tenantScoped(TableRef table) {
            // Membership is decided by column presence, the same fact the catalog-load
            // classification keyed on, so the two views cannot disagree.
            return table.column(scopes.columnName()).isPresent();
        }

        private boolean matchesTenantColumn(ColumnRef column) {
            return scopes.columnName().equalsIgnoreCase(column.javaName())
                || scopes.columnName().equalsIgnoreCase(column.sqlName());
        }

        // ===== Direct bindings off the coordinate's operation member rows =====

        /**
         * One coordinate's direct-binding read: the slots that divine a tenant, and the shapes
         * that name the tenant column but cannot route on it.
         *
         * <p>A decline is not an absent binding. The author did name the tenant; the shape just
         * has no single value to acquire a connection from, so it gets its own rejection text
         * rather than the generic "nothing maps to the tenant column". A coordinate carrying one
         * also stops divining for its subtree, which is what {@link #divines()} states: it
         * rejects, so nothing is handed down and a child inheriting from it would inherit a value
         * that is never computed.
         *
         * <p>{@code evidence} names the argument values typed by a tenant-scoped table that bound
         * no slot (a {@code FilmRecord} bound only to {@code title}, a decode of a tenant-scoped
         * node type whose key misses the tenant column). It routes nothing; an unrouted
         * connection-binding service's rejection names it, and the {@code @globalData} ladder
         * refuses the marker over it.
         */
        private record DirectBinding(List<TenantBinding.BoundSlot> slots, List<String> declines,
                                     List<String> evidence) {

            static final DirectBinding NONE = new DirectBinding(List.of(), List.of(), List.of());

            /** Whether this coordinate establishes a tenant its subtree can inherit. */
            boolean divines() {
                return declines.isEmpty() && !slots.isEmpty();
            }
        }

        /**
         * The two axes one bound slot resolves to, or the reason its shape cannot route.
         * {@link Resolved} carries <em>where</em> the value is read and <em>what transform</em>
         * yields the tenant from it; nothing mints a slot from one axis alone.
         */
        private sealed interface SlotAccess {
            record Resolved(TenantBinding.SlotRead read, TenantBinding.SlotProjection projection)
                implements SlotAccess {}

            record Declined(String detail) implements SlotAccess {}
        }

        /** Accumulates one coordinate's slots, declines and tenant evidence, deduping slots by name. */
        private static final class SlotCollector {
            private final List<TenantBinding.BoundSlot> slots = new ArrayList<>();
            private final List<String> declines = new ArrayList<>();
            private final List<String> evidence = new ArrayList<>();
            private final Set<String> seenNames = new HashSet<>();

            void add(String slotName, ColumnRef column, SlotAccess access) {
                switch (access) {
                    case SlotAccess.Resolved r -> {
                        if (seenNames.add(slotName)) {
                            slots.add(new TenantBinding.BoundSlot(slotName, column, r.read(),
                                r.projection()));
                        }
                    }
                    case SlotAccess.Declined d -> decline(d.detail());
                }
            }

            void decline(String detail) {
                if (!declines.contains(detail)) {
                    declines.add(detail);
                }
            }

            void evidence(String name) {
                if (!evidence.contains(name)) {
                    evidence.add(name);
                }
            }

            DirectBinding result() {
                return slots.isEmpty() && declines.isEmpty() && evidence.isEmpty()
                    ? DirectBinding.NONE
                    : new DirectBinding(List.copyOf(slots), List.copyOf(declines), List.copyOf(evidence));
            }
        }

        /**
         * The tenant-divining slots across the coordinate's whole member set: every condition
         * member's column-bound filter slots, read from the column-binding ledger row at the
         * member's {@code (coordinate, table)} rather than from its emitted predicates, so an
         * authored {@code @condition} that suppresses a slot's predicate leaves its binding in
         * place (a polymorphic root carries one condition member per participant, so the
         * per-participant rows need no fallback), the lookup member's
         * key mapping, an INSERT / UPSERT write member's {@code @table} input, and the WHERE
         * surface of the two verbs that have one, and a root service call's argument-sourced
         * parameters. Deduped by slot name across members (the same
         * argument typically binds on every polymorphic participant, and one slot per name
         * suffices for the agreement fold).
         */
        private DirectBinding directBinding(FieldCoordinates coord, List<OperationMember> members) {
            var collector = new SlotCollector();
            for (OperationMember member : members) {
                switch (member) {
                    case OperationMember.Condition c ->
                        collectFromColumnBindings(columnBindings.slotsAt(coord, c.table()), collector);
                    case OperationMember.Lookup l -> collectFromLookup(l.lookupMapping(), collector);
                    case OperationMember.Write.Insert i -> collectFromTableInput(i.input(), collector);
                    case OperationMember.Write.Upsert u -> collectFromTableInput(u.input(), collector);
                    // The WHERE-keyed verbs (UPDATE, DELETE) share one body over
                    // Dml.whereKeyColumns(), so a third WHERE-bearing write arm is covered on
                    // arrival rather than falling silently to the no-op default below.
                    case OperationMember.Write.Dml dml -> collectFromWhereKeys(dml, collector);
                    case OperationMember.ServiceCall sc -> collectFromServiceCall(coord, sc, collector);
                    default -> { }
                }
            }
            // A @routine write contributes nothing here: its only member is the routine call,
            // which carries no filter, lookup or input surface, so it mints no slot at all.
            return collector.result();
        }

        private static boolean hasKind(List<OperationMember> members, OperationMember.Kind kind) {
            return members.stream().anyMatch(m -> m.kind() == kind);
        }

        /**
         * A condition member's column-bound slots. The tenant column's position in a slot's
         * column tuple is the slot of the decode record the slot's extraction produces, since the
         * ledger records the tuple the decode yields, so the projection is read off the same
         * index; an arity-1 slot reads index 0. A {@code @reference}-reached tenant column divines
         * the operation's tenant exactly as a local one does.
         */
        private void collectFromColumnBindings(List<ColumnBindingLedger.ColumnBoundSlot> slots,
                                               SlotCollector collector) {
            for (var slot : slots) {
                for (int i = 0; i < slot.columns().size(); i++) {
                    if (matchesTenantColumn(slot.columns().get(i))) {
                        collector.add(slot.slotName(), slot.columns().get(i), accessOf(slot.extraction(),
                            TenantBinding.SlotRead.TopLevelArg.INSTANCE, i));
                    }
                }
            }
        }

        /**
         * Both axes of one slot's runtime read, resolved from the carrier's
         * {@link CallSiteExtraction} at mint time so the routing emitters render them rather than
         * re-walking the carrier. The extraction states the location where it has one (a nested
         * input path, a context argument) and {@code fallbackRead} supplies it otherwise; the
         * leaf states the transform.
         *
         * @param decodeSlot the tenant column's 0-based position in the key tuple a node-id
         *     decode returns, ignored by every other leaf
         */
        private static SlotAccess accessOf(CallSiteExtraction extraction,
                                           TenantBinding.SlotRead fallbackRead, int decodeSlot) {
            TenantBinding.SlotRead read;
            CallSiteExtraction leaf;
            switch (extraction) {
                case CallSiteExtraction.NestedInputField nested -> {
                    read = new TenantBinding.SlotRead.NestedInput(nested.outerArgName(), nested.path());
                    leaf = nested.leaf();
                }
                case CallSiteExtraction.ContextArg ignored -> {
                    read = TenantBinding.SlotRead.ContextArg.INSTANCE;
                    leaf = extraction;
                }
                default -> {
                    read = fallbackRead;
                    leaf = extraction;
                }
            }
            return switch (leaf) {
                // A pruning leaf exists because the same wire id decodes differently per
                // polymorphic branch; there is no single decode to route the one connection on.
                case CallSiteExtraction.PruneOnMismatch ignored -> new SlotAccess.Declined(
                    "the tenant column is reached through a @nodeId argument of a multi-table"
                        + " polymorphic field, whose participants decode the same id as different"
                        + " node types: there is no single decode to route the statement on."
                        + " Bind the tenant with an argument mapping to the tenant column.");
                case CallSiteExtraction.NodeIdDecodeKeys nid -> new SlotAccess.Resolved(read,
                    new TenantBinding.SlotProjection.DecodedKeySlot(nid.decodeMethod(), decodeSlot));
                // Direct and the coercing leaves (JooqConvert, EnumValueOf, ...) read a wire
                // value that already is the tenant value, whose Java type the generated
                // divinedTenant guard checks against the tenant column type.
                default -> new SlotAccess.Resolved(read, TenantBinding.SlotProjection.Raw.INSTANCE);
            };
        }

        private void collectFromLookup(LookupMapping mapping, SlotCollector collector) {
            if (!(mapping instanceof LookupMapping.ColumnMapping cm)) {
                return;
            }
            for (var arg : cm.args()) {
                switch (arg) {
                    case LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg s -> {
                        if (matchesTenantColumn(s.targetColumn())) {
                            collector.add(s.argName(), s.targetColumn(), accessOf(s.extraction(),
                                TenantBinding.SlotRead.TopLevelArg.INSTANCE, 0));
                        }
                    }
                    case LookupMapping.ColumnMapping.LookupArg.MapInput mi -> {
                        for (InputColumnBinding.MapBinding b : mi.bindings()) {
                            if (matchesTenantColumn(b.targetColumn())) {
                                collector.add(b.fieldName(), b.targetColumn(), accessOf(b.extraction(),
                                    new TenantBinding.SlotRead.NestedInput(mi.argName(),
                                        List.of(b.fieldName())),
                                    b.decodeSlot()));
                            }
                        }
                    }
                    // Decoded node-id lookups carry per-id tenants (the per-row family), not a
                    // single argument value; they classify through the node dispatch facts,
                    // never as an ArgumentBound slot.
                    case LookupMapping.ColumnMapping.LookupArg.DecodedRecord ignored -> { }
                }
            }
        }

        /**
         * The WHERE surface of a DML statement. An UPDATE routes on its WHERE partition: routing
         * on a SET-side tenant column would send the statement to the destination tenant and
         * update a row that is not there, and under database-per-tenant a row cannot change tenant
         * by an UPDATE at all, since the destination row lives in another database. A SET-side
         * tenant column joins the agreement fold only where the walker has already forced it equal
         * to a WHERE column ({@link UpdateRows#isAgreementChecked}), so a value naming another
         * tenant is refused by the fold before any connection; every other SET-side tenant column
         * declines. The WHERE slots are added first so one of them is the binding's primary.
         */
        private void collectFromWhereKeys(OperationMember.Write.Dml dml, SlotCollector collector) {
            for (var key : dml.whereKeyColumns()) {
                if (!matchesTenantColumn(key.targetColumn())) continue;
                collector.add(key.sdlFieldName(), key.targetColumn(), accessOf(key.extraction(),
                    new TenantBinding.SlotRead.NestedInput(dml.outerArgName(),
                        List.of(key.sdlFieldName())),
                    key.decodeSlot()));
            }
            if (dml instanceof OperationMember.Write.Update update) {
                for (var set : update.updateRows().setColumns()) {
                    if (!matchesTenantColumn(set.targetColumn())) continue;
                    if (update.updateRows().isAgreementChecked(set)) {
                        collector.add(set.sdlFieldName(), set.targetColumn(), accessOf(set.extraction(),
                            new TenantBinding.SlotRead.NestedInput(dml.outerArgName(),
                                List.of(set.sdlFieldName())),
                            set.decodeSlot()));
                    } else {
                        collector.decline(
                            "input field '" + set.sdlFieldName() + "' writes tenant column '"
                                + scopes.columnName() + "' in the UPDATE's SET clause. Under"
                                + " database-per-tenant the destination row lives in another"
                                + " database, so no UPDATE can move a row between tenants; remove"
                                + " the field, or delete and re-insert the row.");
                    }
                }
            }
        }

        /**
         * A root {@code @service} call's tenant-bearing arguments: every argument-sourced
         * parameter's {@link ValueShape} tree, walked to the leaves that carry a column. A child
         * service carries a reflected method rather than the structured call and contributes
         * nothing: it gets its tenant from its parent ({@link #bindsConnection}).
         *
         * <p>A service whose own reach holds a global table (a {@code @table} return over one)
         * mints nothing, whatever its arguments name: graphitron re-reads that return table on
         * the connection the call is handed, and global tables live on the default source. The
         * gate sits here rather than in {@link #armOf} so {@link #edgeEstablishesContext}
         * reads the same answer, and the fields under such a service inherit no tenant that
         * nothing stamped.
         */
        private void collectFromServiceCall(FieldCoordinates coord, OperationMember.ServiceCall member,
                                            SlotCollector collector) {
            if (!(member.call() instanceof ServiceCallCarrier.StructuredCall structured)) {
                return;
            }
            if (fields.get(coord) instanceof OutputField out
                    && reachedTables(out).stream().anyMatch(t -> !tenantScoped(t))) {
                return;
            }
            // Constructor rounds carry no argument-sourced entry (the walker refuses one there).
            for (MappingEntry entry : structured.call().methodArgs()) {
                if (entry instanceof MappingEntry.FromArg arg) {
                    collectFromValueShape(arg.shape(), collector);
                }
            }
        }

        private void collectFromValueShape(ValueShape shape, SlotCollector collector) {
            switch (shape) {
                case ValueShape.ListOf list -> collectFromValueShape(list.elementShape(), collector);
                case ValueShape.RecordInput record -> {
                    for (var field : record.fields()) collectFromValueShape(field.shape(), collector);
                }
                case ValueShape.JavaBeanInput bean -> {
                    for (var field : bean.fields()) collectFromValueShape(field.shape(), collector);
                }
                case ValueShape.JooqRecordInput jr -> collectFromJooqRecord(jr, collector);
                case ValueShape.Scalar scalar ->
                    collectFromServiceLeaf(scalar.leafTransform(), pathOf(scalar.sdlPath()), collector);
            }
        }

        /**
         * A jOOQ record parameter: a column binding on the tenant column reads the tenant off the
         * wire as it is, and a {@code @nodeId} decode whose node key includes the tenant column
         * reads it off the decoded key at that position. Both paths are relative to the
         * record's own input, so they extend the parameter's argument path. A tenant-scoped
         * table's record that binds neither is tenant evidence.
         */
        private void collectFromJooqRecord(ValueShape.JooqRecordInput jr, SlotCollector collector) {
            List<String> base = pathOf(jr.sdlPath());
            boolean bound = false;
            for (var binding : jr.carrier().columnBindings()) {
                if (!matchesTenantColumn(binding.column())) continue;
                for (List<String> path : binding.paths()) {
                    var full = concat(base, path);
                    bound = true;
                    collector.add(slotNameOf(full), binding.column(), new SlotAccess.Resolved(
                        readOf(full), TenantBinding.SlotProjection.Raw.INSTANCE));
                }
            }
            for (var keyDecode : jr.carrier().keyDecodes()) {
                // Read off the node type's own key tuple rather than the record's target columns: a
                // reference decode lands the key on foreign-key columns that need not carry the
                // tenant column's name, and the tuple is what the decode helper projects from.
                var decode = nodeTypeByTypeId(keyDecode.typeId()).decodeMethod();
                int slot = tenantIndex(decode.outputColumnShape());
                if (slot < 0) continue;
                var full = concat(base, keyDecode.path());
                bound = true;
                collector.add(slotNameOf(full), decode.outputColumnShape().get(slot), new SlotAccess.Resolved(
                    readOf(full), new TenantBinding.SlotProjection.DecodedKeySlot(decode, slot)));
            }
            if (!bound && tenantScoped(jr.carrier().table())) {
                collector.evidence(recordNameOf(jr.carrier().table()) + " at " + slotNameOf(base));
            }
        }

        /**
         * One service leaf. A record decode of one node type and a key decode read the tenant off
         * the decoded key at the tenant column's position; a polymorphic record decode declines,
         * since its candidates decode the same id with their own key columns; every other leaf
         * carries no column and mints nothing. A decode of a tenant-scoped node type whose key
         * misses the tenant column is tenant evidence.
         */
        private void collectFromServiceLeaf(CallSiteExtraction leaf, List<String> path,
                                            SlotCollector collector) {
            switch (leaf) {
                case CallSiteExtraction.NodeIdDecodeRecord record -> {
                    var decode = nodeTypeByTypeId(record.typeId()).decodeMethod();
                    int slot = tenantIndex(decode.outputColumnShape());
                    if (slot < 0) {
                        if (tenantScoped(record.table())) {
                            collector.evidence(recordNameOf(record.table()) + " at " + slotNameOf(path));
                        }
                        return;
                    }
                    collector.add(slotNameOf(path), decode.outputColumnShape().get(slot), new SlotAccess.Resolved(
                        readOf(path), new TenantBinding.SlotProjection.DecodedKeySlot(decode, slot)));
                }
                case CallSiteExtraction.NodeIdDecodeKeys keys -> {
                    var shape = keys.decodeMethod().outputColumnShape();
                    int slot = tenantIndex(shape);
                    if (slot < 0) {
                        var node = nodeTypeByTypeId(keys.decodeMethod().typeId());
                        if (tenantScoped(node.table())) {
                            collector.evidence(node.name() + " id at " + slotNameOf(path));
                        }
                        return;
                    }
                    collector.add(slotNameOf(path), shape.get(slot), accessOf(keys, readOf(path), slot));
                }
                // The service-side twin of PruneOnMismatch. Minting nothing would leave the id
                // unchecked beside a divining sibling, and the service would be handed a record
                // from another tenant to act on in the wrong database. Only where some candidate
                // is tenant-scoped: an id every candidate of which is global names no tenant.
                case CallSiteExtraction.NodeIdDecodePolymorphicRecord poly -> {
                    if (poly.candidates().stream().anyMatch(c -> tenantScoped(c.table()))) {
                        collector.decline("the tenant is reached through a @nodeId slot naming '"
                            + poly.containerName() + "', whose members decode the same id as"
                            + " different node types, each with its own key columns: there is no"
                            + " single decode to route the service call on. Take a record of one"
                            + " node type, or bind the tenant through a field mapping to tenant"
                            + " column '" + scopes.columnName() + "'.");
                    }
                }
                default -> { }
            }
        }

        /** The simple name of a table's generated record class, as an author reads it in a parameter. */
        private static String recordNameOf(TableRef table) {
            String fq = table.recordClassName();
            return fq.substring(fq.lastIndexOf('.') + 1);
        }

        /** The tenant column's position in {@code columns}, or {@code -1}. */
        private int tenantIndex(List<ColumnRef> columns) {
            for (int i = 0; i < columns.size(); i++) {
                if (matchesTenantColumn(columns.get(i))) return i;
            }
            return -1;
        }

        /**
         * The node type a decode's type id names. {@code TypeBuilder} enforces type-id
         * uniqueness, so at most one matches; a decode naming none is a classifier bug.
         */
        private GraphitronType.NodeType nodeTypeByTypeId(String typeId) {
            for (GraphitronType type : types.values()) {
                if (type instanceof GraphitronType.NodeType nt && typeId.equals(nt.typeId())) {
                    return nt;
                }
            }
            throw new IllegalStateException("no node type carries type id '" + typeId + "'");
        }

        /** An argument path as its SDL names: the argument, then each input field below it. */
        private static List<String> pathOf(ArgPath path) {
            var names = new ArrayList<String>(path.deeperSegments().size() + 1);
            names.add(path.outerArgName());
            for (var segment : path.deeperSegments()) names.add(segment.name());
            return names;
        }

        private static List<String> concat(List<String> head, List<String> tail) {
            var out = new ArrayList<String>(head.size() + tail.size());
            out.addAll(head);
            out.addAll(tail);
            return out;
        }

        /**
         * A one-segment path is the argument itself; a longer one walks into its input. The
         * runtime walk maps a list-shaped level over its elements, so a batch input needs nothing
         * further here.
         */
        private static TenantBinding.SlotRead readOf(List<String> path) {
            return path.size() == 1
                ? TenantBinding.SlotRead.TopLevelArg.INSTANCE
                : new TenantBinding.SlotRead.NestedInput(path.get(0), path.subList(1, path.size()));
        }

        /**
         * The argument name for a top-level slot, which is what {@link TenantBinding.SlotRead.TopLevelArg}
         * reads by, and the dot-joined path for a nested one: the collector dedupes by name, so a
         * name stopping at the last segment would merge two {@code id} fields at different paths
         * and drop the second from the agreement fold.
         */
        private static String slotNameOf(List<String> path) {
            return String.join(".", path);
        }

        private void collectFromTableInput(ArgumentRef.InputTypeArg.TableInputArg input,
                                           SlotCollector collector) {
            for (InputColumnBindingGroup group : input.fieldBindings()) {
                if (!(group instanceof InputColumnBindingGroup.MapGroup mg)) continue;
                for (InputColumnBinding.MapBinding b : mg.bindings()) {
                    if (matchesTenantColumn(b.targetColumn())) {
                        collector.add(b.fieldName(), b.targetColumn(), accessOf(b.extraction(),
                            new TenantBinding.SlotRead.NestedInput(input.name(),
                                List.of(b.fieldName())),
                            b.decodeSlot()));
                    }
                }
            }
            // INSERT / UPSERT: fieldBindings is structurally empty (the VALUES emission walks
            // fields() directly), so the divining slots come from the same envelope: an input
            // field whose column mapping lands on the tenant column routes the mutation.
            collectFromInputFields(input.fields(), new ArrayDeque<>(), input.name(), collector);
        }

        private void collectFromInputFields(List<InputField> fields,
                                            ArrayDeque<String> path,
                                            String argName,
                                            SlotCollector collector) {
            for (InputField field : fields) {
                switch (field) {
                    case InputField.ColumnBackedField cf ->
                        collectFromCarrier(cf.name(), cf.columns(), cf.extraction(), path, argName,
                            collector);
                    case InputField.ColumnBackedReferenceField rf -> {
                        switch (rf.binding()) {
                            // The own-table tuple is positionally aligned with what the extraction
                            // produces, so the FK columns a decoded key lifts onto this table are
                            // read at the decode slot their position names.
                            case FilterBinding.Local local ->
                                collectFromCarrier(rf.name(), local.ownTableColumns(),
                                    rf.extraction(), path, argName, collector);
                            // Never reached: MutationInputResolver rejects a Remote-bound
                            // carrier on every @mutation before the write classifies, since the
                            // write has no own-table column to put the decoded key in.
                            case FilterBinding.Remote ignored -> { }
                        }
                    }
                    // A nested grouping input flattens onto the same table; descend with the
                    // grouping's key on the read path.
                    case InputField.NestingField nf -> {
                        path.addLast(nf.name());
                        collectFromInputFields(nf.fields(), path, argName, collector);
                        path.removeLast();
                    }
                    default -> { }
                }
            }
        }

        /**
         * One input-field carrier's contribution: the tenant column's position in the carrier's
         * column tuple is both the column it binds and the decode slot its extraction projects,
         * so an arity-1 {@code @nodeId} carrier and a composite one are the same line.
         */
        private void collectFromCarrier(String name, List<ColumnRef> columns,
                                        CallSiteExtraction extraction, ArrayDeque<String> path,
                                        String argName, SlotCollector collector) {
            for (int i = 0; i < columns.size(); i++) {
                if (!matchesTenantColumn(columns.get(i))) continue;
                var keys = new ArrayList<>(path);
                keys.add(name);
                collector.add(name, columns.get(i),
                    accessOf(extraction, new TenantBinding.SlotRead.NestedInput(argName, keys), i));
            }
        }

        // ===== Node dispatch =====

        private void classifyNodeDispatch() {
            for (GraphitronType type : types.values()) {
                if (!(type instanceof GraphitronType.NodeType nt)) continue;
                TableRef table = nt.table();
                if (!tenantScoped(table)) continue;
                List<ColumnRef> keyColumns = nt.nodeKeyColumns().isEmpty()
                    ? table.primaryKeyColumns()
                    : nt.nodeKeyColumns();
                int position = -1;
                for (int i = 0; i < keyColumns.size(); i++) {
                    if (matchesTenantColumn(keyColumns.get(i))) {
                        position = i;
                        break;
                    }
                }
                if (position < 0) {
                    nodeDispatchRoutable = false;
                    rejections.add(new ValidationError(
                        nt.name(),
                        Rejection.noTenantBinding(
                            nt.name(), table.tableName(),
                            "its node id key does not embed tenant column '"
                                + scopes.columnName() + "', so a decoded id cannot name the"
                                + " tenant to fetch from."),
                        graphql.language.SourceLocation.EMPTY));
                } else {
                    nodePositions.put(nt.name(), position);
                }
            }
        }

        // ===== Entity dispatch =====

        private void classifyEntityDispatch() {
            for (var entry : entitiesByType.entrySet()) {
                EntityResolution res = entry.getValue();
                if (!tenantScoped(res.table())) continue;
                var alternatives = new ArrayList<TenantBinding.EntityRepBound.AlternativeSlot>();
                boolean routable = true;
                var keyAlternatives = res.alternatives();
                for (int i = 0; i < keyAlternatives.size(); i++) {
                    var alt = keyAlternatives.get(i);
                    if (!alt.resolvable()) continue;
                    int position = -1;
                    List<ColumnRef> columns = alt.columns();
                    for (int p = 0; p < columns.size(); p++) {
                        if (matchesTenantColumn(columns.get(p))) {
                            position = p;
                            break;
                        }
                    }
                    if (position < 0) {
                        routable = false;
                        rejections.add(new ValidationError(
                            entry.getKey(),
                            Rejection.noTenantBinding(
                                entry.getKey(), res.table().tableName(),
                                "its federation key alternative #" + i + " does not carry"
                                    + " tenant column '" + scopes.columnName()
                                    + "', so a representation cannot name the tenant to"
                                    + " fetch from."),
                            graphql.language.SourceLocation.EMPTY));
                    } else {
                        alternatives.add(
                            new TenantBinding.EntityRepBound.AlternativeSlot(i, position));
                    }
                }
                if (routable && !alternatives.isEmpty()) {
                    byEntityType.put(entry.getKey(),
                        new TenantBinding.EntityRepBound(alternatives));
                }
            }
        }

        // ===== Ancestor tenant context =====

        private void buildEdges() {
            for (var type : sdl.getAllTypesAsList()) {
                if (type.getName().startsWith("__")) continue;
                if (!(type instanceof GraphQLObjectType obj)) continue;
                // An unreached type never executes, so its edges say nothing about the paths
                // that do; recording them would let it deny its targets a context.
                if (!domain.contains(obj.getName())) continue;
                for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
                    var target = GraphQLTypeUtil.unwrapAll(field.getType());
                    if (!(target instanceof GraphQLObjectType
                        || target instanceof GraphQLInterfaceType
                        || target instanceof GraphQLUnionType)) {
                        continue;
                    }
                    var edge = FieldCoordinates.coordinates(obj.getName(), field.getName());
                    var reached = structuralClosure(((GraphQLNamedType) target).getName());
                    edgeTargets.put(edge, reached);
                    outgoingEdges.computeIfAbsent(obj.getName(), k -> new ArrayList<>()).add(edge);
                    for (String r : reached) {
                        reachingEdges.computeIfAbsent(r, k -> new ArrayList<>()).add(edge);
                    }
                }
            }
        }

        /**
         * Computes the three ancestor facts as forward closures over the in-domain edges.
         * "No context" propagates from the operation roots, the dispatch-vetoed types, and the
         * types nothing hands a tenant, along every edge that does not establish one. That is
         * the greatest fixed point the every-path property wants: a cycle entered only through
         * establishing edges is never reached, while a veto or an unbound entry reaching any
         * member reaches the whole cycle through its internal edges. The any-path facts close
         * from the targets of their edges along every edge.
         */
        private void foldAncestorContexts() {
            var unbound = new HashSet<String>(roots);
            for (String typeName : domain) {
                if (dispatchVetoed(typeName)
                        || (!reachingEdges.containsKey(typeName) && !routableDispatchSurface(typeName))) {
                    unbound.add(typeName);
                }
            }
            withoutContext = closeForward(unbound, edge -> !edgeEstablishesContext(edge));
            belowBoundEdge = closeForward(targetsOfEdges(this::edgeDivinesTenant), edge -> true);
            belowFannedEdge = closeForward(targetsOfEdges(this::fanMarked), edge -> true);
        }

        /**
         * The types reachable from {@code seeds} along the in-domain edges {@code follow} accepts,
         * seeds included.
         */
        private Set<String> closeForward(Set<String> seeds,
                                         Predicate<FieldCoordinates> follow) {
            var closed = new HashSet<String>();
            var queue = new ArrayDeque<>(seeds);
            while (!queue.isEmpty()) {
                String typeName = queue.poll();
                if (!closed.add(typeName)) continue;
                for (FieldCoordinates edge : outgoingEdges.getOrDefault(typeName, List.of())) {
                    if (follow.test(edge)) {
                        queue.addAll(edgeTargets.get(edge));
                    }
                }
            }
            return closed;
        }

        private Set<String> targetsOfEdges(Predicate<FieldCoordinates> which) {
            var targets = new HashSet<String>();
            for (var entry : edgeTargets.entrySet()) {
                if (which.test(entry.getKey())) {
                    targets.addAll(entry.getValue());
                }
            }
            return targets;
        }

        /**
         * True when every path from a root to {@code typeName}, judged over the walk's domain,
         * runs through an edge that establishes a tenant context, so a tenant-scoped field on it
         * can inherit the divined value. Cycles add no path from a root. Conservative on every
         * uncovered shape: a root, a type outside the domain, a dispatch-vetoed type and
         * whatever it reaches, and a type nothing hands a tenant all answer {@code false}.
         */
        private boolean tenantContextOf(String typeName) {
            return domain.contains(typeName) && !withoutContext.contains(typeName);
        }

        /**
         * Batched dispatch surfaces reach the type outside the field-edge graph; each must itself
         * be routable for the type to have a context.
         */
        private boolean dispatchVetoed(String typeName) {
            if (types.get(typeName) instanceof GraphitronType.NodeType nt
                    && tenantScoped(nt.table())
                    && (!nodeDispatchRoutable || !nodePositions.containsKey(typeName))) {
                return true;
            }
            EntityResolution entity = entitiesByType.get(typeName);
            return entity != null && tenantScoped(entity.table())
                && !byEntityType.containsKey(typeName);
        }

        /**
         * A dispatch surface that hands its type a tenant: a node type whose key embeds the
         * tenant column, or an entity type with an {@link TenantBinding.EntityRepBound}.
         */
        private boolean routableDispatchSurface(String typeName) {
            return (types.get(typeName) instanceof GraphitronType.NodeType
                    && nodePositions.containsKey(typeName))
                || byEntityType.containsKey(typeName);
        }

        /**
         * Whether the edge itself hands its subtree a tenant; an edge that does not passes on
         * its parent's context, which {@link #foldAncestorContexts} propagates.
         */
        private boolean edgeEstablishesContext(FieldCoordinates edge) {
            if (fanMarked(edge)) {
                // A fanned field stamps each unioned row's tenant as per-element localContext, so
                // its subtree is tenant-homogeneous per element: the edge establishes context and
                // children below it classify Inherited (a @splitQuery child then partitions per
                // tenant through the loader-name seam, exactly as under an ArgumentBound root).
                return true;
            }
            if (fields.get(edge) instanceof OutputField) {
                var members = operationMembers.membersOf(edge);
                if (directBinding(edge, members).divines()) {
                    return true;
                }
                if (hasKind(members, OperationMember.Kind.NODE_RESOLVE)) {
                    return nodeDispatchRoutable;
                }
            }
            return false;
        }

        /**
         * The types a field of static type {@code startTypeName} can materialize: the type
         * itself plus, for an abstract type, its implementors / members (transitively).
         */
        private Set<String> structuralClosure(String startTypeName) {
            var cached = closureCache.get(startTypeName);
            if (cached != null) return cached;
            var closure = new HashSet<String>();
            var queue = new ArrayDeque<String>();
            queue.add(startTypeName);
            while (!queue.isEmpty()) {
                String name = queue.poll();
                if (!closure.add(name)) continue;
                switch (sdl.getType(name)) {
                    case GraphQLInterfaceType iface -> {
                        for (var impl : sdl.getImplementations(iface)) {
                            queue.add(impl.getName());
                        }
                    }
                    case GraphQLUnionType union -> {
                        for (var member : union.getTypes()) {
                            queue.add(member.getName());
                        }
                    }
                    case null, default -> { }
                }
            }
            closureCache.put(startTypeName, closure);
            return closure;
        }
    }
}
