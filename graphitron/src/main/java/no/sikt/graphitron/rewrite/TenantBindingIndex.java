package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
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
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.UpdateRows;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;
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
import java.util.Optional;
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
     * arm's payload. A nesting type's fields, which that relation leaves out, are assigned too:
     * each classified instance under a {@link ChildField.NestingField} reads its leaf-local
     * members, so a field of a type without {@code @table} routes as it would on its parent. A condition member's slots are read from {@code columnBindings}, the column
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
     * A tenancy marker, with the two texts its completeness sweep rejects with: one for a
     * single-tenant build, where the marker decides nothing, and one for a coordinate that never
     * reached the marker's ladder. Reaching the ladder is the marker's verdict, since every ladder
     * ends in an arm or a marker-specific rejection. {@code typeSite} says where the marker is
     * applied: on an object type, whose coordinate is the type name and whose ladder runs in
     * {@link Fold#classifyEntityDispatch}, or on a field definition, whose coordinate is
     * {@code "Type.field"} and whose ladder runs in {@link Fold#armOf}.
     */
    private record TenancyMarker(String directive,
                                 boolean typeSite,
                                 java.util.function.UnaryOperator<String> withoutTenancy,
                                 java.util.function.UnaryOperator<String> unreached) {}

    /**
     * One use of a {@code @tenant} application: the output field whose arguments reach it, and
     * the schema coordinate of the marked argument or input field.
     */
    private record TenantMarkerUse(FieldCoordinates field, String definition) {}

    private static final List<TenancyMarker> TENANCY_MARKERS = List.of(
        new TenancyMarker(BuildContext.DIR_TENANT_FAN_OUT, false,
            coordinate -> "'" + coordinate + "' declares @tenantFanOut, but this build configures no"
                + " <tenantColumn>: there are no tenants to fan out over. Configure"
                + " database-per-tenant routing or remove the directive.",
            coordinate -> "'" + coordinate + "' declares @tenantFanOut, but the coordinate never"
                + " reached the fan-out classification: either the field failed"
                + " classification on its own (see its error), or its parent is a"
                + " class-, record-, or nesting-backed type, where the fanned"
                + " fetcher boundary is deferred in v1. Move the field to a root or"
                + " @table-backed parent, or remove the directive."),
        new TenancyMarker(BuildContext.DIR_GLOBAL_DATA, false,
            coordinate -> "'" + coordinate + "' declares @globalData, but this build configures no"
                + " <tenantColumn>: every field already runs on the one database. Remove the"
                + " directive.",
            coordinate -> "'" + coordinate + "' declares @globalData, but the coordinate never"
                + " reached the tenant-binding classification: either the field failed"
                + " classification on its own (see its error), or it is an interface field or a"
                + " field of a nesting type. @globalData is supported on root @service fields"
                + " only; remove the directive."),
        new TenancyMarker(BuildContext.DIR_DEFAULT_TENANT, true,
            coordinate -> "'" + coordinate + "' declares @defaultTenant, but this build configures"
                + " no <tenantColumn>: every entity already resolves on the one database. Remove"
                + " the directive.",
            // The ladder runs for every resolvable entity, so a marked type misses it only when
            // _entities never dispatches it: no @key, or every @key resolvable: false.
            coordinate -> "'" + coordinate + "' declares @defaultTenant, but it has no resolvable"
                + " key, so _entities never resolves it. Remove the directive."));

    /**
     * Every tenancy marker application, as {@code (marker, "Type")} for a type-site marker on an
     * object type and {@code (marker, "Type.field")} for a field-site one. Walks every
     * {@link graphql.schema.GraphQLFieldsContainer}, objects <em>and</em> interfaces: the field
     * markers are legal on interface field definitions, graphql-java does not copy interface-field
     * directives onto implementors, and field classification only models object coordinates, so
     * an interface marker reaches a verdict through no other route.
     */
    private static void forEachMarkerApplication(GraphQLSchema sdl,
                                                 java.util.function.BiConsumer<TenancyMarker, String> action) {
        for (var type : sdl.getAllTypesAsList()) {
            if (type.getName().startsWith("__")
                    || !(type instanceof graphql.schema.GraphQLFieldsContainer container)) continue;
            if (container instanceof GraphQLObjectType object) {
                for (TenancyMarker marker : TENANCY_MARKERS) {
                    if (marker.typeSite() && object.hasAppliedDirective(marker.directive())) {
                        action.accept(marker, object.getName());
                    }
                }
            }
            for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                for (TenancyMarker marker : TENANCY_MARKERS) {
                    if (!marker.typeSite() && field.hasAppliedDirective(marker.directive())) {
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
        for (String definition : tenantMarkedDefinitions(sdl).keySet()) {
            rejections.add(markerRejection(List.of(BuildContext.DIR_TENANT), definition,
                "'" + definition + "' declares @tenant, but this build configures no"
                    + " <tenantColumn>: there is no tenant to route on. Remove the directive."));
        }
        return rejections;
    }

    /**
     * Every {@code @tenant} application, keyed by the schema coordinate of the argument or input
     * field carrying it, in schema order: the arguments of every object and interface field, and
     * the fields of every input object. The marker is legal on {@code ARGUMENT_DEFINITION}, which
     * also covers a directive definition's own arguments; those are no use site of a value and are
     * not read.
     */
    private static Map<String, graphql.schema.GraphQLInputValueDefinition> tenantMarkedDefinitions(
            GraphQLSchema sdl) {
        var marked = new LinkedHashMap<String, graphql.schema.GraphQLInputValueDefinition>();
        for (var type : sdl.getAllTypesAsList()) {
            if (type.getName().startsWith("__")) continue;
            if (type instanceof graphql.schema.GraphQLFieldsContainer container) {
                for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                    for (var argument : field.getArguments()) {
                        if (argument.hasAppliedDirective(BuildContext.DIR_TENANT)) {
                            marked.put(SchemaCoordinateSyntax.ofArgument(container.getName(),
                                field.getName(), argument.getName()), argument);
                        }
                    }
                }
            } else if (type instanceof GraphQLInputObjectType input) {
                for (var field : input.getFieldDefinitions()) {
                    if (field.hasAppliedDirective(BuildContext.DIR_TENANT)) {
                        marked.put(SchemaCoordinateSyntax.ofField(input.getName(), field.getName()),
                            field);
                    }
                }
            }
        }
        return marked;
    }

    /**
     * Whether the client cannot omit a bound slot: its read path is non-null all the way down,
     * the top-level argument for {@link TenantBinding.SlotRead.TopLevelArg} (read by the slot
     * name) and the outer argument plus every input field on the path for
     * {@link TenantBinding.SlotRead.NestedInput}. A list wrapper's element type counts too,
     * since {@code [ID]!} can arrive as {@code [null]} and name no tenant. Nullability decides
     * alone: a non-null list can still arrive empty. A context argument is supplied by the
     * consumer server-side, so a missing one is a wiring bug, not a client choice.
     */
    static boolean slotRequired(GraphQLFieldDefinition definition, String slotName,
                                TenantBinding.SlotRead read) {
        return switch (read) {
            case TenantBinding.SlotRead.ContextArg ignored -> true;
            case TenantBinding.SlotRead.TopLevelArg ignored ->
                nonNullAllTheWay(argumentType(definition, slotName));
            case TenantBinding.SlotRead.NestedInput nested -> {
                graphql.schema.GraphQLType type = argumentType(definition, nested.outerArgName());
                if (!nonNullAllTheWay(type)) {
                    yield false;
                }
                for (String segment : nested.path()) {
                    if (!(GraphQLTypeUtil.unwrapAll(type) instanceof GraphQLInputObjectType input)
                            || input.getField(segment) == null) {
                        throw unresolvableSlot(definition, slotName, read);
                    }
                    type = input.getField(segment).getType();
                    if (!nonNullAllTheWay(type)) {
                        yield false;
                    }
                }
                yield true;
            }
        };
    }

    private static graphql.schema.GraphQLType argumentType(GraphQLFieldDefinition definition,
                                                          String argumentName) {
        var argument = definition == null ? null : definition.getArgument(argumentName);
        if (argument == null) {
            throw unresolvableSlot(definition, argumentName,
                TenantBinding.SlotRead.TopLevelArg.INSTANCE);
        }
        return argument.getType();
    }

    private static boolean nonNullAllTheWay(graphql.schema.GraphQLType type) {
        graphql.schema.GraphQLType current = type;
        while (current instanceof graphql.schema.GraphQLNonNull nonNull) {
            current = nonNull.getWrappedType();
            if (!(current instanceof graphql.schema.GraphQLList list)) {
                return true;
            }
            current = list.getWrappedType();
        }
        return false;
    }

    private static IllegalStateException unresolvableSlot(GraphQLFieldDefinition definition,
                                                          String slotName, TenantBinding.SlotRead read) {
        return new IllegalStateException("Graphitron generator bug (tenant routing): the bound"
            + " slot '" + slotName + "' read as " + read + " does not resolve against the"
            + " arguments of field '" + (definition == null ? "?" : definition.getName())
            + "', so its presence cannot be read off the schema.");
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
        private final Map<String, EntityResolution> entitiesByType;
        private final Map<String, GraphitronType> types;
        private final TenantScopes.Configured scopes;
        private final ColumnBindingLedger columnBindings;
        /**
         * Every classified instance of every coordinate the fold assigns: the one per-field input
         * every reader below takes. See {@link Site}.
         */
        private final Map<FieldCoordinates, List<Site>> sites;

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

        /**
         * The dispatch facts, decided once by {@link #classifyNodeDispatch} and
         * {@link #classifyEntityDispatch}: type name -> how {@code _entities} and
         * {@code Query.node} enter it. See {@link DispatchFact}.
         */
        private final Map<String, DispatchFact> dispatchFacts = new LinkedHashMap<>();
        /**
         * The sites {@link #handsDownAsIfFixed} counts, computed once before the ancestor folds
         * read them. Identity-keyed: each {@link Site} is one classified instance.
         */
        private final Set<Site> countedSites =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        /**
         * The coordinates classification rejected outright: each holds a
         * {@link GraphitronField.UnclassifiedField}, so it has an edge and no {@link Site}. Read
         * in the same pass over the classified fields as {@link #sites}. Flat only: a rejection
         * inside a nesting type lands at the embedding field's coordinate.
         */
        private final Set<FieldCoordinates> classificationRejected = new HashSet<>();
        /**
         * The classification-rejected edges both ancestor folds count as handing a tenant down,
         * computed once in {@link #run} beside {@link #countedSites}: an in-domain
         * {@link #classificationRejected} edge that is not {@code @globalData}-marked and whose
         * {@link #sdlReachesTenantTable SDL reach} holds a tenant-scoped table. The edge-level
         * sibling of {@link #handsDownAsIfFixed}: a rejected field has no site, so no reach,
         * reading or offered fix to argue from, but its SDL return type still answers whether its
         * own statement will need a tenant once it classifies. If it will, the fixed field either
         * hands a tenant down or is itself a tenant rejection {@link #handsDownAsIfFixed} counts,
         * so its subtree's "no ancestor established" errors are consequential and the field
         * reports its own error alone. A {@code @tenantFanOut} edge needs no entry here:
         * {@link #edgeEstablishesContext} and {@link #belowFannedEdge} already read the marker.
         *
         * <p>Only failing builds see it: {@link GraphitronSchemaValidator} reports every
         * {@link GraphitronField.UnclassifiedField}, so a passing build has none. The proxy errs
         * both ways, at a bounded cost. It over-counts a field whose fix needs no tenant although
         * its return type leads straight, or through a connection, to tenant-scoped rows: a real
         * error below it is hidden, and a {@code @tenantFanOut} child is rejected as sitting under
         * a tenant-bound ancestor, until the field classifies and the ordinary rules judge its
         * site. It under-counts below a result, nesting or payload type, where the walk stops:
         * that subtree keeps today's consequential errors rather than risk hiding real ones below
         * a global child of the stopped-at type.
         *
         * <p>The arms children get below a counted edge are counterfactual and reach no consumer.
         * The validator reads only {@link TenantBindingIndex#rejections()}; the emit-side readers,
         * {@link no.sikt.graphitron.rewrite.generators.util.EntityFetcherDispatchClassGenerator}
         * and the per-field seam {@link GraphitronSchema#tenantBindingOf} (read by the launcher,
         * routine-write and tenant DSL emitters), run only once the error list is empty. A
         * diagnostics consumer that shows arms of a failing build must revisit this.
         */
        private final Set<FieldCoordinates> countedRejectedEdges = new HashSet<>();

        private final Map<FieldCoordinates, TenantBinding> byCoordinate = new LinkedHashMap<>();
        private final List<ValidationError> rejections = new ArrayList<>();
        /** Per tenancy marker, the coordinates that reached its ladder: the sweep's verdict set. */
        private final Map<String, Set<String>> markerVerdicts = new HashMap<>();

        /** Every {@code @tenant} application, by the coordinate of the argument or input field. */
        private final Map<String, graphql.schema.GraphQLInputValueDefinition> tenantMarked;
        /** The {@code @tenant} uses the fold minted a slot from: the marker sweep's verdict set. */
        private final Set<TenantMarkerUse> consumedTenantMarkers = new HashSet<>();
        /** The {@code @tenant} uses the fold found bound into a jOOQ record parameter. */
        private final Set<TenantMarkerUse> jooqBoundTenantMarkers = new HashSet<>();
        /** The {@code @tenant} uses the type rung declined, with the rung's text. */
        private final Map<TenantMarkerUse, String> mistypedTenantMarkers = new HashMap<>();

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
            if (columnBindings == ColumnBindingLedger.EMPTY) {
                // Same ground as the member relation: a hand-built schema that never ran the walk
                // would otherwise read every condition path as binding nothing.
                throw new IllegalArgumentException(
                    "tenant-binding fold requires the walk-minted column-binding ledger");
            }
            this.columnBindings = columnBindings;
            this.sites = sitesOf(fields, operationMembers, classificationRejected);
            this.mutationRootName = sdl.getMutationType() == null ? null : sdl.getMutationType().getName();
            this.tenantMarked = tenantMarkedDefinitions(sdl);
            recordRoot(sdl.getQueryType());
            recordRoot(sdl.getMutationType());
            recordRoot(sdl.getSubscriptionType());
            buildEdges();
        }

        private void recordRoot(GraphQLObjectType root) {
            if (root != null) roots.add(root.getName());
        }

        /**
         * One classified instance of a coordinate: the leaf and its operation members. A flat
         * coordinate is one site, its members read off the minted {@link OperationMemberRelation}.
         * A nesting type's field is classified inside each enclosing {@code @table} parent and
         * lives only in {@link ChildField.NestingField#nestedFields()}, so it gets one site per
         * classified instance, its members read through the leaf-local
         * {@link no.sikt.graphitron.rewrite.model.OperationMembers#membersOf}, the same production
         * the emit-side producers read for nested instances.
         */
        private record Site(FieldCoordinates coord, OutputField field, List<OperationMember> members,
                            boolean nested) {}

        /** One site's tenant verdict: an arm, or the rejections that stand in for one. */
        private sealed interface Verdict {
            record Arm(TenantBinding binding) implements Verdict {}

            record Rejected(List<ValidationError> rejections) implements Verdict {
                public Rejected {
                    rejections = List.copyOf(rejections);
                }
            }

            static Verdict rejected(ValidationError rejection) {
                return new Rejected(List.of(rejection));
            }
        }

        /**
         * The sites of every classified coordinate; the coordinates classification rejected are
         * collected into {@code rejected} in the same pass, so "rejected" and "has a site" come
         * from one read.
         */
        private static Map<FieldCoordinates, List<Site>> sitesOf(
                Map<FieldCoordinates, GraphitronField> fields, OperationMemberRelation operationMembers,
                Set<FieldCoordinates> rejected) {
            var sites = new LinkedHashMap<FieldCoordinates, List<Site>>();
            for (var entry : fields.entrySet()) {
                if (entry.getValue() instanceof OutputField out) {
                    sites.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(
                        new Site(entry.getKey(), out, operationMembers.membersOf(entry.getKey()), false));
                } else if (entry.getValue() instanceof GraphitronField.UnclassifiedField) {
                    rejected.add(entry.getKey());
                }
            }
            for (var field : fields.values()) {
                if (field instanceof ChildField.NestingField nesting) {
                    addNestedSites(nesting, sites);
                }
            }
            return sites;
        }

        private static void addNestedSites(ChildField.NestingField nesting,
                                           Map<FieldCoordinates, List<Site>> sites) {
            for (ChildField child : nesting.nestedFields()) {
                var coord = FieldCoordinates.coordinates(child.parentTypeName(), child.name());
                sites.computeIfAbsent(coord, k -> new ArrayList<>()).add(new Site(coord, child,
                    no.sikt.graphitron.rewrite.model.OperationMembers.membersOf(child), true));
                if (child instanceof ChildField.NestingField deeper) {
                    addNestedSites(deeper, sites);
                }
            }
        }

        private List<Site> sitesAt(FieldCoordinates coord) {
            return sites.getOrDefault(coord, List.of());
        }

        TenantBindingIndex run() {
            classifyNodeDispatch();
            classifyEntityDispatch();
            for (var coordinateSites : sites.values()) {
                for (Site site : coordinateSites) {
                    if (handsDownAsIfFixed(site)) {
                        countedSites.add(site);
                    }
                }
            }
            for (FieldCoordinates coord : classificationRejected) {
                if (edgeTargets.containsKey(coord) && !globalMarked(coord)
                        && sdlReachesTenantTable(coord)) {
                    countedRejectedEdges.add(coord);
                }
            }
            // The ancestor facts read the dispatch facts and the counted sites and edges just
            // filled, and armOf reads them.
            foldAncestorContexts();
            for (var entry : sites.entrySet()) {
                var arm = combine(entry.getValue().stream().map(this::checkedArmOf).toList());
                if (arm != null) {
                    byCoordinate.put(entry.getKey(), arm);
                }
            }
            sweepUnreachedMarkers();
            sweepTenantMarkers();
            return new TenantBindingIndex(byCoordinate, routedEntities(), rejections);
        }

        /**
         * {@link #armOf}, holding it to what the ancestor folds assumed: a site
         * {@link #handsDownAsIfFixed} counted as handing a tenant down must route by its parent
         * row, inherit, or reject. Any other verdict means the folds handed a tenant to a subtree
         * that will not get one, which is a generator bug, not an authoring error.
         */
        private Verdict checkedArmOf(Site site) {
            Verdict verdict = armOf(site);
            boolean expected = switch (verdict) {
                case Verdict.Rejected ignored -> true;
                case Verdict.Arm a -> a.binding() instanceof TenantBinding.ParentRowBound
                    || a.binding() instanceof TenantBinding.Inherited;
            };
            if (!expected && countedSites.contains(site)) {
                var coord = site.coord();
                throw new IllegalStateException("Generator bug: '" + coord.getTypeName() + "."
                    + coord.getFieldName() + "' was counted as handing its subtree a tenant, but"
                    + " classified " + verdict + " rather than routing by its parent row,"
                    + " inheriting, or rejecting.");
            }
            return verdict;
        }

        /**
         * Folds one coordinate's site verdicts into its arm, or {@code null}. Equal arms at every
         * site record that arm. A rejected site records no arm and reports each distinct rejection
         * once across sites; a distinct text from another site is kept, which is what preserves a
         * hint only one parent's site produces. Sites yielding unequal arms record no arm and no
         * text of their own: the one per-parent input, the parent-row slot, applies only to a
         * batched field, and {@code GraphitronSchemaValidator} already rejects a batched field of a
         * nesting type shared by several parents. On a schema it admits the case cannot arise, and
         * should it ever, the missing arm fails the generation at the emitter's invariant rather
         * than crashing the build of a schema the validator is about to reject.
         */
        private TenantBinding combine(List<Verdict> verdicts) {
            TenantBinding arm = null;
            boolean rejected = false;
            boolean disagree = false;
            for (Verdict verdict : verdicts) {
                switch (verdict) {
                    case Verdict.Rejected r -> {
                        rejected = true;
                        for (ValidationError error : r.rejections()) {
                            if (!rejections.contains(error)) {
                                rejections.add(error);
                            }
                        }
                    }
                    case Verdict.Arm a -> {
                        if (arm == null) {
                            arm = a.binding();
                        } else if (!arm.equals(a.binding())) {
                            disagree = true;
                        }
                    }
                }
            }
            return rejected || disagree ? null : arm;
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

        /**
         * The {@code @tenant} completeness sweep, per use site: every output field whose argument
         * trees reach a marked argument or input field is one use of it, and every use the fold
         * did not mint a slot from is rejected at the definition, naming the use. Per use rather
         * than per definition because one input type is consumed by many fields: a definition
         * that routes one root service and is silently ignored at a child service or a query
         * input would leave the second site running on a tenant the marked value never had to
         * agree with. The reasons are checked in order, the first that applies wins.
         */
        private void sweepTenantMarkers() {
            if (tenantMarked.isEmpty()) return;
            var uses = tenantMarkerUses();
            // armOf can leave a coordinate before reading its direct binding (a marker ladder, a
            // cross-scope reach), and the binding is what consumes a marked use; read it here so
            // the verdict does not depend on which rung the field stopped at.
            uses.stream().map(TenantMarkerUse::field).distinct()
                .flatMap(c -> sitesAt(c).stream())
                .filter(site -> site.field() instanceof ServiceField)
                .forEach(this::directBinding);
            for (TenantMarkerUse use : uses) {
                if (consumedTenantMarkers.contains(use)) continue;
                rejections.add(markerRejection(List.of(BuildContext.DIR_TENANT), use.definition(),
                    "'" + use.definition() + "' declares @tenant, but at '"
                        + use.field().getTypeName() + "." + use.field().getFieldName() + "' "
                        + unconsumedTenantMarkerReason(use)));
            }
        }

        private String unconsumedTenantMarkerReason(TenantMarkerUse use) {
            FieldCoordinates coord = use.field();
            var field = sitesAt(coord).stream().map(Site::field).findFirst().orElse(null);
            if (!(field instanceof ServiceField && field instanceof OutputField out)) {
                GraphQLFieldDefinition def = fieldDefinition(coord);
                if (roots.contains(coord.getTypeName()) && def != null
                        && def.hasAppliedDirective(BuildContext.DIR_SERVICE)
                        && !(field instanceof OutputField)) {
                    return "the field did not classify (see its own error), so nothing reads the"
                        + " marked value.";
                }
                return "it is read by a field that is not a root @service:"
                    + " a query or @mutation field binds the tenant through @field(name: \""
                    + scopes.columnName() + "\") on the tenant column, and a child service runs on"
                    + " its parent's tenant. Remove the directive.";
            }
            var definition = tenantMarked.get(use.definition());
            var named = GraphQLTypeUtil.unwrapAll(definition.getType());
            if (!(named instanceof graphql.schema.GraphQLScalarType)) {
                return "it is not a scalar: @tenant marks the one value that names the tenant, so"
                    + " put it on the scalar argument or input field that holds it.";
            }
            if (definition.hasAppliedDirective(BuildContext.DIR_NODE_ID)) {
                return "it carries @nodeId, whose decoded id already names its tenant. Remove"
                    + " @tenant.";
            }
            if (jooqBoundTenantMarkers.contains(use)) {
                return "it is bound into a jOOQ record parameter, where a field names a column:"
                    + " bind it to the tenant column with @field(name: \"" + scopes.columnName()
                    + "\") instead, which routes the call.";
            }
            var global = reachedTables(out).stream().filter(t -> !tenantScoped(t)).findFirst();
            if (global.isPresent()) {
                return "the service returns global @table type '"
                    + GraphQLTypeUtil.unwrapAll(fieldDefinition(coord).getType()).getName()
                    + "' (table '" + global.get().tableName() + "'), which runs on the default"
                    + " source, so there is no tenant to route. Remove the directive.";
            }
            String mistyped = mistypedTenantMarkers.get(use);
            if (mistyped != null) {
                return mistyped;
            }
            return "the service's parameters do not read it as a plain value. Mark a scalar the"
                + " service receives as a parameter, or as a member of a Java bean or record"
                + " parameter, or remove the directive.";
        }

        /**
         * Every use of a {@code @tenant} application: for each field of every object and interface
         * type, the marked arguments and the marked input fields its argument trees reach.
         * Cycle-safe, since input types can recurse.
         */
        private Set<TenantMarkerUse> tenantMarkerUses() {
            var uses = new java.util.LinkedHashSet<TenantMarkerUse>();
            for (var type : sdl.getAllTypesAsList()) {
                if (type.getName().startsWith("__")
                        || !(type instanceof graphql.schema.GraphQLFieldsContainer container)) continue;
                for (GraphQLFieldDefinition field : container.getFieldDefinitions()) {
                    var use = FieldCoordinates.coordinates(container.getName(), field.getName());
                    var visited = new HashSet<String>();
                    for (var argument : field.getArguments()) {
                        String coordinate = SchemaCoordinateSyntax.ofArgument(container.getName(),
                            field.getName(), argument.getName());
                        if (tenantMarked.containsKey(coordinate)) {
                            uses.add(new TenantMarkerUse(use, coordinate));
                        }
                        if (GraphQLTypeUtil.unwrapAll(argument.getType()) instanceof GraphQLInputObjectType input) {
                            forEachInputField(input, visited, definition -> {
                                if (tenantMarked.containsKey(definition)) {
                                    uses.add(new TenantMarkerUse(use, definition));
                                }
                            });
                        }
                    }
                }
            }
            return uses;
        }

        /**
         * The coordinate of every field of {@code input} and of the input objects below it, each
         * input type visited once.
         */
        private static void forEachInputField(GraphQLInputObjectType input, Set<String> visited,
                                              java.util.function.Consumer<String> action) {
            if (!visited.add(input.getName())) return;
            for (var field : input.getFieldDefinitions()) {
                action.accept(SchemaCoordinateSyntax.ofField(input.getName(), field.getName()));
                if (GraphQLTypeUtil.unwrapAll(field.getType()) instanceof GraphQLInputObjectType nested) {
                    forEachInputField(nested, visited, action);
                }
            }
        }

        private void recordMarkerVerdict(String directive, String coordinate) {
            markerVerdicts.computeIfAbsent(directive, k -> new HashSet<>()).add(coordinate);
        }

        // ===== Per-field arm assignment =====

        private Verdict armOf(Site site) {
            FieldCoordinates coord = site.coord();
            OutputField out = site.field();
            String coordinate = coord.getTypeName() + "." + coord.getFieldName();
            // A tenancy marker routes through its own ladder ahead of everything below, so a
            // marked field always gets a marker-specific verdict or rejection, never the generic
            // cross-scope or noTenantBinding message. The ladders serve flat sites only: a nesting
            // type's field is deferred for both markers, so a nested site carrying one gets no arm
            // and records no verdict, and the unreached-marker sweep rejects it.
            boolean fanMarked = fanMarked(coord);
            boolean globalMarked = globalMarked(coord);
            if (site.nested() && (fanMarked || globalMarked)) {
                return new Verdict.Rejected(List.of());
            }
            if (fanMarked && globalMarked) {
                recordMarkerVerdict(BuildContext.DIR_TENANT_FAN_OUT, coordinate);
                recordMarkerVerdict(BuildContext.DIR_GLOBAL_DATA, coordinate);
                return Verdict.rejected(markerRejection(
                    List.of(BuildContext.DIR_GLOBAL_DATA, BuildContext.DIR_TENANT_FAN_OUT), coordinate,
                    "'" + coordinate + "' combines @globalData with @tenantFanOut: one says the"
                        + " field's data is global and runs on the default source, the other unions"
                        + " it across every tenant. Remove one of the directives."));
            }
            if (fanMarked) {
                recordMarkerVerdict(BuildContext.DIR_TENANT_FAN_OUT, coordinate);
                return fanOutArmOf(site);
            }
            if (globalMarked) {
                recordMarkerVerdict(BuildContext.DIR_GLOBAL_DATA, coordinate);
                return globalDataArmOf(site);
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
                return Verdict.rejected(new ValidationError(
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
            }
            var members = site.members();
            var direct = directBinding(site);
            boolean connectionService = connectionService(site, anyGlobal);
            boolean needsTenant = needsTenant(site, reach);
            // The parent decides first, ahead of the field's own arguments: a parent that already
            // supplies a tenant, named by its row or handed down from above, is the tenant the
            // child runs on, and an argument that names the tenant column only filters inside it.
            // Otherwise an optional argument would route the field (failing when it is omitted),
            // and a batch whose parents span tenants would go to the one tenant it names. Roots
            // never reach these rungs: they sit in withoutContext and have no parent row.
            //
            // The parent row names the tenant that holds the child row, so it decides even when
            // an ancestor handed a tenant down. In a database-per-tenant deployment an inherited
            // tenant finds the child only when the parent row names that same tenant.
            var parentRow = parentRowBinding(site);
            if (parentRow != null) {
                return new Verdict.Arm(parentRow);
            }
            // A method call handed a connection-bound value (a DSLContext, or the $session handle
            // its connection's mount returned) runs on whichever connection it is handed, so which
            // connection serves it is semantics, not plumbing: under a tenant context the call runs
            // on the inherited tenant's connection. Decided ahead of the reach-derived Untenanted
            // arm, whose "touches no tables" reading is about graphitron's SQL only, a service's
            // own SQL being opaque. Not where the field's own reach is global: graphitron re-reads
            // that global return table on the same connection, and a global service runs on the
            // default source, so the field keeps the Untenanted.DefaultSource leaf below.
            if (!anyGlobal && bindsConnection(out) && tenantContextOf(coord.getTypeName())) {
                return new Verdict.Arm(new TenantBinding.Inherited(coord.getTypeName()));
            }
            // Only where the statement needs a tenant: a field touching no tenant table keeps its
            // Untenanted leaf below.
            if (needsTenant && tenantContextOf(coord.getTypeName())) {
                return new Verdict.Arm(new TenantBinding.Inherited(coord.getTypeName()));
            }
            switch (direct.reading()) {
                // A declined shape names the tenant column but cannot route on it. Each decline
                // carries its own detail rather than falling through to the generic
                // "nothing names the tenant" text, which would send an author looking for a
                // binding they already wrote. Only where the statement needs a tenant at all:
                // a field whose own SQL stays on the default source has nothing to route, so
                // the shape that could not route it is moot. The rejection is the field's alone:
                // every decline's fix lands in a slot, so the ancestor folds treat its subtree as
                // the fix leaves it (handsDownAsIfFixed).
                case Reading.Declined declined when needsTenant -> {
                    return rejectUnrouted(coordinate, reach, anyTenant, declined.declines(),
                        direct.evidence());
                }
                case Reading.Guaranteed guaranteed -> {
                    return new Verdict.Arm(new TenantBinding.ArgumentBound(guaranteed.slots()));
                }
                // Every slot is optional, so a request may leave them all out and name no tenant:
                // there is no correct routing for that request. The rejection is the field's
                // alone, as for a decline: the ancestor folds treat its subtree as either fix
                // leaves it (handsDownAsIfFixed). Where nothing in the field's own SQL needs a tenant it
                // falls through like an unbound field, and a tenant-scoped child below it then
                // finds no context and rejects on its own.
                case Reading.Conditional conditional when needsTenant -> {
                    return rejectUnrouted(coordinate, reach, anyTenant,
                        List.of(optionalOnlyDetail(site, coordinate, conditional.slots())),
                        direct.evidence());
                }
                default -> { }
            }
            if (hasKind(members, OperationMember.Kind.NODE_RESOLVE)) {
                // Node dispatch spans types, and routes the decoded id's tenant wherever some
                // node type it dispatches is tenant-scoped (a key missing the tenant column is
                // rejected once in classifyNodeDispatch, and read as routed once fixed). None
                // at all means node dispatch reads only global tables, which is a global read.
                return new Verdict.Arm(nodeDispatchRoutes()
                    ? TenantBinding.NodeIdBound.INSTANCE
                    : TenantBinding.Untenanted.GlobalRead.INSTANCE);
            }
            if (!needsTenant) {
                return new Verdict.Arm(untenantedLeaf(members));
            }
            if (connectionService) {
                // The service fix, not the generic one: whatever the reach, what routes the call
                // is a tenant in its arguments, or the author's statement that its data is global.
                return Verdict.rejected(new ValidationError(coordinate,
                    Rejection.unroutedServiceCall(coordinate, scopes.columnName(), List.of(),
                        direct.evidence()),
                    graphql.language.SourceLocation.EMPTY));
            }
            String detail = "no argument or input field maps to tenant column '"
                + scopes.columnName() + "', and no ancestor established a tenant context.";
            if (out instanceof ChildField.TableField inline
                    && parentRowTenantSlot(inline.parentCorrelation(), inline.joinPath()) != null) {
                detail += " Its foreign key lands on the tenant column, so the parent row names the"
                    + " tenant, but an inline reference runs inside its parent's statement on the"
                    + " parent's connection. Mark it @" + BuildContext.DIR_SPLIT_QUERY
                    + " to fetch each parent row's child from the tenant the row names.";
            }
            return Verdict.rejected(new ValidationError(
                coordinate,
                Rejection.noTenantBinding(
                    coordinate,
                    reach.stream().filter(this::tenantScoped).findFirst().orElseThrow().tableName(),
                    detail),
                graphql.language.SourceLocation.EMPTY));
        }

        /**
         * The rejection for a field whose arguments name the tenant column without routing it, one
         * per detail. Picks the family by reach: {@link Rejection#noTenantBinding} names the first
         * tenant-scoped table the field reaches, and where nothing in reach is tenant-scoped (a
         * connection-bound root {@code @service}) that opening would be false, so the service arm
         * carries the details instead.
         */
        private Verdict rejectUnrouted(String coordinate, List<TableRef> reach, boolean anyTenant,
                                       List<String> details, List<String> evidence) {
            if (!anyTenant) {
                return Verdict.rejected(new ValidationError(coordinate,
                    Rejection.unroutedServiceCall(coordinate, scopes.columnName(), details, evidence),
                    graphql.language.SourceLocation.EMPTY));
            }
            String tenantTable = reach.stream().filter(this::tenantScoped).findFirst()
                .orElseThrow().tableName();
            return new Verdict.Rejected(details.stream()
                .map(detail -> new ValidationError(
                    coordinate,
                    Rejection.noTenantBinding(coordinate, tenantTable, detail),
                    graphql.language.SourceLocation.EMPTY))
                .toList());
        }

        /**
         * The detail for a field whose tenant slots are all optional. The fan-out fix leads where
         * the field could carry the marker: with two optional slots, making one non-null breaks
         * every client that sends only the other.
         */
        private String optionalOnlyDetail(Site site, String coordinate,
                                          List<TenantBinding.BoundSlot> slots) {
            String names = slots.stream().map(TenantBinding.BoundSlot::slotName).distinct()
                .collect(java.util.stream.Collectors.joining(", "));
            String fix = acceptsFanOut(site)
                ? "Mark the field @" + BuildContext.DIR_TENANT_FAN_OUT + " to query every tenant the"
                    + " request may see when none is given, or make one of them non-null."
                : "Make one of them non-null.";
            return "'" + coordinate + "' takes its tenant only from optional values (" + names
                + "); a request that omits all of them names no tenant. " + fix;
        }

        /**
         * Whether the {@code @tenantFanOut} ladder would accept the marker on this field, asked of
         * the ladder itself rather than of a second list of its conditions. A nesting type's field
         * is turned away from both marker ladders before either runs, so it never qualifies.
         */
        private boolean acceptsFanOut(Site site) {
            return !site.nested() && fanOutArmOf(site) instanceof Verdict.Arm;
        }

        /**
         * Whether the field's own statement needs a tenant: {@code reach} (its
         * {@link #reachedTables}) holds a tenant-scoped table, or it is a
         * {@link #connectionService}. A function of the site alone, so the ancestor fold can ask it
         * before any arm is assigned.
         */
        private boolean needsTenant(Site site, List<TableRef> reach) {
            return reach.stream().anyMatch(this::tenantScoped)
                || connectionService(site, reach.stream().anyMatch(t -> !tenantScoped(t)));
        }

        /**
         * A root service handed a connection runs its own SQL on that connection, and that SQL is
         * opaque, so it needs a tenant whatever graphitron's own reach says. Not where the reach
         * holds a global table: graphitron re-reads that return on the same connection, and a
         * global service runs on the default source, so the structure decides the field.
         */
        private boolean connectionService(Site site, boolean anyGlobal) {
            return roots.contains(site.coord().getTypeName())
                && site.field() instanceof ServiceField && bindsConnection(site.field()) && !anyGlobal;
        }

        /**
         * Whether a field that needs a tenant hands its subtree one in both ancestor folds,
         * {@link #siteEstablishesContext} and {@link #siteDivinesTenant}, whatever its own verdict:
         * the field carries neither tenancy marker, its own statement {@link #needsTenant needs a
         * tenant}, that statement does not span tenant-scoped and global tables, its reading is not
         * {@link Reading.Guaranteed} (which both folds count on its own), and the
         * {@code @globalData} ladder would not accept the marker on it ({@link #acceptsGlobalData}).
         * {@link #run} computes the answer once per site into {@link #countedSites}, which both
         * folds and {@link #checkedArmOf} read.
         *
         * <p>Such a field ends in one of three verdicts in {@link #armOf}: it routes by its parent
         * row, it inherits the tenant its parent type already has on every path, or it rejects. A
         * rejected field generates nothing, so its subtree is judged as it will be once the
         * author applies a fix the rejection offers, and every fix it can be offered hands a
         * tenant down: a required or optional binding on the field or an ancestor, a
         * {@code @tenant} marker, a node-typed record, a {@code @splitQuery} hint toward the
         * parent row, or {@code @tenantFanOut} where {@link #acceptsFanOut} holds. A decline's own
         * fix lands in a slot, or removes a SET-side tenant field and leaves the WHERE slots or an
         * unbound reading whose rejection again offers only those fixes. The exclusions are the
         * rejections that offer something else:
         *
         * <ul>
         *   <li>A cross-scope statement offers no tenant fix at all; restructuring the field may
         *   leave it global.</li>
         *   <li>{@code @globalData} hands nothing down: a root connection service that reaches no
         *   table and names no tenant is offered it, and after taking it its subtree really has no
         *   tenant, so its children's errors are real now. Asked of the ladder rather than of the
         *   message, which also offers the marker where the ladder refuses it.</li>
         *   <li>A marked field takes its verdict from the marker's ladder, and a field that needs
         *   no tenant runs on the default source; neither subtree gets one.</li>
         * </ul>
         *
         * <p>A passing build classifies as it would without this predicate, by a fixed point.
         * Counting a site only ever turns an edge from passing a context on into establishing
         * one, so the types without a context can only shrink. In a passing build a counted site
         * whose parent type has no context cannot inherit, since both inherited rungs need
         * {@link #tenantContextOf}; {@link #checkedArmOf} holds it to the three verdicts, so it
         * ends {@link TenantBinding.ParentRowBound}, which {@link #parentRowBinding} already
         * establishes on, or rejected, which a passing build has none of. Every edge whose answer
         * changed therefore leaves a type that already had a context, {@link #closeForward} never
         * follows it from the unbound seeds, and the closure is unchanged. A rejection that
         * starts offering a fix outside the list above must revisit this predicate;
         * {@link #checkedArmOf} throws if a counted site ends in a fourth verdict.
         */
        private boolean handsDownAsIfFixed(Site site) {
            if (fanMarked(site.coord()) || globalMarked(site.coord())) {
                return false;
            }
            List<TableRef> reach = reachedTables(site.field());
            boolean crossScope = reach.stream().anyMatch(this::tenantScoped)
                && reach.stream().anyMatch(t -> !tenantScoped(t));
            return needsTenant(site, reach)
                && !crossScope
                && !(directBinding(site).reading() instanceof Reading.Guaranteed)
                && !acceptsGlobalData(site);
        }

        /**
         * Whether the {@code @globalData} ladder would accept the marker on this field, asked of
         * the ladder itself, as {@link #acceptsFanOut} asks its own. A nesting type's field is
         * turned away from both marker ladders before either runs, so it never qualifies.
         */
        private boolean acceptsGlobalData(Site site) {
            return !site.nested() && globalDataArmOf(site) instanceof Verdict.Arm;
        }

        /**
         * Whether the tables a classification-rejected edge's own statement would select from,
         * read off the SDL, hold a tenant-scoped one: from the edge's targets (interfaces and
         * unions already expanded), a {@link GraphitronType.TableBackedType} contributes its
         * table, a {@link GraphitronType.ConnectionType} or {@link GraphitronType.EdgeType} is
         * walked through along its own edges, and every other type contributes nothing and stops
         * the walk. The wrappers are the only pass-through types because their fields are never
         * classified on their own: their edges are the returning field's statement seen through
         * the wrapper. Every other type's fields are sites of their own once the build passes.
         * A type with no {@code types} entry stops the walk too; in a failing build a non-table
         * type reached only through a rejected field is never classified. Reads only the SDL
         * edges and the classified types, never ancestor context, so it runs before the folds.
         */
        private boolean sdlReachesTenantTable(FieldCoordinates edge) {
            return closeForward(edgeTargets.get(edge), e -> passesThrough(e.getTypeName())).stream()
                .anyMatch(typeName -> types.get(typeName) instanceof GraphitronType.TableBackedType tb
                    && tenantScoped(tb.table()));
        }

        private boolean passesThrough(String typeName) {
            return types.get(typeName) instanceof GraphitronType.ConnectionType
                || types.get(typeName) instanceof GraphitronType.EdgeType;
        }

        // ===== The parent-row binding =====

        /**
         * The first-hop slot through which a global parent row names the tenant of the child row
         * it references, or {@code null}. Matches when the correlation joins on column pairs
         * ({@link ParentCorrelation.OnFkSlots}, so the batch key is the first hop's parent-side
         * columns), the parent's rows live on the default source, every hop lands on a
         * tenant-scoped table (so the whole statement runs in one tenant database), and one
         * first-hop slot has the tenant column as its target side. A condition hop or a hop-0
         * filter names no column whose value equals the tenant by construction, and a tenant
         * column first paired on a later hop pairs with an intermediate table, not the parent
         * row; none of those match.
         *
         * <p>Reads only the field's own correlation and path plus the configured scopes, never
         * ancestor context, so the ancestor fold can call it before any arm is assigned. Serves
         * both the batched arm and the inline rejection's {@code @splitQuery} hint, so the two
         * cannot drift.
         */
        private JoinSlot.FkSlot parentRowTenantSlot(ParentCorrelation correlation,
                                                    List<JoinStep> joinPath) {
            if (!(correlation instanceof ParentCorrelation.OnFkSlots fk)) {
                return null;
            }
            TableRef parent = fk.parentKeyOwnerTable();
            if (parent == null || tenantScoped(parent)) {
                return null;
            }
            for (JoinStep step : joinPath) {
                if (!(step instanceof JoinStep.Hop hop)
                        || hop.targetTable() == null || !tenantScoped(hop.targetTable())) {
                    return null;
                }
            }
            return fk.slots().slots().stream()
                .filter(slot -> matchesTenantColumn(slot.targetSide()))
                .findFirst()
                .orElse(null);
        }

        /**
         * The {@link TenantBinding.ParentRowBound} arm for {@code site}, or {@code null}: a
         * table-sourced {@link ChildField.BatchedTableField} (its own statement on its own
         * connection) carrying no tenancy marker, whose correlation {@link #parentRowTenantSlot}
         * matches. Its own arguments do not exclude it: an argument naming the tenant column only
         * filters inside the tenant the parent row names. Shared by {@link #armOf} and the two edge
         * predicates, so the arm and the context it establishes derive from one test.
         */
        private TenantBinding.ParentRowBound parentRowBinding(Site site) {
            if (!(site.field() instanceof ChildField.BatchedTableField field)
                    || field.sourceShape() != SourceShape.Table
                    || fanMarked(site.coord()) || globalMarked(site.coord())) {
                return null;
            }
            var slot = parentRowTenantSlot(field.parentCorrelation(), field.joinPath());
            return slot == null ? null : mintParentRowBound(field, slot);
        }

        /**
         * The one mint of {@link TenantBinding.ParentRowBound}, projected off the field's
         * {@link ParentCorrelation.OnFkSlots}. Pins the invariant the fetcher emission depends
         * on: the slot is one of the first hop's own slots, and its source side is a batch key
         * column, so the key extraction already reads the tenant value.
         */
        private static TenantBinding.ParentRowBound mintParentRowBound(ChildField.BatchedTableField field,
                                                                       JoinSlot.FkSlot slot) {
            var fk = (ParentCorrelation.OnFkSlots) field.parentCorrelation();
            boolean firstHopSlot = fk.slots().slots().stream().anyMatch(s -> s == slot);
            if (!firstHopSlot || !field.sourceKey().columns().contains(slot.sourceSide())) {
                throw new IllegalStateException(
                    "Graphitron generator bug (tenant routing): the parent-row tenant slot of '"
                    + field.parentTypeName() + "." + field.name() + "' must be one of its first"
                    + " hop's slots with its source side among the batch key columns "
                    + field.sourceKey().columns() + "; got " + slot);
            }
            return new TenantBinding.ParentRowBound(fk.parentKeyOwnerTable(), slot);
        }

        // ===== The @globalData arm =====

        /** Whether the coordinate's SDL field definition carries the {@code @globalData} marker. */
        private boolean globalMarked(FieldCoordinates coord) {
            GraphQLFieldDefinition def = fieldDefinition(coord);
            return def != null && def.hasAppliedDirective(BuildContext.DIR_GLOBAL_DATA);
        }

        /**
         * The {@code @globalData} rejection ladder, closed and validate-time: a marked field
         * either survives every rung and classifies {@link TenantBinding.Untenanted.DefaultSource}, or rejects
         * with a marker-specific message; the first rung that applies wins. The marker is
         * accepted exactly where an unrouted connection-binding root service would otherwise
         * reject. The reach and argument rungs refuse it wherever the build can see tenant data,
         * so what it vouches for is only the service's own SQL, the one thing the build cannot
         * see. Computes reach itself, so a cross-scope field gets a rung's text rather than the
         * generic cross-scope rejection.
         */
        private Verdict globalDataArmOf(Site site) {
            FieldCoordinates coord = site.coord();
            OutputField out = site.field();
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
            var direct = directBinding(site);
            if (!(direct.reading() instanceof Reading.Unbound)) {
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
                    + "', or mark the scalar argument or input field that holds the tenant with"
                    + " @tenant, and remove the directive.");
            }
            return new Verdict.Arm(TenantBinding.Untenanted.DefaultSource.INSTANCE);
        }

        private static Verdict rejectGlobalData(String coordinate, String reason) {
            return Verdict.rejected(
                markerRejection(List.of(BuildContext.DIR_GLOBAL_DATA), coordinate, reason));
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
         * would misreport as "nothing to fan out over". Optional tenant slots survive and become
         * the arm's narrowing; a required one rejects, since the field would never fan.
         *
         * <p>Free of side effects (the marker verdict is recorded in {@link #armOf}), so the
         * optional-slot rejection can ask it whether the marker would be accepted on an unmarked
         * field.
         */
        private Verdict fanOutArmOf(Site site) {
            FieldCoordinates coord = site.coord();
            OutputField out = site.field();
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
            if (hasKind(site.members(), OperationMember.Kind.LOOKUP)) {
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
            // A declined shape is judged on the slots beside it: it stays a row filter that neither
            // routes nor narrows, which is safe because narrowing changes which tenants are asked,
            // never which rows answer.
            List<TenantBinding.BoundSlot> slots = switch (directBinding(site).reading()) {
                case Reading.Unbound ignored -> List.of();
                case Reading.Declined declined -> declined.slots();
                case Reading.Guaranteed guaranteed -> guaranteed.slots();
                case Reading.Conditional conditional -> conditional.slots();
            };
            var required = slots.stream().filter(TenantBinding.BoundSlot::required).toList();
            if (!required.isEmpty()) {
                return rejectFanOut(coordinate, List.of(BuildContext.DIR_TENANT_FAN_OUT),
                    "'" + coordinate + "' already binds the tenant column through the non-null "
                        + required.stream().map(TenantBinding.BoundSlot::slotName).distinct()
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
            return new Verdict.Arm(new TenantBinding.FanOut(slots));
        }

        /**
         * True when some path reaching {@code typeName} crosses a tenant-divining edge (a
         * guaranteed direct binding, a field {@link #handsDownAsIfFixed} counts, routable node
         * dispatch, or a parent-row binding) or starts at a type a dispatch entry routes. The
         * any-path mirror of
         * {@link #tenantContextOf}'s every-path fold, matching the nested-marker rung's posture:
         * a marked field rejects if fanning would contradict a divined tenant on <em>any</em>
         * path (rejections are conservative), while the {@link TenantBinding.Inherited} verdict
         * keeps demanding every-path certainty. A path through a cycle counts like any other.
         */
        private boolean anyBoundAncestor(String typeName) {
            return belowBoundEdge.contains(typeName);
        }

        /**
         * Whether the edge's own field divines a tenant: the direct-binding, routable
         * node-dispatch and parent-row facts behind {@link #anyBoundAncestor}. True when any site
         * of the edge divines, since the fact it feeds is an any-path rejection fact. A
         * classification-rejected edge in {@link #countedRejectedEdges} divines too, so every type
         * with a context still lies below a divining edge, a fanned edge, or a routed entry.
         */
        private boolean edgeDivinesTenant(FieldCoordinates edge) {
            return countedRejectedEdges.contains(edge)
                || sitesAt(edge).stream().anyMatch(this::siteDivinesTenant);
        }

        private boolean siteDivinesTenant(Site site) {
            // The same posture as siteEstablishesContext: a @tenantFanOut field below a rejected
            // field that needs a tenant rejects now, as it would after any fix. A counted site
            // that inherits divines nothing new: every type with a context already lies below a
            // divining edge, a fanned edge, or a routed dispatch entry (the seeds in
            // foldAncestorContexts).
            if (directBinding(site).reading() instanceof Reading.Guaranteed
                    || countedSites.contains(site)) {
                return true;
            }
            if (hasKind(site.members(), OperationMember.Kind.NODE_RESOLVE)) {
                return nodeDispatchRoutes();
            }
            return parentRowBinding(site) != null;
        }

        private static Verdict rejectFanOut(String coordinate, List<String> directives, String reason) {
            return Verdict.rejected(markerRejection(directives, coordinate, reason));
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
         * rejects, and generates nothing; what its subtree is judged against is the fix: every
         * decline's fix lands in a slot, so a declined field that needs a tenant hands its
         * subtree one as the fix would ({@link Fold#handsDownAsIfFixed}), as an optional-only or
         * unbound one does, and it rejects without its children repeating the error.
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

            /**
             * The closed reading every consumer switches on, so none decides afresh what an
             * optional-only binding means. Declines outrank slots; among slots, one the client
             * cannot omit is what guarantees every request a tenant.
             */
            Reading reading() {
                if (!declines.isEmpty()) {
                    return new Reading.Declined(declines, slots);
                }
                if (slots.isEmpty()) {
                    return Reading.Unbound.INSTANCE;
                }
                return slots.stream().anyMatch(TenantBinding.BoundSlot::required)
                    ? new Reading.Guaranteed(slots)
                    : new Reading.Conditional(slots);
            }
        }

        /** What one coordinate's direct binding says about its tenant; see {@link DirectBinding#reading()}. */
        private sealed interface Reading {

            /** No slots and no declines: the arguments say nothing about the tenant. */
            record Unbound() implements Reading {
                static final Unbound INSTANCE = new Unbound();
            }

            /** A shape names the tenant column but cannot route; the slots beside it are kept. */
            record Declined(List<String> declines, List<TenantBinding.BoundSlot> slots) implements Reading {}

            /** No declines and at least one required slot: every request names a tenant. */
            record Guaranteed(List<TenantBinding.BoundSlot> slots) implements Reading {}

            /** No declines, and every slot optional: a request may name no tenant at all. */
            record Conditional(List<TenantBinding.BoundSlot> slots) implements Reading {}
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

        /**
         * Accumulates one coordinate's slots, declines and tenant evidence, deduping slots by name.
         * Every mint site funnels through {@link #add}, which is where a slot's presence is read off
         * the coordinate's own field definition, so no mint site decides it on its own.
         */
        private static final class SlotCollector {
            private final GraphQLFieldDefinition definition;
            private final List<TenantBinding.BoundSlot> slots = new ArrayList<>();
            private final List<String> declines = new ArrayList<>();
            private final List<String> evidence = new ArrayList<>();
            private final Set<String> seenNames = new HashSet<>();

            SlotCollector(GraphQLFieldDefinition definition) {
                this.definition = definition;
            }

            void add(String slotName, SlotAccess access) {
                switch (access) {
                    case SlotAccess.Resolved r -> {
                        if (seenNames.add(slotName)) {
                            slots.add(new TenantBinding.BoundSlot(slotName, r.read(), r.projection(),
                                slotRequired(definition, slotName, r.read())));
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
        private DirectBinding directBinding(Site site) {
            var collector = new SlotCollector(fieldDefinition(site.coord()));
            for (OperationMember member : site.members()) {
                switch (member) {
                    case OperationMember.Condition c ->
                        collectFromColumnBindings(columnBindings.slotsAt(site.coord(), c.table()), collector);
                    case OperationMember.Lookup l -> collectFromLookup(l.lookupMapping(), collector);
                    case OperationMember.Write.Insert i -> collectFromTableInput(i.input(), collector);
                    case OperationMember.Write.Upsert u -> collectFromTableInput(u.input(), collector);
                    // The WHERE-keyed verbs (UPDATE, DELETE) share one body over
                    // Dml.whereKeyColumns(), so a third WHERE-bearing write arm is covered on
                    // arrival rather than falling silently to the no-op default below.
                    case OperationMember.Write.Dml dml -> collectFromWhereKeys(dml, collector);
                    case OperationMember.ServiceCall sc -> collectFromServiceCall(site, sc, collector);
                    default -> { }
                }
            }
            // A @routine write contributes nothing here: its only member is the routine call,
            // which carries no filter, lookup or input surface, so it mints no slot at all.
            return collector.result();
        }

        /**
         * Which {@link TenantBinding.Untenanted} leaf a field touching only global tables takes,
         * read off its own operation members: a statement that writes or calls a service runs on
         * the default source in every request, since the build cannot see what that SQL touches;
         * anything else is graphitron's own read, which may follow the request's default tenant.
         * {@code REENTRY} is deliberately not in the test: on a producing coordinate it rides
         * beside the {@code WRITE} or {@code SERVICE_CALL} that already decides the leaf, and on
         * a record-sourced child it marks a payload re-projection, which is a global read.
         */
        private static TenantBinding.Untenanted untenantedLeaf(List<OperationMember> members) {
            return hasKind(members, OperationMember.Kind.WRITE)
                    || hasKind(members, OperationMember.Kind.SERVICE_CALL)
                ? TenantBinding.Untenanted.DefaultSource.INSTANCE
                : TenantBinding.Untenanted.GlobalRead.INSTANCE;
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
                        collector.add(slot.slotName(), accessOf(slot.extraction(),
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
                            collector.add(s.argName(), accessOf(s.extraction(),
                                TenantBinding.SlotRead.TopLevelArg.INSTANCE, 0));
                        }
                    }
                    case LookupMapping.ColumnMapping.LookupArg.MapInput mi -> {
                        for (InputColumnBinding.MapBinding b : mi.bindings()) {
                            if (matchesTenantColumn(b.targetColumn())) {
                                collector.add(b.fieldName(), accessOf(b.extraction(),
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
                collector.add(key.sdlFieldName(), accessOf(key.extraction(),
                    new TenantBinding.SlotRead.NestedInput(dml.outerArgName(),
                        List.of(key.sdlFieldName())),
                    key.decodeSlot()));
            }
            if (dml instanceof OperationMember.Write.Update update) {
                for (var set : update.updateRows().setColumns()) {
                    if (!matchesTenantColumn(set.targetColumn())) continue;
                    if (update.updateRows().isAgreementChecked(set)) {
                        collector.add(set.sdlFieldName(), accessOf(set.extraction(),
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
        private void collectFromServiceCall(Site site, OperationMember.ServiceCall member,
                                            SlotCollector collector) {
            if (!(member.call() instanceof ServiceCallCarrier.StructuredCall structured)) {
                return;
            }
            if (reachedTables(site.field()).stream().anyMatch(t -> !tenantScoped(t))) {
                return;
            }
            // Constructor rounds carry no argument-sourced entry (the walker refuses one there).
            for (MappingEntry entry : structured.call().methodArgs()) {
                if (entry instanceof MappingEntry.FromArg arg) {
                    collectFromValueShape(arg.shape(), site.coord(), collector);
                }
            }
        }

        private void collectFromValueShape(ValueShape shape, FieldCoordinates use,
                                           SlotCollector collector) {
            switch (shape) {
                case ValueShape.ListOf list -> collectFromValueShape(list.elementShape(), use, collector);
                case ValueShape.RecordInput record -> {
                    for (var field : record.fields()) collectFromValueShape(field.shape(), use, collector);
                }
                case ValueShape.JavaBeanInput bean -> {
                    for (var field : bean.fields()) collectFromValueShape(field.shape(), use, collector);
                }
                case ValueShape.JooqRecordInput jr -> collectFromJooqRecord(jr, use, collector);
                case ValueShape.Scalar scalar -> {
                    var path = pathOf(scalar.sdlPath());
                    if (scalar.leafTransform() instanceof CallSiteExtraction.Direct
                            && tenantMarked.containsKey(scalar.definition())) {
                        collectFromMarkedScalar(scalar.definition(), path, use, collector);
                    } else {
                        collectFromServiceLeaf(scalar.leafTransform(), path, collector);
                    }
                }
            }
        }

        /**
         * A scalar the author marked {@code @tenant}, read as the client sent it: the read's value
         * is the tenant, so the slot projects {@link TenantBinding.SlotProjection.Raw}. The
         * marker is the author's statement that the opaque service uses the value as its tenant;
         * what the build checks is the type, since the generated agreement fold compares
         * candidates with {@code equals} before anything is coerced, so a value of any other type
         * than the key's would disagree with a decoded sibling naming the same tenant. A mismatch
         * declines, so nothing routes on a value the fold might misjudge. Only a
         * {@link CallSiteExtraction.Direct} leaf reaches here; a marked value read through any
         * other leaf mints what that leaf mints and is left for the marker sweep.
         */
        private void collectFromMarkedScalar(String definition, List<String> path,
                                             FieldCoordinates use, SlotCollector collector) {
            var marked = new TenantMarkerUse(use, definition);
            String typeMismatch = tenantKeyTypeMismatch(definition);
            if (typeMismatch != null) {
                mistypedTenantMarkers.put(marked, typeMismatch);
                collector.decline("'" + definition + "' is marked @tenant, but " + typeMismatch);
                return;
            }
            consumedTenantMarkers.add(marked);
            collector.add(slotNameOf(path),
                new SlotAccess.Resolved(readOf(path), TenantBinding.SlotProjection.Raw.INSTANCE));
        }

        /**
         * The type rung: {@code null} when the marked value arrives as the tenant key's Java type,
         * otherwise the decline's reason, phrased to follow "but". The wire type is the one forward mapping graphql-java's
         * argument coercion follows ({@link ScalarTypeResolver#coercionOutputType}), read over the
         * definition's own scalar (the element type for a list), not the Java member's declared
         * type, which the wire-coercion check lets through unjudged for a scalar it does not know.
         */
        private String tenantKeyTypeMismatch(String definition) {
            var named = GraphQLTypeUtil.unwrapAll(tenantMarked.get(definition).getType());
            var wire = ScalarTypeResolver.coercionOutputType(named.getName(), types.values());
            var key = scopes.tenantType() == null ? null
                : scopes.tenantType().isPrimitive() ? scopes.tenantType().box() : scopes.tenantType();
            if (wire != null && wire.equals(key)) {
                return null;
            }
            String arrives = wire == null
                ? "arrives as a Java type graphitron cannot determine"
                : "arrives as " + wire;
            return "its scalar '" + named.getName() + "' " + arrives + ", not the tenant key type " + key + " of tenant column '"
                + scopes.columnName() + "'. Graphitron does not convert a marked value, so it must"
                + " arrive as the key's own type: declare it with a scalar that does.";
        }

        /**
         * Records every {@code @tenant} application inside the input a jOOQ record parameter
         * binds, at {@code base}, as bound into a record: a field there names a column, and the
         * record field bound to the tenant column already routes the call, so the marker decides
         * nothing and the sweep says which binding to write instead.
         */
        private void declineMarkersInJooqRecord(FieldCoordinates use, List<String> base) {
            if (tenantMarked.isEmpty()) return;
            var argument = fieldDefinition(use) == null ? null
                : fieldDefinition(use).getArgument(base.get(0));
            if (argument == null) return;
            var type = GraphQLTypeUtil.unwrapAll(argument.getType());
            for (String segment : base.subList(1, base.size())) {
                if (!(type instanceof GraphQLInputObjectType input) || input.getField(segment) == null) {
                    return;
                }
                type = GraphQLTypeUtil.unwrapAll(input.getField(segment).getType());
            }
            if (type instanceof GraphQLInputObjectType input) {
                forEachInputField(input, new HashSet<>(), definition -> {
                    if (tenantMarked.containsKey(definition)) {
                        jooqBoundTenantMarkers.add(new TenantMarkerUse(use, definition));
                    }
                });
            }
        }

        /**
         * A jOOQ record parameter: a column binding on the tenant column reads the tenant off the
         * wire as it is, and a {@code @nodeId} decode whose target's key includes the tenant column
         * reads it off the decoded key at that position (see {@link #decodedSlotAccess}). Both
         * paths are relative to the record's own input, so they extend the parameter's argument
         * path. A tenant-scoped table's record that binds neither is tenant evidence.
         */
        private void collectFromJooqRecord(ValueShape.JooqRecordInput jr, FieldCoordinates use,
                                           SlotCollector collector) {
            List<String> base = pathOf(jr.sdlPath());
            declineMarkersInJooqRecord(use, base);
            boolean bound = false;
            for (var binding : jr.carrier().columnBindings()) {
                if (!matchesTenantColumn(binding.column())) continue;
                for (List<String> path : binding.paths()) {
                    var full = concat(base, path);
                    bound = true;
                    collector.add(slotNameOf(full), new SlotAccess.Resolved(
                        readOf(full), TenantBinding.SlotProjection.Raw.INSTANCE));
                }
            }
            for (var keyDecode : jr.carrier().keyDecodes()) {
                // Read off the target's own key tuple rather than the record's target columns: a
                // reference decode lands the key on foreign-key columns that need not carry the
                // tenant column's name, and the tuple is what the decode projects from.
                var full = concat(base, keyDecode.path());
                var access = decodedSlotAccess(keyDecode.typeName(), keyDecode.keyColumns(),
                    readOf(full), slotNameOf(full));
                if (access.isEmpty()) continue;
                bound = true;
                collector.add(slotNameOf(full), access.get());
            }
            if (!bound && tenantScoped(jr.carrier().table())) {
                collector.evidence(recordNameOf(jr.carrier().table()) + " at " + slotNameOf(base));
            }
        }

        /**
         * One service leaf. A record decode of one target type and a key decode read the tenant off
         * the decoded key at the tenant column's position; a polymorphic record decode declines,
         * since its candidates decode the same id with their own key columns; every other leaf
         * carries no column and mints nothing. A decode of a tenant-scoped type whose key misses
         * the tenant column is tenant evidence.
         */
        private void collectFromServiceLeaf(CallSiteExtraction leaf, List<String> path,
                                            SlotCollector collector) {
            switch (leaf) {
                case CallSiteExtraction.NodeIdDecodeRecord record -> {
                    var access = decodedSlotAccess(record.typeName(), record.keyColumns(),
                        readOf(path), slotNameOf(path));
                    if (access.isEmpty()) {
                        if (tenantScoped(record.table())) {
                            collector.evidence(recordNameOf(record.table()) + " at " + slotNameOf(path));
                        }
                        return;
                    }
                    collector.add(slotNameOf(path), access.get());
                }
                case CallSiteExtraction.NodeIdDecodeKeys keys -> {
                    var shape = keys.decodeMethod().outputColumnShape();
                    int slot = tenantIndex(shape);
                    if (slot < 0) {
                        // The decode carries the table it reads, so the evidence needs no type
                        // lookup; the target may be a node type or a @table type decoded from
                        // catalog node metadata.
                        var decode = keys.decodeMethod();
                        if (tenantScoped(decode.table())) {
                            collector.evidence(decode.targetTypeName() + " id at " + slotNameOf(path));
                        }
                        return;
                    }
                    collector.add(slotNameOf(path), accessOf(keys, readOf(path), slot));
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
         * Where a record-shaped {@code @nodeId(typeName:)} decode reads the tenant, or empty when
         * the target's key tuple {@code keyColumns} has no tenant column. A target that is a node
         * type reads the tenant off its {@code decode<TypeName>} helper at the tenant column's
         * position. A {@code @table} type that is not a node type declines when its key carries the
         * tenant column: the record carriers hold no {@link HelperRef.Decode}, so a record decode of
         * such a type does not route the statement on its tenant. That is a scope decision rather
         * than a missing helper; a leaf naming the same type does route, through the catalog decode
         * it carries. Looked up by {@code typeName}, not by type id: two
         * types can back one table, and a {@code @table}-only type's catalog type id can coincide
         * with a node type's.
         */
        private Optional<SlotAccess> decodedSlotAccess(String typeName, List<ColumnRef> keyColumns,
                                                       TenantBinding.SlotRead read, String slotName) {
            int slot = tenantIndex(keyColumns);
            if (slot < 0) {
                return Optional.empty();
            }
            if (types.get(typeName) instanceof GraphitronType.NodeType nt) {
                return Optional.of(new SlotAccess.Resolved(
                    read, new TenantBinding.SlotProjection.DecodedKeySlot(nt.decodeMethod(), slot)));
            }
            return Optional.of(new SlotAccess.Declined("the tenant is reached through @nodeId(typeName: '"
                + typeName + "') at " + slotName + ", whose key carries tenant column '"
                + scopes.columnName() + "', but '" + typeName + "' is not a node type, and a record"
                + " decode of a @table-only type does not route the statement on its tenant. Make '"
                + typeName + "' a node type, or bind the tenant through a field mapping to tenant"
                + " column '" + scopes.columnName() + "'."));
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
                        collector.add(b.fieldName(), accessOf(b.extraction(),
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
                collector.add(name,
                    accessOf(extraction, new TenantBinding.SlotRead.NestedInput(argName, keys), i));
            }
        }

        // ===== Dispatch entries =====

        /**
         * How the batched dispatch surfaces, {@code _entities} and {@code Query.node}, enter one
         * type. A type with no resolvable alternative ({@link EntityResolution#resolvable}), or no
         * entity entry at all, is entered by neither and has no fact.
         */
        private sealed interface DispatchFact {

            /**
             * The entry routes each representation by its alternative's tenant source: the tenant
             * column of its key, or the request's default tenant for a {@code @defaultTenant} type.
             */
            record Routed(TenantBinding.EntityRepBound binding) implements DispatchFact {}

            /**
             * Tenant-scoped, and rejected because a resolvable alternative or the node key lacks
             * the tenant column. Read as routed: every fix that keeps the entry resolvable routes
             * it, and the one that does not removes the entry, leaving the type to its field
             * edges. Only a failing build carries one.
             */
            enum RoutedOnceFixed implements DispatchFact { INSTANCE }

            /** A global entity or node type, served from the default source. */
            enum ServedUntenanted implements DispatchFact { INSTANCE }

            /** Whether the entry hands its type a tenant, now or once its rejection is fixed. */
            default boolean handsDownTenant() {
                return !(this instanceof ServedUntenanted);
            }
        }

        private DispatchFact dispatchFact(String typeName) {
            return dispatchFacts.get(typeName);
        }

        /**
         * Whether node dispatch routes the decoded id's tenant: some node type it dispatches is
         * tenant-scoped, routed now or once fixed. The one question behind node dispatch's arm in
         * {@link #armOf} and its divining answer in {@link #siteDivinesTenant}.
         */
        private boolean nodeDispatchRoutes() {
            return dispatchFacts.entrySet().stream().anyMatch(e ->
                types.get(e.getKey()) instanceof GraphitronType.NodeType
                    && e.getValue().handsDownTenant());
        }

        /** The {@link DispatchFact.Routed} projection: the map the emitted dispatcher stamps rows from. */
        private Map<String, TenantBinding.EntityRepBound> routedEntities() {
            var routed = new LinkedHashMap<String, TenantBinding.EntityRepBound>();
            dispatchFacts.forEach((typeName, fact) -> {
                if (fact instanceof DispatchFact.Routed r) {
                    routed.put(typeName, r.binding());
                }
            });
            return routed;
        }

        private boolean resolvableEntity(String typeName) {
            var resolution = entitiesByType.get(typeName);
            return resolution != null && resolution.resolvable();
        }

        /**
         * Rejects every tenant-scoped node type whose key does not embed the tenant column, and
         * records it {@link DispatchFact.RoutedOnceFixed}. Runs ahead of
         * {@link #classifyEntityDispatch}, which reads that fact to leave the type's
         * {@link KeyAlternative.NodeId} alternative to this text.
         */
        private void classifyNodeDispatch() {
            for (GraphitronType type : types.values()) {
                if (!(type instanceof GraphitronType.NodeType nt)) continue;
                TableRef table = nt.table();
                if (!tenantScoped(table)) continue;
                List<ColumnRef> keyColumns = nt.nodeKeyColumns().isEmpty()
                    ? table.primaryKeyColumns()
                    : nt.nodeKeyColumns();
                if (tenantIndex(keyColumns) >= 0) continue;
                rejections.add(new ValidationError(
                    nt.name(),
                    Rejection.noTenantBinding(
                        nt.name(), table.tableName(),
                        "its node id key does not embed tenant column '"
                            + scopes.columnName() + "', so a decoded id cannot name the"
                            + " tenant to fetch from. Add tenant column '" + scopes.columnName()
                            + "' to @node(keyColumns:)."),
                    graphql.language.SourceLocation.EMPTY));
                if (resolvableEntity(nt.name())) {
                    dispatchFacts.put(nt.name(), DispatchFact.RoutedOnceFixed.INSTANCE);
                }
            }
        }

        /**
         * Decides every resolvable entity's {@link DispatchFact}: global, routed by the tenant
         * source of every resolvable alternative, or rejected and routed once fixed. A
         * {@link KeyAlternative.NodeId} alternative of a node type
         * {@link #classifyNodeDispatch} already rejected is not rejected twice. A
         * {@link KeyAlternative.Direct} alternative lacking the tenant column routes by the
         * request's default tenant when the type is marked {@code @defaultTenant} and
         * {@link #defaultTenantVerdict} accepts the marker; that is still {@link DispatchFact.Routed},
         * since the entry hands its subtree a tenant either way.
         */
        private void classifyEntityDispatch() {
            for (var entry : entitiesByType.entrySet()) {
                String typeName = entry.getKey();
                EntityResolution res = entry.getValue();
                if (!res.resolvable()) continue;
                boolean byDefault = defaultTenantVerdict(typeName, res);
                if (!tenantScoped(res.table())) {
                    dispatchFacts.put(typeName, DispatchFact.ServedUntenanted.INSTANCE);
                    continue;
                }
                boolean nodeRejected = dispatchFact(typeName) instanceof DispatchFact.RoutedOnceFixed;
                var alternatives = new ArrayList<TenantBinding.EntityRepBound.AlternativeSlot>();
                boolean routable = true;
                var keyAlternatives = res.alternatives();
                for (int i = 0; i < keyAlternatives.size(); i++) {
                    var alt = keyAlternatives.get(i);
                    if (!alt.resolvable()) continue;
                    int position = tenantIndex(alt.columns());
                    if (position >= 0) {
                        alternatives.add(new TenantBinding.EntityRepBound.AlternativeSlot(i,
                            new TenantBinding.EntityRepBound.TenantSource.KeyColumn(position)));
                        continue;
                    }
                    if (byDefault && alt instanceof KeyAlternative.Direct) {
                        alternatives.add(new TenantBinding.EntityRepBound.AlternativeSlot(i,
                            new TenantBinding.EntityRepBound.TenantSource.RequestDefault(
                                tenantColumnOf(res.table()))));
                        continue;
                    }
                    routable = false;
                    boolean nodeKey = alt instanceof KeyAlternative.NodeId;
                    if (nodeKey && nodeRejected) continue;
                    rejections.add(new ValidationError(
                        typeName,
                        Rejection.noTenantBinding(
                            typeName, res.table().tableName(),
                            "its federation key alternative #" + i + " does not carry"
                                + " tenant column '" + scopes.columnName()
                                + "', so a representation cannot name the tenant to"
                                + " fetch from. " + (nodeKey
                                    ? "Add tenant column '" + scopes.columnName()
                                        + "' to @node(keyColumns:)."
                                    : "Add a field mapped to tenant column '" + scopes.columnName()
                                        + "' to the key's fields, mark the alternative"
                                        + " resolvable: false, or mark the type @defaultTenant"
                                        + " to resolve it on the request's default tenant.")),
                        graphql.language.SourceLocation.EMPTY));
                }
                dispatchFacts.put(typeName, routable
                    ? new DispatchFact.Routed(new TenantBinding.EntityRepBound(alternatives))
                    : DispatchFact.RoutedOnceFixed.INSTANCE);
            }
        }

        // ===== The @defaultTenant ladder =====

        /** Whether the type's SDL declaration carries the {@code @defaultTenant} marker. */
        private boolean defaultTenantMarked(String typeName) {
            return sdl.getType(typeName) instanceof GraphQLObjectType object
                && object.hasAppliedDirective(BuildContext.DIR_DEFAULT_TENANT);
        }

        /**
         * The {@code @defaultTenant} ladder for one resolvable entity: whether its tenant-less
         * {@link KeyAlternative.Direct} alternatives route by the request's default tenant. False
         * for an unmarked type. A marked type records its verdict and either is accepted or
         * rejects at the first rung that applies, and a rejected type classifies as if unmarked:
         * <ol>
         *   <li>its table has no tenant column, so {@code _entities} already reads it from the
         *       request's default tenant;</li>
         *   <li>no resolvable {@code Direct} alternative lacks the tenant column, so every key the
         *       marker could apply to names its tenant. A node id alternative is not one: the
         *       marker never routes node ids, so a type whose only tenant-less key is its node id
         *       lands here and keeps {@link #classifyNodeDispatch}'s rejection.</li>
         * </ol>
         * The rungs mirror the "does not carry tenant column" fix text, which offers the marker
         * exactly where this ladder accepts it.
         */
        private boolean defaultTenantVerdict(String typeName, EntityResolution res) {
            if (!defaultTenantMarked(typeName)) {
                return false;
            }
            recordMarkerVerdict(BuildContext.DIR_DEFAULT_TENANT, typeName);
            String reason;
            if (!tenantScoped(res.table())) {
                reason = "its table '" + res.table().tableName() + "' has no tenant column '"
                    + scopes.columnName() + "', and _entities already reads global types from the"
                    + " request's default tenant. Remove the directive.";
            } else if (res.alternatives().stream().noneMatch(alt -> alt.resolvable()
                    && alt instanceof KeyAlternative.Direct
                    && tenantIndex(alt.columns()) < 0)) {
                reason = "every key names its tenant: no resolvable @key lacks tenant column '"
                    + scopes.columnName() + "', so each representation routes by its own value"
                    + " (the marker does not apply to node ids, which route by the tenant inside"
                    + " the id). Remove the directive.";
            } else {
                return true;
            }
            rejections.add(markerRejection(List.of(BuildContext.DIR_DEFAULT_TENANT), typeName,
                "'" + typeName + "' declares @defaultTenant, but " + reason));
            return false;
        }

        /** The tenant column of a tenant-scoped table, as the catalog resolves it. */
        private ColumnRef tenantColumnOf(TableRef table) {
            return table.column(scopes.columnName()).orElseThrow(() -> new IllegalStateException(
                "Generator bug: table '" + table.tableName() + "' was judged tenant-scoped but has"
                    + " no tenant column '" + scopes.columnName() + "'."));
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
         * "No context" propagates from the operation roots, the types a dispatch surface enters
         * with no tenant, and the types nothing hands a tenant, along every edge that does not
         * establish one. That is the greatest fixed point the every-path property wants: a cycle
         * entered only through establishing edges is never reached, while an unbound entry
         * reaching any member, by a field edge or by dispatch, reaches the whole cycle through
         * its internal edges. The any-path facts close from the targets of their edges along
         * every edge; "below a divining edge" also closes from the types a dispatch entry routes,
         * now or once fixed, since a routed entity row has a fixed tenant just as a divining
         * field's rows do. With that seed every type with a context lies below a divining edge,
         * a fanned edge, or a routed entry, which is what lets {@link #siteDivinesTenant} count a
         * {@link #handsDownAsIfFixed} site without asking whether it inherits. A
         * classification-rejected edge has no site; one in {@link #countedRejectedEdges} both
         * establishes and divines, so it reports its own error alone rather than voiding its
         * subtree's context.
         */
        private void foldAncestorContexts() {
            var unbound = new HashSet<String>(roots);
            var routedEntries = new HashSet<String>();
            for (String typeName : domain) {
                if (unboundDispatchEntry(typeName)
                        || (!reachingEdges.containsKey(typeName) && !routableDispatchSurface(typeName))) {
                    unbound.add(typeName);
                }
                if (routableDispatchSurface(typeName)) {
                    routedEntries.add(typeName);
                }
            }
            withoutContext = closeForward(unbound, edge -> !edgeEstablishesContext(edge));
            var divined = targetsOfEdges(this::edgeDivinesTenant);
            divined.addAll(routedEntries);
            belowBoundEdge = closeForward(divined, edge -> true);
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
         * uncovered shape: a root, a type outside the domain, a type a dispatch surface enters
         * with no tenant and whatever it reaches, and a type nothing hands a tenant all answer
         * {@code false}.
         */
        private boolean tenantContextOf(String typeName) {
            return domain.contains(typeName) && !withoutContext.contains(typeName);
        }

        /**
         * Batched dispatch surfaces reach the type outside the field-edge graph, whatever else
         * reaches it, but only when this subgraph resolves it: an entity with no resolvable
         * alternative is entered by neither {@code _entities} nor {@code Query.node}, which share
         * one dispatch core, and has no {@link DispatchFact}. A surface that does enter hands the
         * type no tenant only when it serves it from the default source,
         * {@link DispatchFact.ServedUntenanted}: every global entity, node types among them. A
         * tenant-scoped entry whose key lacks the tenant column is rejected on its own and read
         * as routed, so it voids no context below it.
         */
        private boolean unboundDispatchEntry(String typeName) {
            return dispatchFact(typeName) instanceof DispatchFact.ServedUntenanted;
        }

        /**
         * A dispatch surface that hands its type a tenant: {@link DispatchFact.Routed}, node types
         * whose key embeds the tenant column included, or {@link DispatchFact.RoutedOnceFixed},
         * which only a failing build carries. {@link DispatchFact.Routed} is what
         * {@link #routedEntities} projects into the map the dispatcher routes and stamps each
         * resolved row from
         * ({@link no.sikt.graphitron.rewrite.generators.util.EntityFetcherDispatchClassGenerator}),
         * so in every build that generates, "the classifier says this surface hands a tenant
         * down" and "the emitted dispatcher stamps this surface's rows" are one fact.
         */
        private boolean routableDispatchSurface(String typeName) {
            var fact = dispatchFact(typeName);
            return fact != null && fact.handsDownTenant();
        }

        /**
         * Whether the edge itself hands its subtree a tenant; an edge that does not passes on
         * its parent's context, which {@link #foldAncestorContexts} propagates. A
         * classification-rejected edge has no site and so passes its parent's context on, unless
         * it is in {@link #countedRejectedEdges}, which judges it as its fix will leave it.
         */
        private boolean edgeEstablishesContext(FieldCoordinates edge) {
            if (fanMarked(edge)) {
                // A fanned field stamps each unioned row's tenant as per-element localContext, so
                // its subtree is tenant-homogeneous per element: the edge establishes context and
                // children below it classify Inherited (a @splitQuery child then partitions per
                // tenant through the loader-name seam, exactly as under an ArgumentBound root).
                return true;
            }
            if (countedRejectedEdges.contains(edge)) {
                return true;
            }
            // Every site, since the fact it feeds is the every-path context fact: an edge one of
            // whose instances hands nothing down leaves that path without a tenant.
            var edgeSites = sitesAt(edge);
            return !edgeSites.isEmpty() && edgeSites.stream().allMatch(this::siteEstablishesContext);
        }

        private boolean siteEstablishesContext(Site site) {
            // A guaranteed binding names a tenant on every request. A counted field that needs a
            // tenant without one guaranteed is judged as its fixes leave it, so it rejects alone
            // rather than voiding the context of every type it reaches.
            if (directBinding(site).reading() instanceof Reading.Guaranteed
                    || countedSites.contains(site)) {
                return true;
            }
            if (hasKind(site.members(), OperationMember.Kind.NODE_RESOLVE)) {
                // Each node type's own dispatch fact decides what node dispatch hands it: a
                // global one is an unbound entry, seeded in foldAncestorContexts, and a
                // tenant-scoped one routes, or will once its key is fixed.
                return true;
            }
            // The parent row names the tenant per row, so the subtree below the edge is
            // tenant-homogeneous per parent row and its children inherit.
            return parentRowBinding(site) != null;
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
