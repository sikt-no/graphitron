package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.Arrival;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.model.ReachableSourceShape;
import no.sikt.graphitron.rewrite.model.Source;
import no.sikt.graphitron.rewrite.model.TenantScopes;
import no.sikt.graphitron.rewrite.session.SessionHooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parsed representation of a GraphQL schema. Holds all classified types and fields.
 *
 * <p>Types are keyed by name. The {@link #fields} map is the authoritative flat index of all
 * classified fields, keyed by {@link FieldCoordinates}. Use {@link #field} for O(1) point
 * lookups and {@link #fieldsOf} for O(1) per-type lookups (pre-grouped at construction time).
 *
 * <p>{@link #warnings} carries non-fatal advisories accumulated during classification,
 * shape-parallel to the errors {@code GraphitronSchemaValidator} produces but never failing
 * the build. Surfaced by the plugin's mojos to the Maven log.
 *
 * <p>{@link #contextArguments} is the cached output of {@link ContextArgumentClassifier},
 * computed once at construction time. The validator and the facade generator both read this
 * field rather than re-classifying, so a single-producer guarantee holds across the consumers.
 *
 * <p>{@link #diagnostics} carries build-time validation findings accumulated instead of
 * demoting a classified verdict to {@code UnclassifiedType} / {@code UnclassifiedField}. Each
 * is a fully-formed {@link ValidationError}; the validator drains them into the
 * {@link ValidationError} stream it emits, so a verdict read after the walk equals the verdict
 * classification produced. Empty for every test-constructed schema and every error-free build.
 *
 * <p>{@link #arrivals} is the ancestor-product arrival fold, a typename-keyed index computed
 * once over the assembled SDL ({@code ArrivalIndex}). It is the ancestor fact {@link #sourceOf}
 * threads into {@link OutputField#source(Arrival)}; arrival is a parent-typename-grain fact, so
 * it lives here rather than as a per-leaf component. Empty for test-constructed schemas, which
 * then fold every nested field to the conservative absorbing {@link Arrival#MANY}.
 *
 * <p>{@link #reachableSourceShapes} is the per-{@link FieldCoordinates} reachable-source-shape
 * fact ({@code MixedSourceReachIndex}), computed once post-walk. It carries an entry only for a
 * coordinate reached through more than one source shape; single-reach coordinates are absent and
 * derive their singleton on read. The dispatch emitter and the validator's shape-set rule both
 * read it, so neither re-derives the union. Empty for every single-source schema.
 *
 * <p>{@link #tenantScopes} is the catalog-wide tenant-scope classification
 * ({@link TenantScopeClassifier}), computed once at catalog load from the configured
 * {@code <tenantColumn>} element. The validator's tenant drain and the tenant-routing emitters
 * both read this field. {@link TenantScopes.None} for single-tenant builds and every
 * test-constructed schema.
 *
 * <p>{@link #connectionSynthesis} is the coordinate-keyed connection-synthesis relation
 * ({@link ConnectionSynthesisRelation}): one row per Relay connection carrier the classify walk
 * visited, plus the schema-grain minted names. The plan's facet producers read it by coordinate.
 * {@link ConnectionSynthesisRelation#EMPTY} for every schema built without the classify walk.
 *
 * <p>{@link #sessionHooks} is the resolved session-hook carrier
 * ({@link no.sikt.graphitron.rewrite.session.SessionHooks}), minted by
 * {@link GraphitronSchemaBuilder} from the authored {@code <sessionState>} strings and hung
 * here upstream of the contextArgument classification, so the mount's payload parameters feed
 * the classifier as an additional root. Defaults to
 * {@link no.sikt.graphitron.rewrite.session.SessionHooks.NotConfigured} the way
 * {@link #tenantScopes} defaults, so the population is a function of the model's own
 * components rather than of which constructor a caller used.
 */
public record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields,
    Map<String, List<GraphitronField>> fieldsByType,
    Map<String, EntityResolution> entitiesByType,
    List<BuildWarning> warnings,
    ContextArgumentClassifier.Classification contextArguments,
    List<ValidationError> diagnostics,
    Map<String, Arrival> arrivals,
    Map<FieldCoordinates, Set<ReachableSourceShape>> reachableSourceShapes,
    TenantScopes tenantScopes,
    TenantBindingIndex tenantBindings,
    Set<String> argumentReachableInputs,
    ConnectionSynthesisRelation connectionSynthesis,
    OperationMemberRelation operationMembers,
    DeliveryFactRelation deliveryFacts,
    SessionHooks sessionHooks
) {

    public GraphitronSchema {
        // Null-tolerant: only catalog-aware builds populate the tenant classification; every
        // other caller (unit tier, hand-built schemas) defaults to single-tenant.
        tenantScopes = tenantScopes == null ? TenantScopes.None.INSTANCE : tenantScopes;
        tenantBindings = tenantBindings == null ? TenantBindingIndex.EMPTY : tenantBindings;
        argumentReachableInputs = argumentReachableInputs == null ? Set.of() : argumentReachableInputs;
        connectionSynthesis = connectionSynthesis == null ? ConnectionSynthesisRelation.EMPTY : connectionSynthesis;
        operationMembers = operationMembers == null ? OperationMemberRelation.EMPTY : operationMembers;
        deliveryFacts = deliveryFacts == null ? DeliveryFactRelation.EMPTY : deliveryFacts;
        sessionHooks = sessionHooks == null ? SessionHooks.NotConfigured.INSTANCE : sessionHooks;
    }

    /**
     * The field's tenant-binding arm, or {@code null} when the axis is absent (single-tenant
     * build) or the coordinate carries no arm (a rejected unroutable field, or a coordinate
     * that is not a classified {@link OutputField}). The single seam consumers read the
     * per-field arm through, mirroring {@link #sourceOf(FieldCoordinates)}.
     */
    public no.sikt.graphitron.rewrite.model.TenantBinding tenantBindingOf(FieldCoordinates coord) {
        return tenantBindings.byCoordinate().get(coord);
    }

    /** {@link #tenantBindingOf(FieldCoordinates)} keyed by type/field name. */
    public no.sikt.graphitron.rewrite.model.TenantBinding tenantBindingOf(String typeName, String fieldName) {
        return tenantBindingOf(FieldCoordinates.coordinates(typeName, fieldName));
    }

    /**
     * Whether any coordinate classified {@link no.sikt.graphitron.rewrite.model.TenantBinding.FanOut}:
     * the one predicate the factory generators fork the {@code ExecutionInput} signatures on (the
     * dedicated fan-out tenant-collection parameter exists exactly when this is true), so the
     * emitters cannot drift on what "the schema has a fanned field" means.
     */
    public boolean hasFanOutBinding() {
        return tenantBindings.byCoordinate().values().stream()
            .anyMatch(b -> b instanceof no.sikt.graphitron.rewrite.model.TenantBinding.FanOut);
    }

    /**
     * Two-arg convenience constructor: groups fields by {@code parentTypeName} automatically,
     * preserving insertion order (declaration order when the fields map is a {@link LinkedHashMap}).
     * No entity resolutions, no warnings, empty arrival index (every nested field folds to
     * {@link Arrival#MANY}).
     */
    public GraphitronSchema(Map<String, GraphitronType> types, Map<FieldCoordinates, GraphitronField> fields) {
        this(types, fields, groupByType(fields), Map.of(), List.of(),
            ContextArgumentClassifier.classify(fields.values()), List.of(), Map.of(), Map.of(),
            TenantScopes.None.INSTANCE, TenantBindingIndex.EMPTY, Set.of(),
            ConnectionSynthesisRelation.EMPTY, OperationMemberRelation.EMPTY,
            DeliveryFactRelation.EMPTY, SessionHooks.NotConfigured.INSTANCE);
    }

    /**
     * Convenience constructor: same field-grouping as the two-arg form but preserves the
     * {@code warnings}, {@code diagnostics}, and {@code arrivals} components.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics,
                            Map<String, Arrival> arrivals,
                            Map<FieldCoordinates, Set<ReachableSourceShape>> reachableSourceShapes) {
        this(types, fields, entitiesByType, warnings, diagnostics, arrivals, reachableSourceShapes,
            TenantScopes.None.INSTANCE, TenantBindingIndex.EMPTY);
    }

    /**
     * The {@link GraphitronSchemaBuilder} constructor: the seven-arg field-grouping form plus
     * {@code tenantScopes} and {@code tenantBindings}.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics,
                            Map<String, Arrival> arrivals,
                            Map<FieldCoordinates, Set<ReachableSourceShape>> reachableSourceShapes,
                            TenantScopes tenantScopes,
                            TenantBindingIndex tenantBindings) {
        this(types, fields, entitiesByType, warnings, diagnostics, arrivals, reachableSourceShapes,
            tenantScopes, tenantBindings, Set.of());
    }

    /**
     * The {@link GraphitronSchemaBuilder} constructor with the argument-reachability fold
     * ({@link ArgumentReachableInputs}); the nine-arg form defaults it empty for callers with
     * no assembled schema to walk. Defaults the connection-synthesis relation empty.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics,
                            Map<String, Arrival> arrivals,
                            Map<FieldCoordinates, Set<ReachableSourceShape>> reachableSourceShapes,
                            TenantScopes tenantScopes,
                            TenantBindingIndex tenantBindings,
                            Set<String> argumentReachableInputs) {
        this(types, fields, entitiesByType, warnings, diagnostics, arrivals, reachableSourceShapes,
            tenantScopes, tenantBindings, argumentReachableInputs, ConnectionSynthesisRelation.EMPTY);
    }

    /**
     * The {@link GraphitronSchemaBuilder} constructor with the connection-synthesis relation
     * ({@link ConnectionSynthesisRelation}), the classify walk's coordinate-keyed sidecar.
     * Defaults the minted operation member relation to its not-computed sentinel.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics,
                            Map<String, Arrival> arrivals,
                            Map<FieldCoordinates, Set<ReachableSourceShape>> reachableSourceShapes,
                            TenantScopes tenantScopes,
                            TenantBindingIndex tenantBindings,
                            Set<String> argumentReachableInputs,
                            ConnectionSynthesisRelation connectionSynthesis) {
        this(types, fields, entitiesByType, warnings, diagnostics, arrivals, reachableSourceShapes,
            tenantScopes, tenantBindings, argumentReachableInputs, connectionSynthesis,
            OperationMemberRelation.EMPTY);
    }

    /**
     * The {@link GraphitronSchemaBuilder} constructor with the minted operation member relation
     * ({@link OperationMemberRelation}), the post-walk trigger-fact fold. Defaults the delivery
     * fact relation to its not-computed sentinel.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics,
                            Map<String, Arrival> arrivals,
                            Map<FieldCoordinates, Set<ReachableSourceShape>> reachableSourceShapes,
                            TenantScopes tenantScopes,
                            TenantBindingIndex tenantBindings,
                            Set<String> argumentReachableInputs,
                            ConnectionSynthesisRelation connectionSynthesis,
                            OperationMemberRelation operationMembers) {
        this(types, fields, entitiesByType, warnings, diagnostics, arrivals, reachableSourceShapes,
            tenantScopes, tenantBindings, argumentReachableInputs, connectionSynthesis,
            operationMembers, DeliveryFactRelation.EMPTY);
    }

    /**
     * The {@link GraphitronSchemaBuilder} constructor with the materialized delivery fact
     * relation ({@link DeliveryFactRelation}), the post-walk delivery fold beside the member
     * relation. Defaults the session-hook carrier to not-configured.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics,
                            Map<String, Arrival> arrivals,
                            Map<FieldCoordinates, Set<ReachableSourceShape>> reachableSourceShapes,
                            TenantScopes tenantScopes,
                            TenantBindingIndex tenantBindings,
                            Set<String> argumentReachableInputs,
                            ConnectionSynthesisRelation connectionSynthesis,
                            OperationMemberRelation operationMembers,
                            DeliveryFactRelation deliveryFacts) {
        this(types, fields, entitiesByType, warnings, diagnostics, arrivals, reachableSourceShapes,
            tenantScopes, tenantBindings, argumentReachableInputs, connectionSynthesis,
            operationMembers, deliveryFacts, SessionHooks.NotConfigured.INSTANCE);
    }

    /**
     * The {@link GraphitronSchemaBuilder} constructor with the resolved session-hook carrier
     * ({@link SessionHooks}). The carrier sits upstream of the contextArgument classification:
     * the classify call below takes it as an additional root, so a mount payload parameter
     * becomes an ordinary name-keyed factory slot, unified with same-named {@code @service}
     * declarations by the classifier's existing type-agreement fold.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics,
                            Map<String, Arrival> arrivals,
                            Map<FieldCoordinates, Set<ReachableSourceShape>> reachableSourceShapes,
                            TenantScopes tenantScopes,
                            TenantBindingIndex tenantBindings,
                            Set<String> argumentReachableInputs,
                            ConnectionSynthesisRelation connectionSynthesis,
                            OperationMemberRelation operationMembers,
                            DeliveryFactRelation deliveryFacts,
                            SessionHooks sessionHooks) {
        this(types, fields, groupByType(fields), Map.copyOf(entitiesByType), List.copyOf(warnings),
            ContextArgumentClassifier.classify(fields.values(), sessionHooks), List.copyOf(diagnostics),
            Map.copyOf(arrivals), Map.copyOf(reachableSourceShapes), tenantScopes, tenantBindings,
            argumentReachableInputs, connectionSynthesis, operationMembers, deliveryFacts, sessionHooks);
    }

    /**
     * Five-arg convenience constructor for tests: no arrival index, so every nested field folds
     * to the conservative {@link Arrival#MANY}.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            List<ValidationError> diagnostics) {
        this(types, fields, entitiesByType, warnings, diagnostics, Map.of(), Map.of());
    }

    /**
     * Seven-arg convenience constructor for tests that supply a pre-grouped {@code fieldsByType}
     * and an explicit {@link ContextArgumentClassifier.Classification}: no arrival index.
     */
    public GraphitronSchema(Map<String, GraphitronType> types,
                            Map<FieldCoordinates, GraphitronField> fields,
                            Map<String, List<GraphitronField>> fieldsByType,
                            Map<String, EntityResolution> entitiesByType,
                            List<BuildWarning> warnings,
                            ContextArgumentClassifier.Classification contextArguments,
                            List<ValidationError> diagnostics) {
        this(types, fields, fieldsByType, entitiesByType, warnings, contextArguments, diagnostics, Map.of(), Map.of(),
            TenantScopes.None.INSTANCE, TenantBindingIndex.EMPTY, Set.of(), ConnectionSynthesisRelation.EMPTY,
            OperationMemberRelation.EMPTY, DeliveryFactRelation.EMPTY, SessionHooks.NotConfigured.INSTANCE);
    }

    private static Map<String, List<GraphitronField>> groupByType(Map<FieldCoordinates, GraphitronField> fields) {
        var grouped = new LinkedHashMap<String, List<GraphitronField>>();
        for (var field : fields.values()) {
            grouped.computeIfAbsent(field.parentTypeName(), k -> new ArrayList<>()).add(field);
        }
        return Map.copyOf(grouped);
    }

    /**
     * Returns the field at the given coordinates, or {@code null} if absent.
     */
    public GraphitronField field(String typeName, String fieldName) {
        return fields.get(FieldCoordinates.coordinates(typeName, fieldName));
    }

    /**
     * Returns the type with the given name, or {@code null} if absent.
     */
    public GraphitronType type(String typeName) {
        return types.get(typeName);
    }

    /**
     * The field's {@link Source} arm, folding the parent type's ancestor-product {@link Arrival}
     * into {@link OutputField#source(Arrival)}. The single seam consumers read the arrival arm
     * through. A missing arrival entry folds to the absorbing {@link Arrival#MANY}, so an
     * incompletely indexed schema can never mint a spurious {@link Source.OnlyChild}. Returns
     * {@code null} when the coordinate is absent or does not classify to an {@link OutputField}.
     */
    public Source sourceOf(FieldCoordinates coord) {
        if (!(fields.get(coord) instanceof OutputField out)) {
            return null;
        }
        return out.source(arrivals.getOrDefault(coord.getTypeName(), Arrival.MANY));
    }

    /** {@link #sourceOf(FieldCoordinates)} keyed by type/field name. */
    public Source sourceOf(String typeName, String fieldName) {
        return sourceOf(FieldCoordinates.coordinates(typeName, fieldName));
    }

    /**
     * The coordinate's operation member set: the 0..N relation
     * {@code coordinate -> operation}, one {@link no.sikt.graphitron.rewrite.model.OperationMember}
     * row per operation the coordinate triggers, keyed {@code (coordinate, member)}. The member
     * view beside {@link #connectionSynthesis()}, reading the minted trigger-fact relation
     * ({@link OperationMemberRelation}, the post-walk fold over the gathered trigger slots and
     * the shape facts); a schema built without the classify walk carries the not-computed
     * sentinel and falls back to the leaf-derived projection
     * ({@link no.sikt.graphitron.rewrite.model.OperationMembers}'s compile-total crosswalk), so
     * the read surface is uniform across harnesses. The membership-agreement pin holds the two
     * productions equal over the classified corpus for the coexistence window. Empty for a
     * coordinate that is absent, does not classify to an {@link OutputField}, or triggers no
     * operation (a record-read or nesting coordinate: the DataFetcher's existence is the fact).
     */
    public List<no.sikt.graphitron.rewrite.model.OperationMember> operationMembersOf(FieldCoordinates coord) {
        if (operationMembers != OperationMemberRelation.EMPTY) {
            return operationMembers.membersOf(coord);
        }
        if (!(fields.get(coord) instanceof OutputField out)) {
            return List.of();
        }
        return no.sikt.graphitron.rewrite.model.OperationMembers.membersOf(out);
    }

    /** {@link #operationMembersOf(FieldCoordinates)} keyed by type/field name. */
    public List<no.sikt.graphitron.rewrite.model.OperationMember> operationMembersOf(String typeName, String fieldName) {
        return operationMembersOf(FieldCoordinates.coordinates(typeName, fieldName));
    }

    /**
     * The coordinate's delivery fact: batched keyed re-query or inline, the anchor-hood axis
     * the launcher membership predicate joins with the member rows. Reads the materialized
     * relation ({@link DeliveryFactRelation}, the post-walk fold beside the member relation);
     * a schema built without the classify walk carries the not-computed sentinel and falls
     * back to the leaf-derived crosswalk
     * ({@link no.sikt.graphitron.rewrite.model.DeliveryFact#leafDerivedOf}), so the read
     * surface is uniform across harnesses. The delivery pin holds the two productions equal
     * over the classified corpus. {@link no.sikt.graphitron.rewrite.model.DeliveryFact.Inline}
     * for a coordinate that is absent or does not classify to an {@link OutputField}.
     */
    public no.sikt.graphitron.rewrite.model.DeliveryFact deliveryOf(FieldCoordinates coord) {
        if (deliveryFacts != DeliveryFactRelation.EMPTY) {
            return deliveryFacts.deliveryOf(coord);
        }
        if (!(fields.get(coord) instanceof OutputField out)) {
            return no.sikt.graphitron.rewrite.model.DeliveryFact.Inline.INSTANCE;
        }
        return no.sikt.graphitron.rewrite.model.DeliveryFact.leafDerivedOf(out);
    }

    /** {@link #deliveryOf(FieldCoordinates)} keyed by type/field name. */
    public no.sikt.graphitron.rewrite.model.DeliveryFact deliveryOf(String typeName, String fieldName) {
        return deliveryOf(FieldCoordinates.coordinates(typeName, fieldName));
    }

    /**
     * The set of source shapes proven to reach {@code coord}. Returns the reified union for a
     * coordinate reached through more than one shape; for a single-reach coordinate (absent from the
     * stored index) derives the singleton from the parent type's classification. Returns an empty set
     * for a coordinate that is not a source-shape-dispatched member field (a root field, or a column
     * on a {@code @table} parent). Never {@code null}.
     */
    public Set<ReachableSourceShape> reachableSourceShapes(FieldCoordinates coord) {
        var stored = reachableSourceShapes.get(coord);
        if (stored != null) {
            return stored;
        }
        return switch (types.get(coord.getTypeName())) {
            case GraphitronType.NestingType ignored -> Set.of(ReachableSourceShape.NESTING_RECORD);
            case GraphitronType.JooqRecordCarrier ignored -> Set.of(ReachableSourceShape.JOOQ_RECORD_CARRIER);
            case GraphitronType.ResultType ignored -> Set.of(ReachableSourceShape.CLASS_BACKED_ACCESSOR);
            case null, default -> Set.of();
        };
    }

    /** {@link #reachableSourceShapes(FieldCoordinates)} keyed by type/field name. */
    public Set<ReachableSourceShape> reachableSourceShapes(String typeName, String fieldName) {
        return reachableSourceShapes(FieldCoordinates.coordinates(typeName, fieldName));
    }

    /**
     * Read-only view of the reified index: every coordinate reached through more than one source
     * shape, keyed to its shape-set union. Single-reach coordinates are absent. Consumed by the
     * validator's shape-set rule and the mixed-source pipeline tests.
     */
    public Map<FieldCoordinates, Set<ReachableSourceShape>> mixedSourceCoordinates() {
        return reachableSourceShapes;
    }

    /**
     * Returns all fields belonging to {@code typeName}, in declaration order, or an empty list
     * if the type has no fields recorded in this schema.
     */
    public List<GraphitronField> fieldsOf(String typeName) {
        return fieldsByType.getOrDefault(typeName, List.of());
    }

    /**
     * The nesting/pivot reach fold ({@link NestingReach}): every type reached as a nesting or
     * pivot projection from a table-backed root, with its one representative wiring. A pure
     * whole-schema fold over this schema's own components (no stored index, the
     * {@link #joinedTableReprojectionOf} precedent); its three consumers (the type-unit
     * producer's fetchers membership, the fetcher generator's per-row nested build, the
     * registrations emitter's nested bodies) read one representative order by construction.
     */
    public NestingReach nestingReach() {
        return NestingReach.compute(this);
    }

    /**
     * The joined-table participants' field-residence split for a single-table discriminated
     * interface: the read surface of the {@link JoinedTableReprojection} fold, one formula for
     * the launcher producer and the legacy interface-reprojection call sites alike.
     * {@link JoinedTableReprojection#EMPTY} when {@code typeName} does not name a
     * {@link GraphitronType.TableInterfaceType} here. A pure per-type fold over this schema's
     * own components, so it needs no stored index; the validator drains its deferrals.
     */
    public JoinedTableReprojection joinedTableReprojectionOf(String typeName) {
        return JoinedTableReprojection.of(this, typeName);
    }

    /**
     * Returns the federation entity-resolution metadata for {@code typeName}, or {@code null}
     * if the type carries no {@code @key} (and is not a {@code @node}). The classifier records
     * one entry here per type whose SDL declaration carries at least one resolvable
     * {@code @key} alternative; the runtime entity dispatcher consumes these to wire the
     * {@code _entities} fetcher.
     */
    public EntityResolution entityResolution(String typeName) {
        return entitiesByType.get(typeName);
    }

}
