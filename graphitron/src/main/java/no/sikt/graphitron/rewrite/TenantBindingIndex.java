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
 * <p>{@link #rejections()} carries the typed {@code noTenantBinding} findings: a field or
 * dispatch surface reaching a tenant-scoped table with no binding in scope. The validator
 * drains them through its tenant mirror; nothing here demotes a classified verdict.
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
     */
    public static TenantBindingIndex compute(
            GraphQLSchema sdl,
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
            // The axis is absent, but a @tenantFanOut marker must not be silently ignored: the
            // author asked for a per-tenant union in a build with no tenants to fan over.
            var markerRejections = rejectMarkersWithoutTenancy(sdl);
            return markerRejections.isEmpty()
                ? EMPTY
                : new TenantBindingIndex(Map.of(), Map.of(), markerRejections);
        }
        return new Fold(sdl, fields, entitiesByType, types, configured, operationMembers,
            columnBindings).run();
    }

    /**
     * Every {@code @tenantFanOut} application in a single-tenant build is a validate-time error.
     * Walks every {@link graphql.schema.GraphQLFieldsContainer} (objects <em>and</em> interfaces:
     * the directive is legal on interface field definitions, and graphql-java does not copy
     * interface-field directives onto implementors), mirroring
     * {@link Fold#sweepUnreachedFanOutMarkers}.
     */
    private static List<ValidationError> rejectMarkersWithoutTenancy(GraphQLSchema sdl) {
        var rejections = new ArrayList<ValidationError>();
        for (var type : sdl.getAllTypesAsList()) {
            if (type.getName().startsWith("__")
                    || !(type instanceof graphql.schema.GraphQLFieldsContainer container)) continue;
            for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                if (!field.hasAppliedDirective(BuildContext.DIR_TENANT_FAN_OUT)) continue;
                String coordinate = container.getName() + "." + field.getName();
                rejections.add(new ValidationError(
                    coordinate,
                    Rejection.directiveConflict(List.of(BuildContext.DIR_TENANT_FAN_OUT),
                        "'" + coordinate + "' declares @tenantFanOut, but this build configures no"
                            + " <tenantColumn>: there are no tenants to fan out over. Configure"
                            + " database-per-tenant routing or remove the directive."),
                    graphql.language.SourceLocation.EMPTY));
            }
        }
        return rejections;
    }

    /**
     * The stateful fold over one schema: computes each field's direct binding from its own
     * carriers, then resolves the ancestor tenant context with memoisation over the SDL's
     * reaching edges (mirroring {@code ArrivalIndex}'s edge fold). A reachable cycle resolves
     * conservatively to "no context", so recursion terminates and a cycle can never mint a
     * spurious {@link TenantBinding.Inherited}.
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
        /** target typename -> reaching field edges (parent typename + field name). */
        private final Map<String, List<FieldCoordinates>> reachingEdges = new HashMap<>();
        private final Map<String, Boolean> ctxMemo = new HashMap<>();
        private final Set<String> ctxInProgress = new HashSet<>();
        private final Map<String, Boolean> fannedAncestorMemo = new HashMap<>();
        private final Set<String> fannedAncestorInProgress = new HashSet<>();
        private final Map<String, Boolean> boundAncestorMemo = new HashMap<>();
        private final Set<String> boundAncestorInProgress = new HashSet<>();
        private final Map<String, Set<String>> closureCache = new HashMap<>();

        /** Node dispatch facts, computed once: type name -> decoded tenant position. */
        private final Map<String, Integer> nodePositions = new LinkedHashMap<>();
        private boolean nodeDispatchRoutable = true;

        private final Map<FieldCoordinates, TenantBinding> byCoordinate = new LinkedHashMap<>();
        private final Map<String, TenantBinding.EntityRepBound> byEntityType = new LinkedHashMap<>();
        private final List<ValidationError> rejections = new ArrayList<>();
        /** Coordinates the fan-out ladder rejected, so the marker sweep never double-reports. */
        private final Set<String> fanOutRejected = new HashSet<>();

        Fold(GraphQLSchema sdl,
             Map<FieldCoordinates, GraphitronField> fields,
             Map<String, EntityResolution> entitiesByType,
             Map<String, GraphitronType> types,
             TenantScopes.Configured scopes,
             OperationMemberRelation operationMembers,
             ColumnBindingLedger columnBindings) {
            this.sdl = sdl;
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
            for (var entry : fields.entrySet()) {
                if (!(entry.getValue() instanceof OutputField out)) continue;
                FieldCoordinates coord = entry.getKey();
                TenantBinding arm = armOf(coord, out);
                if (arm != null) {
                    byCoordinate.put(coord, arm);
                }
            }
            sweepUnreachedFanOutMarkers();
            return new TenantBindingIndex(byCoordinate, byEntityType, rejections);
        }

        /**
         * Completeness backstop: every {@code @tenantFanOut} application must end as a
         * {@link TenantBinding.FanOut} verdict or a fan-out rejection. A marked coordinate the
         * classification never modelled as an {@link OutputField} (a nesting type's member, a
         * projected leaf, an already-unclassified field) would otherwise be silently ignored;
         * the sweep turns it into a validate-time rejection.
         */
        private void sweepUnreachedFanOutMarkers() {
            // Objects and interfaces both: the directive is legal on interface field definitions,
            // graphql-java does not copy interface-field directives onto implementors, and field
            // classification only models object coordinates, so an interface marker reaches a
            // verdict through no other route.
            for (var type : sdl.getAllTypesAsList()) {
                if (type.getName().startsWith("__")
                        || !(type instanceof graphql.schema.GraphQLFieldsContainer container)) continue;
                for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                    if (!field.hasAppliedDirective(BuildContext.DIR_TENANT_FAN_OUT)) continue;
                    String coordinate = container.getName() + "." + field.getName();
                    if (byCoordinate.get(FieldCoordinates.coordinates(container.getName(), field.getName()))
                            instanceof TenantBinding.FanOut
                        || fanOutRejected.contains(coordinate)) {
                        continue;
                    }
                    rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                        "'" + coordinate + "' declares @tenantFanOut, but the coordinate never"
                            + " reached the fan-out classification: either the field failed"
                            + " classification on its own (see its error), or its parent is a"
                            + " class-, record-, or nesting-backed type, where the fanned"
                            + " fetcher boundary is deferred in v1. Move the field to a root or"
                            + " @table-backed parent, or remove the directive.");
                }
            }
        }

        // ===== Per-field arm assignment =====

        private TenantBinding armOf(FieldCoordinates coord, OutputField out) {
            // A @tenantFanOut marker routes through its own ladder ahead of everything below, so a
            // marked field always gets a fan-out-specific verdict or rejection, never the generic
            // cross-scope or noTenantBinding message.
            if (fanMarked(coord)) {
                return fanOutArmOf(coord, out);
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
                    coord.getTypeName() + "." + coord.getFieldName(),
                    Rejection.noTenantBinding(
                        coord.getTypeName() + "." + coord.getFieldName(),
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
            if (anyTenant && !direct.declines().isEmpty()) {
                // A declined shape names the tenant column but cannot route on it. Each decline
                // carries its own detail rather than falling through to the generic
                // "nothing names the tenant" text, which would send an author looking for a
                // binding they already wrote. Only where the statement needs a tenant at all:
                // a field whose own SQL stays on the default source has nothing to route, so
                // the shape that could not route it is moot.
                String tenantTable = reach.stream().filter(this::tenantScoped).findFirst()
                    .map(TableRef::tableName).orElse(scopes.columnName());
                for (String detail : direct.declines()) {
                    rejections.add(new ValidationError(
                        coord.getTypeName() + "." + coord.getFieldName(),
                        Rejection.noTenantBinding(
                            coord.getTypeName() + "." + coord.getFieldName(), tenantTable, detail),
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
            // A $session-bound method call reads per-connection state (the handle its
            // connection's mount returned), so which connection serves it is semantics, not
            // plumbing: under a tenant context the call runs on the inherited tenant's
            // connection and observes that tenant's handle. Decided ahead of the reach-derived
            // Untenanted arm, whose "touches no tables" reading is about SQL only.
            if (bindsSessionHandle(out) && tenantContextOf(coord.getTypeName())) {
                return new TenantBinding.Inherited(coord.getTypeName());
            }
            if (!anyTenant) {
                return TenantBinding.Untenanted.INSTANCE;
            }
            if (tenantContextOf(coord.getTypeName())) {
                return new TenantBinding.Inherited(coord.getTypeName());
            }
            rejections.add(new ValidationError(
                coord.getTypeName() + "." + coord.getFieldName(),
                Rejection.noTenantBinding(
                    coord.getTypeName() + "." + coord.getFieldName(),
                    reach.stream().filter(this::tenantScoped).findFirst().orElseThrow().tableName(),
                    "no argument or input field maps to tenant column '"
                        + scopes.columnName() + "', and no ancestor established a tenant"
                        + " context."),
                graphql.language.SourceLocation.EMPTY));
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
         * True when some reaching path to {@code typeName} crosses a tenant-divining edge (a
         * direct binding, or routable node dispatch). The any-path mirror of
         * {@link #tenantContextOf}'s every-path fold, matching the nested-marker rung's posture:
         * a marked field rejects if fanning would contradict a divined tenant on <em>any</em>
         * path (rejections are conservative), while the {@link TenantBinding.Inherited} verdict
         * keeps demanding every-path certainty. Cycles fold to {@code false}.
         */
        private boolean anyBoundAncestor(String typeName) {
            Boolean cached = boundAncestorMemo.get(typeName);
            if (cached != null) return cached;
            if (!boundAncestorInProgress.add(typeName)) return false;
            boolean result = false;
            for (FieldCoordinates edge : reachingEdges.getOrDefault(typeName, List.of())) {
                if (edgeDivinesTenant(edge) || anyBoundAncestor(edge.getTypeName())) {
                    result = true;
                    break;
                }
            }
            boundAncestorInProgress.remove(typeName);
            boundAncestorMemo.put(typeName, result);
            return result;
        }

        /**
         * Whether the edge's own field divines a tenant: the direct-binding and routable
         * node-dispatch facts {@link #edgeEstablishesOrTransmitsContext} reads, without the
         * transitive fold (the caller walks paths itself).
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
            fanOutRejected.add(coordinate);
            rejections.add(new ValidationError(
                coordinate,
                Rejection.directiveConflict(directives, reason),
                graphql.language.SourceLocation.EMPTY));
            return null;
        }

        /**
         * True when some reaching path to {@code typeName} crosses a fanned field. The
         * fanned-ancestor fact behind the nested-{@code @tenantFanOut} rejection; any-path (a
         * rejection concern), unlike {@link #tenantContextOf}'s every-path fold. The children's
         * {@link TenantBinding.Inherited} classification reads the same marker through
         * {@link #edgeEstablishesOrTransmitsContext}, so the two facts derive from one predicate
         * ({@link #fanMarked}) and cannot drift. Cycles fold to {@code false}.
         */
        private boolean anyFannedAncestor(String typeName) {
            Boolean cached = fannedAncestorMemo.get(typeName);
            if (cached != null) return cached;
            if (!fannedAncestorInProgress.add(typeName)) return false;
            boolean result = false;
            for (FieldCoordinates edge : reachingEdges.getOrDefault(typeName, List.of())) {
                if (fanMarked(edge) || anyFannedAncestor(edge.getTypeName())) {
                    result = true;
                    break;
                }
            }
            fannedAncestorInProgress.remove(typeName);
            fannedAncestorMemo.put(typeName, result);
            return result;
        }

        /**
         * Whether the field's method call binds the {@code $session} handle: a
         * {@link no.sikt.graphitron.rewrite.model.MethodBackedField} parameter sourced
         * {@link no.sikt.graphitron.rewrite.model.ParamSource.SessionHandle}, or a
         * {@link no.sikt.graphitron.rewrite.model.ServiceField} carrier entry of
         * {@link no.sikt.graphitron.rewrite.model.MappingEntry.FromSessionHandle}. The same
         * two producers {@code GraphitronSchemaValidator.validateSessionHandleBindings} walks.
         */
        private static boolean bindsSessionHandle(OutputField out) {
            if (out instanceof no.sikt.graphitron.rewrite.model.MethodBackedField mbf) {
                return mbf.method().params().stream().anyMatch(p ->
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
                    e instanceof no.sikt.graphitron.rewrite.model.MappingEntry.FromSessionHandle);
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
         */
        private record DirectBinding(List<TenantBinding.BoundSlot> slots, List<String> declines) {

            static final DirectBinding NONE = new DirectBinding(List.of(), List.of());

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

        /** Accumulates one coordinate's slots and declines, deduping slots by name. */
        private static final class SlotCollector {
            private final List<TenantBinding.BoundSlot> slots = new ArrayList<>();
            private final List<String> declines = new ArrayList<>();
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

            DirectBinding result() {
                return slots.isEmpty() && declines.isEmpty()
                    ? DirectBinding.NONE
                    : new DirectBinding(List.copyOf(slots), List.copyOf(declines));
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
         * surface of the two verbs that have one. Deduped by slot name across members (the same
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
                for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
                    var target = GraphQLTypeUtil.unwrapAll(field.getType());
                    if (!(target instanceof GraphQLObjectType
                        || target instanceof GraphQLInterfaceType
                        || target instanceof GraphQLUnionType)) {
                        continue;
                    }
                    var edge = FieldCoordinates.coordinates(obj.getName(), field.getName());
                    for (String reached : structuralClosure(((GraphQLNamedType) target).getName())) {
                        reachingEdges.computeIfAbsent(reached, k -> new ArrayList<>()).add(edge);
                    }
                }
            }
        }

        /**
         * True when every path reaching {@code typeName} runs through a tenant binding, so a
         * tenant-scoped field on it can inherit the divined value. Conservative on every
         * uncovered shape: roots, unreached types, cycles, and any unbound reaching edge all
         * fold to {@code false}.
         */
        private boolean tenantContextOf(String typeName) {
            Boolean cached = ctxMemo.get(typeName);
            if (cached != null) return cached;
            if (roots.contains(typeName)) return false;
            if (!ctxInProgress.add(typeName)) return false;

            boolean result = computeTenantContext(typeName);

            ctxInProgress.remove(typeName);
            ctxMemo.put(typeName, result);
            return result;
        }

        private boolean computeTenantContext(String typeName) {
            // Batched dispatch surfaces reach the type outside the field-edge graph; each
            // must itself be routable for the type's context to hold.
            if (types.get(typeName) instanceof GraphitronType.NodeType nt
                    && tenantScoped(nt.table())
                    && (!nodeDispatchRoutable || !nodePositions.containsKey(typeName))) {
                return false;
            }
            EntityResolution entity = entitiesByType.get(typeName);
            if (entity != null && tenantScoped(entity.table())
                    && !byEntityType.containsKey(typeName)) {
                return false;
            }
            var edges = reachingEdges.getOrDefault(typeName, List.of());
            if (edges.isEmpty()) {
                // Unreached by any field edge and not a routable dispatch surface: nothing
                // establishes a context.
                return (types.get(typeName) instanceof GraphitronType.NodeType
                        && nodePositions.containsKey(typeName))
                    || byEntityType.containsKey(typeName);
            }
            for (FieldCoordinates edge : edges) {
                if (!edgeEstablishesOrTransmitsContext(edge)) {
                    return false;
                }
            }
            return true;
        }

        private boolean edgeEstablishesOrTransmitsContext(FieldCoordinates edge) {
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
            return tenantContextOf(edge.getTypeName());
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
