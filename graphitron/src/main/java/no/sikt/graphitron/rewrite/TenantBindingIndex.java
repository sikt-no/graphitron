package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DomainReturnType;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.Operation;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.ParticipantFilters;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.TenantBinding;
import no.sikt.graphitron.rewrite.model.TenantScopes;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * {@code <tenantColumn>} is configured.
     */
    public static TenantBindingIndex compute(
            GraphQLSchema sdl,
            Map<FieldCoordinates, GraphitronField> fields,
            Map<String, EntityResolution> entitiesByType,
            Map<String, GraphitronType> types,
            TenantScopes scopes) {
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
        return new Fold(sdl, fields, entitiesByType, types, configured).run();
    }

    /** Every {@code @tenantFanOut} application in a single-tenant build is a validate-time error. */
    private static List<ValidationError> rejectMarkersWithoutTenancy(GraphQLSchema sdl) {
        var rejections = new ArrayList<ValidationError>();
        for (var type : sdl.getAllTypesAsList()) {
            if (type.getName().startsWith("__") || !(type instanceof GraphQLObjectType obj)) continue;
            for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
                if (!field.hasAppliedDirective(BuildContext.DIR_TENANT_FAN_OUT)) continue;
                String coordinate = obj.getName() + "." + field.getName();
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

        private final Set<String> roots = new HashSet<>();
        private final String mutationRootName;
        /** target typename -> reaching field edges (parent typename + field name). */
        private final Map<String, List<FieldCoordinates>> reachingEdges = new HashMap<>();
        private final Map<String, Boolean> ctxMemo = new HashMap<>();
        private final Set<String> ctxInProgress = new HashSet<>();
        private final Map<String, Boolean> fannedAncestorMemo = new HashMap<>();
        private final Set<String> fannedAncestorInProgress = new HashSet<>();
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
             TenantScopes.Configured scopes) {
            this.sdl = sdl;
            this.fields = fields;
            this.entitiesByType = entitiesByType;
            this.types = types;
            this.scopes = scopes;
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
         * projected leaf, an already-unclassified field) would otherwise be silently ignored,
         * and a silently ignored fan-out ask is incomplete data presented as complete; the sweep
         * turns it into a validate-time rejection.
         */
        private void sweepUnreachedFanOutMarkers() {
            for (var type : sdl.getAllTypesAsList()) {
                if (type.getName().startsWith("__") || !(type instanceof GraphQLObjectType obj)) continue;
                for (GraphQLFieldDefinition field : obj.getFieldDefinitions()) {
                    if (!field.hasAppliedDirective(BuildContext.DIR_TENANT_FAN_OUT)) continue;
                    String coordinate = obj.getName() + "." + field.getName();
                    if (byCoordinate.get(FieldCoordinates.coordinates(obj.getName(), field.getName()))
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
            var slots = directSlots(out.operation());
            if (slots.isEmpty()) {
                slots = polymorphicFilterSlots(out);
            }
            if (!slots.isEmpty()) {
                return new TenantBinding.ArgumentBound(slots);
            }
            if (out.operation() instanceof Operation.NodeResolve) {
                // Node dispatch spans types; the arm exists iff every tenant-scoped node
                // type's key embeds the tenant column (rejections fired once in
                // classifyNodeDispatch). No tenant-scoped node types at all means node
                // dispatch never leaves the default source.
                return nodePositions.isEmpty()
                    ? TenantBinding.Untenanted.INSTANCE
                    : TenantBinding.NodeIdBound.INSTANCE;
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
         * a fan-out-specific message. The {@code @service}/{@code @tableMethod} rung runs ahead of
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
            if (fieldDef.hasAppliedDirective(BuildContext.DIR_TABLE_METHOD)) {
                return rejectFanOut(coordinate,
                    List.of(BuildContext.DIR_TENANT_FAN_OUT, BuildContext.DIR_TABLE_METHOD),
                    "'" + coordinate + "' combines @tenantFanOut with @tableMethod: fan-out"
                        + " generates the field's SQL itself, and the consumer-SQL fan-out"
                        + " combination is deferred. Remove one of the directives.");
            }
            if (fieldDef.hasAppliedDirective(BuildContext.DIR_ROUTINE)) {
                return rejectFanOut(coordinate,
                    List.of(BuildContext.DIR_TENANT_FAN_OUT, BuildContext.DIR_ROUTINE),
                    "'" + coordinate + "' combines @tenantFanOut with @routine: fan-out generates"
                        + " the field's SQL itself, and a database routine's SQL is not"
                        + " graphitron's to run per tenant. Remove one of the directives.");
            }
            if (out.operation() instanceof Operation.Lookup) {
                return rejectFanOut(coordinate,
                    List.of(BuildContext.DIR_TENANT_FAN_OUT, BuildContext.DIR_LOOKUP_KEY),
                    "'" + coordinate + "' combines @tenantFanOut with @lookupKey: lookup enforces"
                        + " one row per input key, and fanning yields up to one row per tenant per"
                        + " key, silently breaking that invariant.");
            }
            if (out.operation() instanceof Operation.Paginate) {
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
            // fetcher does not intercept yet, so the marker there must reject loudly rather than
            // be silently ignored; relaxing this later is additive.
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
            List<TableRef> reach = reachedTables(out);
            boolean anyTenant = reach.stream().anyMatch(this::tenantScoped);
            boolean anyGlobal = reach.stream().anyMatch(t -> !tenantScoped(t));
            if (!anyTenant) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' reaches no tenant-scoped table: its data is global,"
                        + " so there is nothing to fan out over.");
            }
            if (anyGlobal) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' touches tenant-scoped and global tables in one"
                        + " statement (" + reach.stream().map(TableRef::tableName).distinct()
                            .collect(java.util.stream.Collectors.joining(", "))
                        + "); one statement cannot span the per-tenant and default sources, so"
                        + " it cannot run once per tenant either.");
            }
            var slots = directSlots(out.operation());
            if (slots.isEmpty()) {
                slots = polymorphicFilterSlots(out);
            }
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
            if (tenantContextOf(coord.getTypeName())) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' sits under a tenant-bound ancestor: the tenant is"
                        + " already divined and handed down, and fanning would contradict it.");
            }
            return TenantBinding.FanOut.INSTANCE;
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
                case ChildField.PivotField f -> tables.add(f.spec().pivotTable());
                case ChildField.BatchedPivotField f -> tables.add(f.spec().pivotTable());
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

        /**
         * Direct slots for the multi-table polymorphic roots, whose filter surface lives per
         * participant ({@code participantFilters}) rather than on the operation: a filter
         * binding the tenant column on any participant divines the whole dispatch. Deduped by
         * slot name (the same argument typically binds on every participant).
         */
        private List<TenantBinding.BoundSlot> polymorphicFilterSlots(OutputField out) {
            List<ParticipantFilters> filters = switch (out) {
                case QueryField.QueryInterfaceField f -> f.participantFilters();
                case QueryField.QueryUnionField f -> f.participantFilters();
                default -> List.of();
            };
            var slots = new ArrayList<TenantBinding.BoundSlot>();
            var seenNames = new HashSet<String>();
            for (ParticipantFilters pf : filters) {
                for (TenantBinding.BoundSlot slot : slotsFromFilters(pf.filters())) {
                    if (seenNames.add(slot.slotName())) {
                        slots.add(slot);
                    }
                }
            }
            return slots;
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

        // ===== Direct bindings off the operation's own carriers =====

        private List<TenantBinding.BoundSlot> directSlots(Operation op) {
            return switch (op) {
                case Operation.Fetch f -> slotsFromFilters(f.filters());
                case Operation.Paginate p -> slotsFromFilters(p.filters());
                case Operation.Lookup l -> slotsFromLookup(l.lookupMapping());
                case Operation.Insert i -> slotsFromTableInput(i.input());
                case Operation.Upsert u -> slotsFromTableInput(u.input());
                default -> List.of();
            };
        }

        private List<TenantBinding.BoundSlot> slotsFromFilters(List<WhereFilter> filters) {
            var slots = new ArrayList<TenantBinding.BoundSlot>();
            for (WhereFilter filter : filters) {
                if (!(filter instanceof GeneratedConditionFilter gcf)) continue;
                for (BodyParam param : gcf.bodyParams()) {
                    collectFromBodyParam(param, slots);
                }
            }
            return slots;
        }

        private void collectFromBodyParam(BodyParam param, List<TenantBinding.BoundSlot> slots) {
            switch (param) {
                case BodyParam.Eq eq -> {
                    if (matchesTenantColumn(eq.column())) {
                        slots.add(new TenantBinding.BoundSlot(eq.name(), eq.column()));
                    }
                }
                case BodyParam.In in -> {
                    if (matchesTenantColumn(in.column())) {
                        slots.add(new TenantBinding.BoundSlot(in.name(), in.column()));
                    }
                }
                case BodyParam.RowEq rowEq -> {
                    for (ColumnRef col : rowEq.columns()) {
                        if (matchesTenantColumn(col)) {
                            slots.add(new TenantBinding.BoundSlot(rowEq.name(), col));
                        }
                    }
                }
                case BodyParam.RowIn rowIn -> {
                    for (ColumnRef col : rowIn.columns()) {
                        if (matchesTenantColumn(col)) {
                            slots.add(new TenantBinding.BoundSlot(rowIn.name(), col));
                        }
                    }
                }
                case BodyParam.RemoteColumnPredicate remote ->
                    // The predicate lands on a joined table's column; a tenant column reached
                    // through a @reference path still divines the operation's tenant.
                    collectFromBodyParam(remote.inner(), slots);
            }
        }

        private List<TenantBinding.BoundSlot> slotsFromLookup(LookupMapping mapping) {
            if (!(mapping instanceof LookupMapping.ColumnMapping cm)) {
                return List.of();
            }
            var slots = new ArrayList<TenantBinding.BoundSlot>();
            for (var arg : cm.args()) {
                switch (arg) {
                    case LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg s -> {
                        if (matchesTenantColumn(s.targetColumn())) {
                            slots.add(new TenantBinding.BoundSlot(s.argName(), s.targetColumn()));
                        }
                    }
                    case LookupMapping.ColumnMapping.LookupArg.MapInput mi -> {
                        for (InputColumnBinding.MapBinding b : mi.bindings()) {
                            if (matchesTenantColumn(b.targetColumn())) {
                                slots.add(new TenantBinding.BoundSlot(b.fieldName(), b.targetColumn()));
                            }
                        }
                    }
                    // Decoded node-id lookups carry per-id tenants (the per-row family), not a
                    // single argument value; they classify through the node dispatch facts,
                    // never as an ArgumentBound slot.
                    case LookupMapping.ColumnMapping.LookupArg.DecodedRecord ignored -> { }
                }
            }
            return slots;
        }

        private List<TenantBinding.BoundSlot> slotsFromTableInput(
                ArgumentRef.InputTypeArg.TableInputArg input) {
            var slots = new ArrayList<TenantBinding.BoundSlot>();
            var seenNames = new HashSet<String>();
            for (InputColumnBindingGroup group : input.fieldBindings()) {
                if (!(group instanceof InputColumnBindingGroup.MapGroup mg)) continue;
                for (InputColumnBinding.MapBinding b : mg.bindings()) {
                    if (matchesTenantColumn(b.targetColumn()) && seenNames.add(b.fieldName())) {
                        slots.add(new TenantBinding.BoundSlot(b.fieldName(), b.targetColumn()));
                    }
                }
            }
            // INSERT / UPSERT: fieldBindings is structurally empty (the VALUES emission walks
            // fields() directly), so the divining slots come from the same envelope — a plain
            // input field whose column mapping lands on the tenant column routes the mutation.
            collectFromInputFields(input.fields(), slots, seenNames);
            return slots;
        }

        private void collectFromInputFields(List<InputField> fields,
                                            List<TenantBinding.BoundSlot> slots,
                                            HashSet<String> seenNames) {
            for (InputField field : fields) {
                switch (field) {
                    case InputField.ColumnBackedField cf when !cf.isComposite() -> {
                        if (matchesTenantColumn(cf.columns().get(0)) && seenNames.add(cf.name())) {
                            slots.add(new TenantBinding.BoundSlot(cf.name(), cf.columns().get(0)));
                        }
                    }
                    // A nested grouping input flattens onto the same table; descend.
                    case InputField.NestingField nf -> collectFromInputFields(nf.fields(), slots, seenNames);
                    // Composite NodeId tuples and non-column fields never divine a single
                    // argument value; those shapes belong to the per-row family and the
                    // deliberate fan-out arm.
                    default -> { }
                }
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
            if (fields.get(edge) instanceof OutputField out) {
                var slots = directSlots(out.operation());
                if (!slots.isEmpty()) {
                    return true;
                }
                if (out.operation() instanceof Operation.NodeResolve) {
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
