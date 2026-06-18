package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.ErrorHandlerType;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.InputRecordShape;
import no.sikt.graphitron.rewrite.model.InputRecordShape.InputComponent;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NestingType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ScalarResolution;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ORDER_BY;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DESCRIPTION;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CODE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_COLLATE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_HANDLER;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_HANDLERS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_KEY_COLUMNS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_MATCHES;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_ON;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_RECORD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_SCALAR;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_SQL_STATE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_VALUE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_DISCRIMINATE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_DISCRIMINATOR;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ERROR;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_RECORD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SCALAR_TYPE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SERVICE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_OVERRIDE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.argBoolean;
import static no.sikt.graphitron.rewrite.BuildContext.argString;
import static no.sikt.graphitron.rewrite.BuildContext.argStringList;
import static no.sikt.graphitron.rewrite.BuildContext.asMap;
import static no.sikt.graphitron.rewrite.BuildContext.candidateHint;
import static no.sikt.graphitron.rewrite.BuildContext.locationOf;

/**
 * Classifies all named types in the schema into the {@link GraphitronType} hierarchy.
 *
 * <p>Classification is field-first and reachability-driven (see {@link #buildTypes()}): each type
 * is classified as the walk reaches it, including interface / union participant lists, which are a
 * registry-free function of SDL plus the reflection fixed point (R279 slice 3a, R317 slice 1) and
 * so need no separate enrichment pass.
 */
class TypeBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(TypeBuilder.class);

    private static final Set<String> ROOT_TYPE_NAMES = Set.of("Query", "Mutation", "Subscription");

    private final BuildContext ctx;
    private final ServiceCatalog svc;
    private final Map<String, Class<?>> recordBackingClasses = new LinkedHashMap<>();
    /**
     * R96: the reflection-driven SDL → backing-class binding resolver. Constructed and
     * populated by {@link #buildTypes()} before per-type classification; consulted by
     * {@link #buildResultType} and {@link #buildNonTableInputType} to decide the backed
     * variant. The directive's {@code className} no longer participates.
     */
    private RecordBindingResolver bindings;

    /** Lazily computed by {@link #retainedSupportTypes()}; null until the first support-type gate runs. */
    private Set<String> retainedSupportTypes;

    /**
     * R279 slice 3b — the reachable output-type set discovered by the field-first walk in
     * {@link #buildTypes()}. Exposed so the field-classification pass in
     * {@link GraphitronSchemaBuilder} drives over the same reachable object types first, with a
     * compensating sweep over the unreached ones (removed in slice 6). Empty until
     * {@code buildTypes()} runs.
     */
    private Set<String> reachableOutputTypes = Set.of();

    /** @see #reachableOutputTypes */
    Set<String> reachableOutputTypes() {
        return reachableOutputTypes;
    }

    TypeBuilder(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    /**
     * Backing classes for reflection-bound result types and input types, keyed by GraphQL
     * type name. Populated by R96's {@link RecordBindingResolver} via the recursive reflection
     * walk before per-type classification runs. The schema builder threads each loaded class
     * through {@link FieldBuilder#classifyField} so the per-field accessor resolver does not
     * re-load. Per the rewrite design principles, the class is classifier-time scratch state
     * and does not survive into the persisted model.
     */
    Map<String, Class<?>> recordBackingClasses() {
        return recordBackingClasses;
    }

    /**
     * R178 DML payload bindings produced by {@link RecordBindingResolver#groundDmlMutationField}.
     * Keyed by payload SDL type name. Read by the schema-builder loop and threaded into
     * {@link FieldBuilder#classifyField} so the unified-path classifier can route a payload
     * field's child classification through the inner {@code TableRef} the DML producer carries.
     * Empty until {@code buildTypes()} completes.
     */
    java.util.Optional<no.sikt.graphitron.rewrite.model.ProducerBinding.DmlEmitted> dmlEmittedBinding(String sdlTypeName) {
        return bindings == null ? java.util.Optional.empty() : bindings.resolveDmlEmitted(sdlTypeName);
    }

    /**
     * R178 step 2b: resolves the optional {@link no.sikt.graphitron.rewrite.model.ProducerBinding.ServiceEmitted}
     * binding for an SDL payload type whose producer is an {@code @service} mutation field
     * with a carrier-shaped payload. Mirrors {@link #dmlEmittedBinding}; both bindings sit on
     * dedicated maps inside the resolver and are read at field-classify time to drive the
     * unified-path data-field permit construction.
     */
    java.util.Optional<no.sikt.graphitron.rewrite.model.ProducerBinding.ServiceEmitted> serviceEmittedBinding(String sdlTypeName) {
        return bindings == null ? java.util.Optional.empty() : bindings.resolveServiceEmitted(sdlTypeName);
    }

    // ===== Type map construction =====

    Map<String, GraphitronType> buildTypes() {
        // R96: derive SDL → backing-class bindings from reflection before per-type classification
        // runs. The directive-driven path inside buildResultType / buildNonTableInputType is gone;
        // both consult the resolver for the variant decision.
        bindings = new RecordBindingResolver(ctx, svc);
        bindings.resolveAll();
        // Side-effect: populate recordBackingClasses for downstream field-classification threading.
        for (var named : ctx.schema.getAllTypesAsList()) {
            if (named.getName().startsWith("__")) continue;
            bindings.resolveResult(named.getName()).ifPresent(cls ->
                recordBackingClasses.put(named.getName(), cls));
            bindings.resolveInput(named.getName()).ifPresent(cls ->
                recordBackingClasses.putIfAbsent(named.getName(), cls));
        }

        // R279 slice 3b — the driver inversion. The classified output-type set is *discovered* by
        // the field-first reachability walk ({@link SchemaReachability}: seed Query / Mutation plus
        // the @node / @key directive scan, descend field->target, union->member, and
        // interface->implementor), not by iterating every declared type in isolation. Each reached
        // type is classified here as a byproduct of the field edge that reached it; the verdict is
        // the same {@link #classifyType} value the eager pass produced, now triggered on demand from
        // reachability rather than eagerly over {@code getAllTypesAsList()}. This is the field-first
        // flip: fields drive types.
        var reachable = SchemaReachability.reachableTypeNames(ctx.schema);
        this.reachableOutputTypes = reachable;
        // R317 slice 2 — build the fixed-point reverse indices the field pass reads in place of
        // its whole-registry NodeType / participant scans. Derived from SDL + catalog via the same
        // producers (buildTableType / buildTableInterfaceType), not memoised from the registry, so
        // it carries no dependency on the registry being fully populated.
        buildClassificationIndices(reachable);
        for (var name : reachable) {
            if (!(ctx.schema.getType(name) instanceof GraphQLNamedType namedType)) continue;
            var gType = classifyType(namedType);
            if (gType != null) {
                // R279 slice 2: per-type classification goes through the reconciling register entry.
                ctx.typeRegistry.register(name, gType);
            }
        }
        // R279 slice 6 — the orphan prune (intended behaviour change). The field-first walk already
        // reaches the whole output surface, so an output composite (object / interface / union) the
        // walk did NOT reach is an orphan and is no longer classified: an unreachable @table object
        // gets no generated file and the reachability prune is observable. Input types, scalars, and
        // enums are never reachable through output edges (the walk descends field-output / union-member
        // / interface-implementor only), so they keep this dedicated sweep; without it inputs and
        // scalars would vanish from the registry.
        for (var namedType : ctx.schema.getAllTypesAsList()) {
            if (namedType.getName().startsWith("__")) continue;
            if (reachable.contains(namedType.getName())) continue;
            if (namedType instanceof GraphQLObjectType
                    || namedType instanceof GraphQLInterfaceType
                    || namedType instanceof GraphQLUnionType) {
                continue;
            }
            var gType = classifyType(namedType);
            if (gType != null) {
                ctx.typeRegistry.register(namedType.getName(), gType);
            }
        }
        // R307: the directive-ignored warning is a classification output. With the classify driver
        // now reachability-partitioned, emit it in a dedicated pass over getAllTypesAsList so the
        // warning order is stable (identical to the pre-slice-3b SDL order) and independent of walk
        // order. It reads only the reflection-binding fixed point (resolveAll, above) and SDL
        // directives, never the registry, so it is order-independent of classification.
        for (var namedType : ctx.schema.getAllTypesAsList()) {
            if (namedType.getName().startsWith("__")) continue;
            emitDirectiveIgnoredWarning(namedType);
        }

        // R96: surface multi-producer rejections as UnclassifiedType.
        surfaceMultiProducerRejections();

        // R317 slice 1 — the former second interface/union enrichment pass is gone: each interface
        // and union is now classified with its participants at the node visit in {@link #classifyType}
        // (the participant verdict is registry-free per slice 3a, so folding it onto the visit is
        // byte-identical). What remains here is global validation over the finished registry, not a
        // classification pass.

        // NodeType typeId uniqueness: two types cannot share a typeId because Query.node(id:)
        // dispatch extracts the typeId prefix and routes to one GraphQL type. Colliding entries
        // demote symmetrically — we can't pick a winner without silently breaking the loser's
        // issued IDs, which would violate the durability invariant.
        validateNodeTypeIdUniqueness(ctx.typeRegistry);

        // R317 slice 3b — carrier promotion is folded onto the producing edge. The former
        // promoteSingleRecordPayloads pass (a post-type-pass SDL scan that registered every
        // producer-backed single-record carrier as a JooqTableRecordType) is deleted; the verdict is
        // now landed at the edge that reaches the carrier, from the registry-free
        // carrierTableBinding fixed point, when the carrier type is visited in the field pass
        // (GraphitronSchemaBuilder.classifyFieldsOfObject). See carrierTableBinding.

        return ctx.typeRegistry.entries();
    }

    /**
     * R317 slice 3a/3b — the producer-bound table backing a directiveless single-record carrier, or
     * {@code null} when no DML {@code RETURNING} or {@code @service} producer returns it. Registry-free:
     * derived from the structural carrier scan ({@link BuildContext#scanStructuralDmlPayload} /
     * {@link BuildContext#scanStructuralServiceCarrierPayload}) plus the producer binding fixed point
     * ({@code DmlEmitted} / {@code ServiceEmitted}), never from the in-progress type registry. A
     * producer-backed carrier binds its wrapper to the producer's table record: a DML {@code RETURNING}
     * or an {@code @service} method yields a {@code Record} (single) or {@code Result<Record>} (multi),
     * so the carrier IS that record, single or multi cardinality alike, and the inner data field reads
     * off the record through the standard record-backed path. R275: each producer family gates on its
     * own scan; DML carriers keep the strict forbidden-directive set, {@code @service} carriers tolerate
     * {@code @splitQuery} on the data field.
     *
     * <p>Sole producer of the carrier-table fact, shared by
     * {@link GraphitronSchemaBuilder#classifyFieldsOfObject} (slice 3b: registers the
     * {@link GraphitronType.JooqTableRecordType} at the carrier's visit) and
     * {@link #isDirectivelessNestingTarget} (which excludes producer-backed carriers from the nesting
     * verdict), so the two cannot drift. A carrier-shaped payload that no producer returns (orphan) has
     * no table to bind to; it stays a {@link NestingType} and is rejected by the soundness pass.
     */
    TableRef carrierTableBinding(String name) {
        if (ctx.scanStructuralDmlPayload(name) instanceof BuildContext.DmlPayloadScan.Admit) {
            var table = dmlEmittedBinding(name).map(b -> b.tableRef()).orElse(null);
            if (table != null) return table;
        }
        if (ctx.scanStructuralServiceCarrierPayload(name) instanceof BuildContext.DmlPayloadScan.Admit) {
            var table = serviceEmittedBinding(name).map(b -> b.tableRef()).orElse(null);
            if (table != null) return table;
        }
        return null;
    }

    /**
     * R317 slice 3a — registry-free verdict for whether an SDL object reached at an embedding edge is a
     * directiveless nesting target: a plain object with no competing classification, to be projected as a
     * {@link GraphitronType.NestingType} from the embedding parent's table context. Computed from the
     * type's own SDL plus the binding fixed points, never from the in-progress type registry, so an
     * embedding edge decides nesting independently of whether a sibling edge already registered the same
     * {@code NestingType} (the edge-driven order-independence invariant: an edge never reads a sibling
     * edge's classification). True iff the type is a {@link GraphQLObjectType} that {@link #classifyType}
     * leaves unclassified (no {@code @table} / {@code @error} / producer-backed result, not a root /
     * interface / union / enum / scalar), is not a multi-producer rejection (those classify as
     * {@link UnclassifiedType}), and is not a producer-bound single-record carrier
     * ({@link #carrierTableBinding}, which classifies as {@code JooqTableRecordType} instead). This
     * reproduces, structurally, the {@code ctx.types.get(name) == null} signal the field pass read before
     * the fold (an SDL object the type pass left unregistered), minus the registry read.
     */
    boolean isDirectivelessNestingTarget(String name) {
        if (!(ctx.schema.getType(name) instanceof GraphQLObjectType obj)) return false;
        if (bindings.rejection(name).isPresent()) return false;
        if (classifyType(obj) != null) return false;
        return carrierTableBinding(name) == null;
    }

    /**
     * R317 slice 3e — registry-free look-ahead at a field's target type. Returns the verdict the
     * target type name resolves to, computed from SDL + reflection bindings + catalog
     * ({@link #classifyType}) plus the producer-bound single-record carrier fixed point
     * ({@link #carrierTableBinding}), never read from the in-progress type registry. It reproduces
     * exactly what {@code ctx.types.get(name)} returned at field-classification time in the two-pass
     * world (where {@code buildTypes} had fully populated the registry before the field pass ran),
     * so the field pass can resolve an {@link InterfaceType} / {@link UnionType} / {@link ResultType}
     * target without depending on that target having been registered. That independence is the
     * precondition for folding type and field classification into one enter-only walk (slice 4),
     * where a field's output target is a not-yet-visited child of the field's parent.
     *
     * <p>{@code classifyType} covers the {@code @table} / {@code @node} / {@code @error} / interface
     * / union / reflection-bound result / scalar / enum / input verdicts. The carrier fallback covers
     * the one verdict {@code classifyType} leaves {@code null} but the registry holds non-null: the
     * directiveless single-record carrier, bound at the producing edge from {@link #carrierTableBinding}
     * (a {@link GraphitronType.JooqTableRecordType}), not by a resolved {@code @record} producer. A
     * directiveless nesting target / orphan classifies to {@code null} under both, matching the
     * registry's absent entry; the nesting branch in {@link FieldBuilder} is decided separately by
     * {@link #isDirectivelessNestingTarget}, not by this verdict. The post-walk demotions
     * ({@link #validateNodeTypeIdUniqueness}, multi-producer, case-fold) do not change any verdict arm
     * this look-ahead is read for (interface / union / result): a demoted node is read through the
     * {@code NodeIndex}, and a multi-producer / case-fold demotion turns a verdict that was already not
     * one of those arms into an {@code UnclassifiedType} that is still not one of those arms.
     */
    GraphitronType lookAheadVerdict(String typeName) {
        if (!(ctx.schema.getType(typeName) instanceof GraphQLNamedType named)) return null;
        var verdict = classifyType(named);
        if (verdict != null) return verdict;
        var carrierTable = carrierTableBinding(typeName);
        if (carrierTable == null) return null;
        var objType = ctx.schema.getObjectType(typeName);
        return new GraphitronType.JooqTableRecordType(typeName, locationOf(objType), null, carrierTable);
    }

    private static void validateNodeTypeIdUniqueness(TypeRegistry registry) {
        var byTypeId = new LinkedHashMap<String, List<NodeType>>();
        for (var type : registry.entries().values()) {
            if (type instanceof NodeType nt) {
                byTypeId.computeIfAbsent(nt.typeId(), k -> new ArrayList<>()).add(nt);
            }
        }
        for (var entry : byTypeId.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            String typeId = entry.getKey();
            List<String> colliding = entry.getValue().stream().map(NodeType::name).sorted().toList();
            String others = String.join(", ", colliding);
            for (var nt : entry.getValue()) {
                registry.register(nt.name(), new UnclassifiedType(nt.name(), nt.location(),
                    Rejection.structural("typeId '" + typeId + "' is declared on multiple types (" + others
                    + ") — Query.node dispatch would be nondeterministic; pick one via @node(typeId:)")));
            }
        }
    }

    /**
     * R317 slice 2/3d — builds the fixed-point reverse indices ({@link BuildContext#nodes},
     * {@link BuildContext#tables}, {@link BuildContext#errors},
     * {@link BuildContext#crossTableFieldsByParticipant}) that retire {@code FieldBuilder}'s
     * whole-registry and keyed {@code ctx.types.get} reads at classification edges. Derived from the
     * SDL declarations via the same producers classification uses ({@code buildTableType} for nodes
     * and tables, {@code buildTableInterfaceType} for table-interfaces, {@code buildErrorType} for
     * errors).
     *
     * <p>R317 slice 3d — the three membership indices ({@code nodes} / {@code tables} / {@code errors})
     * are directive-scanned over <b>all</b> declared types (a superset of the reachable set, unpruned),
     * and are <b>pure</b>: no demotion, no reachability prune, and no typeId-uniqueness exclusion (slice
     * 2 conflated the latter into {@code NodeIndex}; it comes out here, with
     * {@link #validateNodeTypeIdUniqueness} left the sole owner of uniqueness as a validation reduction
     * over the registry). The superset is sound because every type a field read actually queries is
     * already reachable. Multiple {@code @node} types on one table is legitimate (distinct node ids
     * over the same rows): {@code byTable} is one-to-many (every node on a table), and the implicit
     * "encoder for this table" lookup is resolved at the call site, which rejects the zero and
     * ambiguous (>1) cases. {@code byName} keys on the distinct type names so each node resolves
     * independently through the explicit {@code @nodeId(typeName:)} path.
     *
     * <p>The participant index ({@code crossTableFieldsByParticipant}) stays restricted to the
     * reachable set.
     */
    private void buildClassificationIndices(Set<String> reachable) {
        // R317 slice 3d — the membership indices (node / table / error) are directive-scanned over
        // ALL declared types (skipping the __-prefixed introspection types), a superset of the
        // reachable set. No reachability prune: every type a field read actually queries is already
        // reachable (@node / @key self-seed; a @table data field or @error member is queried only by
        // a field that reaches it), so the index and the reachability-pruned registry agree on the
        // consulted domain. The indices are PURE: no demotion, no reachability prune, and no
        // typeId-uniqueness exclusion (validateNodeTypeIdUniqueness is the sole owner of uniqueness,
        // as a validation reduction over the registry). Iteration follows SDL declaration order, which
        // is the order classifyType registers the reachable subset in, so byName/byTable ordering is
        // unchanged for the consulted (reachable) entries.
        var byTable = new LinkedHashMap<String, List<NodeType>>();
        var byName = new LinkedHashMap<String, NodeType>();
        var byTableType = new LinkedHashMap<String, TableBackedType>();
        var byErrorName = new LinkedHashMap<String, ErrorType>();
        for (var named : ctx.schema.getAllTypesAsList()) {
            if (named.getName().startsWith("__")) continue;
            if (!(named instanceof GraphQLObjectType || named instanceof GraphQLInterfaceType)) continue;
            // Drive membership off the same classifyType verdict the registry stores (classifyType is
            // registry-free / pure), so the index agrees with the registry by construction: a directive
            // conflict (e.g. @table + @error) or a catalog-miss yields an UnclassifiedType, not a
            // TableBackedType / ErrorType, exactly as classifyType would register it. Calling
            // classifyType directly (rather than the producers in isolation) keeps the index from
            // resolving a verdict the conflict / federation / support-type gates in classifyType would
            // have suppressed.
            var verdict = classifyType(named);
            switch (verdict) {
                case TableBackedType tbt -> {
                    byTableType.put(tbt.name(), tbt);
                    if (tbt instanceof NodeType nt) {
                        // Multiple @node types on ONE table is legitimate (distinct node ids over the
                        // same rows), so byTable is one-to-many. byName keys on the distinct type names
                        // so each node on a shared table resolves independently through the explicit
                        // @nodeId(typeName:) path.
                        byTable.computeIfAbsent(nt.table().tableName(), k -> new ArrayList<>()).add(nt);
                        byName.put(nt.name(), nt);
                    }
                }
                case ErrorType et -> byErrorName.put(et.name(), et);
                case null, default -> { /* not table-backed / error: not indexed */ }
            }
        }
        var byTableFrozen = new LinkedHashMap<String, List<NodeType>>();
        byTable.forEach((table, nodes) -> byTableFrozen.put(table, List.copyOf(nodes)));
        ctx.nodes = new NodeIndex(byTableFrozen, byName);
        ctx.tables = new TableIndex(byTableType);
        ctx.errors = new ErrorIndex(byErrorName);

        var byParticipant = new LinkedHashMap<String, Map<String, ParticipantRef.TableBound.CrossTableField>>();
        for (var name : reachable) {
            if (!(ctx.schema.getType(name) instanceof GraphQLInterfaceType iface)) continue;
            if (!iface.hasAppliedDirective(DIR_TABLE) || !iface.hasAppliedDirective(DIR_DISCRIMINATE)) continue;
            if (!(buildTableInterfaceType(iface) instanceof TableInterfaceType tit)) continue;
            for (var p : tit.participants()) {
                if (!(p instanceof ParticipantRef.TableBound tb)) continue;
                var fields = byParticipant.computeIfAbsent(tb.typeName(), k -> new LinkedHashMap<>());
                for (var ctf : tb.crossTableFields()) {
                    // First-wins across interfaces, mirroring the old findFirst over the scan.
                    fields.putIfAbsent(ctf.fieldName(), ctf);
                }
            }
        }
        var participantIndex = new LinkedHashMap<String, Map<String, ParticipantRef.TableBound.CrossTableField>>();
        byParticipant.forEach((k, v) -> participantIndex.put(k, Map.copyOf(v)));
        ctx.crossTableFieldsByParticipant = Map.copyOf(participantIndex);
    }

    /**
     * R96: emit the directive-ignored warning for a reachable SDL type carrying
     * {@code @record}. R307 folds this into the classification pass: the method is called once
     * per type as the classifier visits it (no separate post-classification re-walk), so the
     * warning is a classification output. The reflection bindings it reads are a fixed point by
     * then ({@code bindings.resolveAll} runs before the classify loop). Three message variants
     * selected by context:
     *
     * <ul>
     *   <li><b>Shadowed by @table</b> (input types only): the type also carries {@code @table},
     *     so the binding comes from {@code @table}-driven reflection. Variant precedence: this
     *     variant takes precedence over Matches/Disagrees.</li>
     *   <li><b>Matches</b>: the directive's {@code className} equals the reflected class, or
     *     the directive carries no {@code className}. The no-{@code className} case is
     *     equivalent to having no {@code @record} at all under R96 (the directive's
     *     {@code className} is the only field that ever participated in binding); it folds
     *     into Matches by definition.</li>
     *   <li><b>Disagrees</b>: the directive's {@code className} differs from the reflected
     *     class. R96 uses reflection's class; the directive's claim is informational only.</li>
     * </ul>
     *
     * <p>Types whose reflection walk produced a multi-producer rejection do not emit the
     * directive-ignored warning at all; the error supersedes the warning at the same site.
     */
    private void emitDirectiveIgnoredWarning(graphql.schema.GraphQLNamedType named) {
        if (!(named instanceof graphql.schema.GraphQLDirectiveContainer container)) return;
        if (!container.hasAppliedDirective(DIR_RECORD)) return;
        String name = named.getName();
        // Suppress the warning when the reflection walk produced a multi-producer rejection
        // for this type; the typed error supersedes the warning at the same site.
        if (bindings.rejection(name).isPresent()) return;

        boolean isInput = named instanceof GraphQLInputObjectType;
        boolean reachable = isInput
            ? bindings.resolveInput(name).isPresent()
                || (named instanceof GraphQLInputObjectType iot && iot.hasAppliedDirective(DIR_TABLE))
            : bindings.resolveResult(name).isPresent();
        if (!reachable) return;

        String declaredClassName = readRecordClassName(container);
        SourceLocation loc = named instanceof GraphQLObjectType obj
            ? locationOf(obj)
            : named instanceof GraphQLInputObjectType inp
                ? locationOf(inp)
                : null;

        // Shadowed by @table. R276: a @table + @record combination is no longer a hard
        // conflict (detectTypeDirectiveConflict ignores @record), so both OBJECT and INPUT
        // carriers reach this site; @table wins and @record is ignored. Warn so the author
        // removes the dead directive, with the @table-specific message (the backing comes from
        // @table metadata, not a producing field's reflected return).
        if (container.hasAppliedDirective(DIR_TABLE)) {
            String message = (isInput ? "Input type '" : "Type '") + name + "' carries both @table and "
                + formatRecordRef(declaredClassName)
                + ". Graphitron derives the backing class from @table; "
                + "the @record directive is ignored. Remove it.";
            ctx.addWarning(new BuildWarning(message, loc));
            return;
        }

        Class<?> reflectedClass = isInput
            ? bindings.resolveInput(name).orElse(null)
            : bindings.resolveResult(name).orElse(null);
        if (reflectedClass == null) return;

        // Matches: declaredClassName is null (no className declared) OR equals the reflected
        // class name. The no-className case is equivalent to having no @record under R96.
        boolean matches = declaredClassName == null
            || declaredClassName.equals(reflectedClass.getName());
        if (matches) {
            String message = "Type '" + name + "' carries "
                + formatRecordRef(declaredClassName)
                + ". Graphitron derives the same backing class from the producing field's "
                + "reflected return type. The directive is redundant; remove it.";
            ctx.addWarning(new BuildWarning(message, loc));
        } else {
            String message = "Type '" + name + "' carries "
                + formatRecordRef(declaredClassName)
                + ". Graphitron derives a different backing class (" + reflectedClass.getName()
                + ") from the producing field's reflected return type and uses that; "
                + "the directive is ignored. Remove it.";
            ctx.addWarning(new BuildWarning(message, loc));
        }
    }

    /**
     * Reads the {@code className} field on a {@code @record} directive value, or {@code null}
     * when the directive carries no className (no-argument form,
     * {@code @record(record: null)}, or {@code @record(record: {className: null})}).
     */
    private static String readRecordClassName(graphql.schema.GraphQLDirectiveContainer container) {
        var dir = container.getAppliedDirective(DIR_RECORD);
        if (dir == null) return null;
        var recordArg = dir.getArgument(ARG_RECORD);
        if (recordArg == null || recordArg.getValue() == null) return null;
        Map<String, Object> ref = asMap(recordArg.getValue());
        return Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
    }

    private static String formatRecordRef(String className) {
        return className == null
            ? "@record (no className)"
            : "@record(record: { className: \"" + className + "\" })";
    }

    /**
     * R96: for every multi-producer disagreement the resolver reported, demote the SDL type to
     * {@link UnclassifiedType} carrying the typed
     * {@link Rejection.AuthorError.RecordBindingMultiProducer} payload. The validator
     * picks the demotion up through its standard {@link UnclassifiedType} pass.
     */
    private void surfaceMultiProducerRejections() {
        for (var named : ctx.schema.getAllTypesAsList()) {
            if (named.getName().startsWith("__")) continue;
            String name = named.getName();
            var rejection = bindings.rejection(name).orElse(null);
            if (rejection == null) continue;
            SourceLocation loc;
            if (named instanceof GraphQLObjectType obj) loc = locationOf(obj);
            else if (named instanceof GraphQLInputObjectType inp) loc = locationOf(inp);
            else continue;
            var unclassified = new UnclassifiedType(name, loc, rejection);
            // R276 / R279 slice 6: a multi-producer type may be absent (a directiveless object with no
            // single agreed producer was never registered) or present (a prior verdict to demote). The
            // single reconciling register entry stores when absent and demotes when present, so the
            // former contains-guarded classify/demote fork collapses to one call.
            ctx.typeRegistry.register(name, unclassified);
        }
    }

    private List<String> implementorNames(String interfaceName) {
        var iface = (GraphQLInterfaceType) ctx.schema.getType(interfaceName);
        return ctx.schema.getImplementations(iface).stream().map(obj -> obj.getName()).toList();
    }

    /**
     * R279 slice 3a — the participant's classification verdict, recomputed as a pure function of SDL
     * plus the already-resolved reflection bindings, with <em>no</em> sideways read of the type
     * registry. This is the order-independence step that lets the field-first walk (slice 3b) call
     * {@link #buildParticipantList} before any eager type pass has populated {@code ctx.types}.
     *
     * <p>It reproduces exactly the value {@code ctx.types.get(typeName)} returned at enrich time:
     * <ul>
     *   <li>a multi-producer rejection ({@link RecordBindingResolver#rejection}) is what
     *       {@code surfaceMultiProducerRejections} demoted/classified to {@link UnclassifiedType}
     *       before the enrich pass ran, so it is reproduced first and as an {@code UnclassifiedType}
     *       (routing to {@code buildParticipantList}'s error arm, as the old registry read did);
     *   <li>otherwise the type pass's own {@link #classifyType} result, which is {@code null} for a
     *       directiveless object. A directiveless single-record carrier classifies as a
     *       {@code JooqTableRecordType} only at the producing edge in the field pass (R317 slice 3b,
     *       {@link #carrierTableBinding}), after this enrich-time recompute, so it is {@code null} here
     *       under both the old registry read and this recompute, exactly as before.
     * </ul>
     *
     * <p>{@code classifyType} is a value-builder over SDL + bindings + catalog with no registry or
     * accumulator writes, so re-invoking it here is safe; its only side effect anywhere is a rare
     * {@code LOGGER.warn} for a {@code @node} keyColumns order mismatch, which does not affect
     * generated output.
     */
    private GraphitronType participantClassification(String typeName) {
        var named = (GraphQLNamedType) ctx.schema.getType(typeName);
        var rejection = bindings.rejection(typeName).orElse(null);
        if (rejection != null) {
            return new UnclassifiedType(typeName, named == null ? null : locationOf(named), rejection);
        }
        return named == null ? null : classifyType(named);
    }

    /**
     * Classifies each interface implementor / union member into a {@link ParticipantRef}: a
     * {@code @table}-bound member becomes {@link ParticipantRef.TableBound}; a non-table member
     * becomes {@link ParticipantRef.Unbound} when the context admits non-table members
     * ({@code allowNonTableMembers}, a plain interface), else it is an error.
     *
     * <p><b>R278 interim:</b> {@code ParticipantRef.Unbound} is overloaded here for two distinct
     * things, {@code @error} members (e.g. an {@code @error}-only union) and directiveless
     * implementors of a plain interface, and the participant role is derived from the member type's
     * standalone classification rather than from the field that returns the polymorphic type. The
     * proper model (classify in the context of the returning field, give {@code @error} its own
     * participant kind, handle service-populated polymorphic types) is tracked as a separate roadmap
     * item; this method keeps the pre-R276 behaviour, only adapted to directiveless objects now being
     * left unclassified ({@code gt == null}) instead of a {@code PlainObjectType}.
     *
     * @param allowNonTableMembers whether non-table members are admitted as {@link ParticipantRef.Unbound}
     *     (true for a plain {@link InterfaceType}; false for unions and {@code TableInterfaceType},
     *     where a non-table member is an error).
     * @param interfaceTable the {@code TableInterfaceType}'s own table when building participants
     *     for a single-table interface. Used to detect each participant's cross-table fields
     *     (those whose {@code @reference} terminates on a different table than the interface
     *     table); the resulting {@link ParticipantRef.TableBound.CrossTableField} list drives
     *     the conditional LEFT JOIN emission in {@code TypeFetcherGenerator}. {@code null} for
     *     plain {@link InterfaceType} and {@link UnionType} contexts, which do not project
     *     cross-table fields through this path.
     */
    private ParticipantListResult buildParticipantList(List<String> typeNames, boolean allowNonTableMembers,
                                                       TableRef interfaceTable) {
        var result = new ArrayList<ParticipantRef>();
        var errors = new ArrayList<String>();
        for (var typeName : typeNames) {
            var gt = participantClassification(typeName);
            if (gt instanceof TableBackedType tbt && !(gt instanceof TableInterfaceType)) {
                String discriminatorValue = argString(ctx.schema.getObjectType(typeName), DIR_DISCRIMINATOR, ARG_VALUE).orElse(null);
                List<ParticipantRef.TableBound.CrossTableField> crossTableFields = interfaceTable != null
                    ? extractCrossTableFields(typeName, interfaceTable)
                    : List.of();
                result.add(new ParticipantRef.TableBound(typeName, tbt.table(), discriminatorValue, crossTableFields));
            } else if (gt == null && allowNonTableMembers) {
                // Directiveless implementor of a plain interface: the type pass left it unclassified
                // (gt == null), and this context admits non-table members. (R278: see class note.)
                result.add(new ParticipantRef.Unbound(typeName));
            } else if (gt != null && !(gt instanceof UnclassifiedType)) {
                // A classified non-table member, e.g. an @error type in an @error-only union.
                // (R278: @error deserves its own participant kind; see method note.)
                result.add(new ParticipantRef.Unbound(typeName));
            } else {
                errors.add("implementing type '" + typeName + "' is not table-bound (missing @table directive)");
            }
        }
        if (!errors.isEmpty()) {
            return new ParticipantListResult(null, String.join("; ", errors));
        }
        return new ParticipantListResult(List.copyOf(result), null);
    }

    /**
     * Walks the participant type's GraphQL field definitions and collects each scalar field
     * whose {@code @reference} traverses a single-hop FK to a table other than {@code interfaceTable}.
     * The interface fetcher emits a conditional LEFT JOIN (gated by the participant's discriminator
     * value) per field returned here; the per-field DataFetcher reads the projected value back
     * from the result {@code Record} by the {@code aliasName} we choose now.
     *
     * <p>Fields that don't fit the pattern (no {@code @reference}, multi-hop path, condition-only
     * step, or path resolving to the interface's own table) are silently ignored — they reach the
     * field-level classifier through the normal {@code FieldBuilder} path.
     */
    private List<ParticipantRef.TableBound.CrossTableField> extractCrossTableFields(
            String participantTypeName, TableRef interfaceTable) {
        var participantObj = ctx.schema.getObjectType(participantTypeName);
        if (participantObj == null) return List.of();
        var out = new ArrayList<ParticipantRef.TableBound.CrossTableField>();
        for (var fieldDef : participantObj.getFieldDefinitions()) {
            if (!fieldDef.hasAppliedDirective(DIR_REFERENCE)) continue;
            String fieldName = fieldDef.getName();
            var parsed = ctx.parsePath(fieldDef, fieldName, interfaceTable.tableName(), null);
            if (parsed.hasError()) continue;
            if (parsed.elements().size() != 1) continue;
            if (!(parsed.elements().get(0) instanceof JoinStep.FkJoin fk)) continue;
            if (fk.targetTable().tableName().equalsIgnoreCase(interfaceTable.tableName())) continue;

            // Resolve the column on the target table that the field maps to. @field(name:) is the
            // primary signal; fall back to the GraphQL field name when the directive is absent.
            String columnSqlName = argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(fieldName);
            var columnEntry = ctx.catalog.findColumn(fk.targetTable().tableName(), columnSqlName).orElse(null);
            if (columnEntry == null) continue;
            var column = new ColumnRef(columnEntry.sqlName(), columnEntry.javaName(), columnEntry.columnClass());

            String aliasName = participantTypeName + "_" + fieldName;
            out.add(new ParticipantRef.TableBound.CrossTableField(fieldName, column, fk, aliasName));
        }
        return List.copyOf(out);
    }

    // ===== Type classification =====

    GraphitronType classifyType(GraphQLNamedType namedType) {
        if (namedType instanceof graphql.schema.GraphQLScalarType scalarType) {
            return classifyScalarType(scalarType);
        }
        // Federation-injected types (e.g. _Service, _Any) are not Graphitron-managed.
        if (namedType.getName().startsWith("_")) {
            return null;
        }
        // Graphitron's own directive-argument support types (declared in directives.graphqls)
        // exist only to shape build-time directive arguments. Strictly internal ones never
        // classify; published ones (SortDirection) classify only when a non-support coordinate
        // references them. Must run before the enum branch so the support enums are gated too.
        // schema.types() membership is the single retention decision both the runtime arm
        // (GraphitronSchemaClassGenerator.planFor) and the print seam (SchemaSdlEmitter) consume.
        if (no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes.isStrictlyInternal(namedType.getName())) {
            return null;
        }
        if (no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes.isPublished(namedType.getName())
                && !retainedSupportTypes().contains(namedType.getName())) {
            return null;
        }
        // A consumer coordinate referencing a strictly internal support type is an authoring
        // mistake; reject it here (typed, validate-time) rather than letting the skip above
        // leave a dangling GraphQLTypeReference that fails at consumer schema-build time.
        var internalReferenceRejection = rejectStrictlyInternalReferences(namedType);
        if (internalReferenceRejection != null) {
            return internalReferenceRejection;
        }
        if (namedType instanceof graphql.schema.GraphQLEnumType enumType) {
            String inertness = checkEnumArgMappingInert(enumType);
            if (inertness != null) {
                return new UnclassifiedType(enumType.getName(), locationOf(enumType), Rejection.structural(inertness));
            }
            var specs = new ArrayList<no.sikt.graphitron.rewrite.model.EnumValueSpec>();
            for (var value : enumType.getValues()) {
                String runtimeValue = argString(value, DIR_FIELD, ARG_NAME).orElse(value.getName());
                String desc = (value.getDescription() != null && !value.getDescription().isEmpty())
                    ? value.getDescription() : null;
                String depReason = value.isDeprecated() ? value.getDeprecationReason() : null;
                specs.add(new no.sikt.graphitron.rewrite.model.EnumValueSpec(
                    value.getName(), runtimeValue, desc, depReason, value));
            }
            return new no.sikt.graphitron.rewrite.model.GraphitronType.EnumType(
                enumType.getName(), locationOf(enumType), List.copyOf(specs), enumType);
        }
        if (namedType instanceof GraphQLInputObjectType inputType) {
            return buildInputType(inputType);
        }

        String name = namedType.getName();
        SourceLocation location = locationOf(namedType);

        if (namedType instanceof GraphQLObjectType objType) {
            if (ROOT_TYPE_NAMES.contains(name)) {
                return new RootType(name, location);
            }
            var typeConflict = detectTypeDirectiveConflict(objType);
            if (typeConflict != null) {
                return new UnclassifiedType(name, location, typeConflict);
            }
            if (objType.hasAppliedDirective(DIR_TABLE)) {
                return buildTableType(objType);
            }
            if (objType.hasAppliedDirective(DIR_ERROR)) {
                return buildErrorType(objType);
            }
            // R96/R276: reflection-derived binding from the resolver is the only signal. A
            // reachable type with a resolved producer binding classifies into the appropriate
            // backed variant; the @record directive is deprecated and ignored (it never drives
            // classification).
            if (bindings.resolveResult(name).isPresent()) {
                return buildResultType(name, location);
            }
            // A directiveless object with no producer is left UNCLASSIFIED here: the type builder
            // cannot yet know what it is. It becomes a NestingType at the embedding edge if a
            // NestingField references it (R317 slice 3a, so NestingType implies a corresponding
            // NestingField by construction); a producer-backed carrier-shaped payload is bound to a
            // JooqTableRecordType at its visit in the field pass (R317 slice 3b, carrierTableBinding);
            // anything else is an orphan, caught at the field edge where the field referencing it
            // classifies as UnclassifiedField.
            return null;
        }
        if (namedType instanceof GraphQLInterfaceType iface) {
            if (iface.hasAppliedDirective(DIR_TABLE) && iface.hasAppliedDirective(DIR_DISCRIMINATE)) {
                return buildTableInterfaceType(iface);
            }
            // R317 slice 1 — classify a plain interface with its participants at the moment the walk
            // reaches it, folding the former second-pass enrichment onto the node visit.
            // {@link #participantClassification} is registry-free (slice 3a), so the participant list
            // is a pure function of SDL + the reflection fixed point and reads nothing from the
            // still-being-populated type registry; the verdict is therefore identical to the one the
            // separate enrich pass produced.
            var participants = buildParticipantList(implementorNames(name), true, null);
            if (participants.error() != null) {
                return new UnclassifiedType(name, location, Rejection.structural(participants.error()));
            }
            return new InterfaceType(name, location, participants.list());
        }
        if (namedType instanceof GraphQLUnionType union) {
            // R317 slice 1 — see the interface arm above: union members are classified into
            // participants at the union's own visit, not in a trailing enrich pass.
            var memberNames = union.getTypes().stream().map(t -> t.getName()).toList();
            var participants = buildParticipantList(memberNames, false, null);
            if (participants.error() != null) {
                return new UnclassifiedType(name, location, Rejection.structural(participants.error()));
            }
            return new UnionType(name, location, participants.list());
        }
        return null;
    }

    /**
     * Published support types ({@link no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes#published()})
     * referenced from at least one coordinate of a non-support type: field return types, argument
     * types, and input field types. Computed once per build over the assembled schema. No
     * transitive closure is needed: the only support types referencing another support type are
     * never retained themselves, so a single scan over non-support coordinates suffices.
     */
    private Set<String> retainedSupportTypes() {
        if (retainedSupportTypes != null) {
            return retainedSupportTypes;
        }
        var retained = new java.util.HashSet<String>();
        for (var named : ctx.schema.getAllTypesAsList()) {
            if (named.getName().startsWith("__")) continue;
            if (no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes.isSupportType(named.getName())) continue;
            forEachReferencedType(named, (coordinate, referencedName) -> {
                if (no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes.isPublished(referencedName)) {
                    retained.add(referencedName);
                }
            });
        }
        retainedSupportTypes = Set.copyOf(retained);
        return retainedSupportTypes;
    }

    /**
     * Rejects {@code namedType} when one of its coordinates references a strictly internal
     * support type. The skip of those types above would otherwise leave a dangling
     * {@code GraphQLTypeReference} in generated code that fails late and untyped at consumer
     * schema-build time; this surfaces the mistake as a typed {@link Rejection.AuthorError}
     * the validator reports with the offending coordinate.
     */
    private GraphitronType rejectStrictlyInternalReferences(GraphQLNamedType namedType) {
        if (no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes.isSupportType(namedType.getName())) {
            return null;  // support types may reference each other (FieldSort.direction)
        }
        var offenses = new ArrayList<String>();
        forEachReferencedType(namedType, (coordinate, referencedName) -> {
            if (no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes.isStrictlyInternal(referencedName)) {
                offenses.add("'" + coordinate + "' references graphitron-internal type '" + referencedName + "'");
            }
        });
        if (offenses.isEmpty()) {
            return null;
        }
        String message = String.join("; ", offenses)
            + ". These types exist only to shape Graphitron's build-time directive arguments and never reach"
            + " the published schema; declare a consumer-owned type instead.";
        return new UnclassifiedType(namedType.getName(), locationOf(namedType), Rejection.structural(message));
    }

    /**
     * Walks every type-referencing coordinate of {@code type}: field return types and argument
     * types on objects and interfaces, input field types on input objects. The consumer receives
     * the coordinate as {@code Type.field} / {@code Type.field(arg:)} prose and the unwrapped
     * (non-null / list stripped) referenced type name.
     */
    private static void forEachReferencedType(GraphQLNamedType type,
                                              java.util.function.BiConsumer<String, String> consumer) {
        if (type instanceof graphql.schema.GraphQLFieldsContainer container) {
            for (var field : container.getFieldDefinitions()) {
                consumer.accept(container.getName() + "." + field.getName(),
                    GraphQLTypeUtil.unwrapAll(field.getType()).getName());
                for (var arg : field.getArguments()) {
                    consumer.accept(container.getName() + "." + field.getName() + "(" + arg.getName() + ":)",
                        GraphQLTypeUtil.unwrapAll(arg.getType()).getName());
                }
            }
        } else if (type instanceof GraphQLInputObjectType inputObject) {
            for (var field : inputObject.getFieldDefinitions()) {
                consumer.accept(inputObject.getName() + "." + field.getName(),
                    GraphQLTypeUtil.unwrapAll(field.getType()).getName());
            }
        }
    }

    /**
     * Classifies a {@link graphql.schema.GraphQLScalarType} via the {@link ScalarTypeResolver}.
     * Resolution order (Phase 3):
     *
     * <ul>
     *   <li><b>Spec built-ins</b> ({@code Int}, {@code Float}, {@code String}, {@code Boolean},
     *       {@code ID}) resolve through the resolver's closed built-in table. {@code @scalarType}
     *       on a spec built-in is a {@link Rejection.InvalidSchema.DirectiveConflict}.</li>
     *   <li><b>Federation-namespace scalars</b> ({@code federation__FieldSet},
     *       {@code federation__Scope}, etc.) resolve via
     *       {@link ScalarTypeResolver#resolveFederationNamespaceScalar(String)} to a
     *       {@link ScalarResolution.Synthesised} carrying the SDL name and the
     *       {@code _Any.type.getCoercing()} source. The schema generator inlines a
     *       {@code GraphQLScalarType.newScalar()...build()} registration; directive-argument
     *       slots reference the scalar via {@code GraphQLTypeReference.typeRef(name)}.</li>
     *   <li><b>{@code @scalarType(scalar: "FQN.FIELD")}</b> on the SDL scalar: the resolver looks
     *       up the named class + field through {@link BuildContext#codegenLoader}, validates the
     *       field, and reflects on the {@code Coercing<I, O>} type parameters.</li>
     *   <li><b>Extended-scalars convention layer</b>: the SDL name is matched against the
     *       resolver's built-in convention table (e.g. {@code BigDecimal} →
     *       {@code graphql.scalars.ExtendedScalars.GraphQLBigDecimal}). The convention only
     *       resolves when the named constant is on the consumer's classpath.</li>
     *   <li><b>Unresolved</b>: hard validation error with a message naming the scalar and
     *       pointing at {@code @scalarType(scalar:)} as the fix.</li>
     * </ul>
     */
    private GraphitronType classifyScalarType(graphql.schema.GraphQLScalarType scalarType) {
        String name = scalarType.getName();
        SourceLocation location = locationOf(scalarType);
        // SDL-level directive presence: graphql-java strips applied directives from spec built-in
        // redeclarations, so the assembled GraphQLScalarType is unreliable for "user wrote
        // @scalarType String { ... }". The build pre-pass in GraphitronSchemaBuilder copies the
        // SDL applied-directive names onto BuildContext so the check here sees the directive even
        // for built-ins. For non-built-ins, the assembled scalar still carries the directive and
        // the two sources agree.
        boolean sdlHasScalarType = ctx.sdlScalarDirectiveNames(name).contains(DIR_SCALAR_TYPE);
        boolean assembledHasDirective = scalarType.hasAppliedDirective(DIR_SCALAR_TYPE);
        boolean hasDirective = sdlHasScalarType || assembledHasDirective;

        if (ScalarTypeResolver.isSpecBuiltIn(name)) {
            if (hasDirective) {
                return new UnclassifiedType(name, location,
                    new Rejection.InvalidSchema.DirectiveConflict(
                        List.of(DIR_SCALAR_TYPE),
                        "@" + DIR_SCALAR_TYPE + " is not allowed on the GraphQL spec built-in '"
                            + name + "' — the GraphQL spec and graphql-java already bind this "
                            + "scalar's Java type and Coercing. Remove the directive."));
            }
            var resolution = ScalarTypeResolver.resolveBuiltIn(name);
            if (resolution instanceof ScalarResolution.Resolved r) {
                return new no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType(name, location, r, scalarType);
            }
            return new UnclassifiedType(name, location, asRejection(resolution, name));
        }

        if (ScalarTypeResolver.isFederationNamespaceScalar(name)) {
            // Federation-namespace names (federation__FieldSet etc.) appear in the assembled
            // schema as scalar types when the consumer @link's the federation spec but
            // federation-jvm exposes no public-static-final constant for the renamed forms.
            // The resolver returns a Synthesised carrier; GraphitronSchemaClassGenerator emits
            // an inline GraphQLScalarType.newScalar()...build() registration with
            // _Any.type.getCoercing() borrowed, and directive-argument slots reference it via
            // GraphQLTypeReference.typeRef(name).
            var resolution = ScalarTypeResolver.resolveFederationNamespaceScalar(name);
            if (resolution instanceof ScalarResolution.Successful s) {
                return new no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType(name, location, s, scalarType);
            }
            return new UnclassifiedType(name, location, asRejection(resolution, name));
        }

        if (hasDirective) {
            String scalarFqn = argString(scalarType, DIR_SCALAR_TYPE, ARG_SCALAR).orElse("");
            if (scalarFqn.isBlank()) {
                return new UnclassifiedType(name, location, Rejection.structural(
                    "@" + DIR_SCALAR_TYPE + " requires a non-blank scalar reference of the form "
                        + "'fully.qualified.Class.FIELD' pointing at a public static final GraphQLScalarType."));
            }
            var resolution = ScalarTypeResolver.resolveFromDirectiveValue(scalarFqn, name, ctx.codegenLoader());
            if (resolution instanceof ScalarResolution.Successful s) {
                return new no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType(name, location, s, scalarType);
            }
            return new UnclassifiedType(name, location, asRejection(resolution, name));
        }

        // Phase 3 convention layer: try the extended-scalars table before escalating. The table
        // resolves when both the SDL name is recognised AND the named constant is on the
        // consumer's classpath; the second clause produces a ClassNotFound rejection the
        // unresolved escalation collapses into a single author-facing message.
        var convention = ScalarTypeResolver.resolveByConvention(name, ctx.codegenLoader());
        if (convention instanceof ScalarResolution.Successful s) {
            return new no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType(name, location, s, scalarType);
        }

        // Unresolved: scalar is neither a spec built-in, a federation-namespace name, an
        // @scalarType-declared scalar, nor in the extended-scalars convention table (with the
        // artifact on classpath). Surface as a hard validation error with the fix in the message.
        return new UnclassifiedType(name, location, Rejection.structural(
            "scalar '" + name + "' is not resolvable to a Java type. Add "
                + "@" + DIR_SCALAR_TYPE + "(" + ARG_SCALAR + ": \"fully.qualified.Class.FIELD\") "
                + "pointing at a public static final GraphQLScalarType, or add "
                + "graphql-java-extended-scalars to the project classpath if the SDL name "
                + "matches a convention-table entry."));
    }

    /**
     * Projects a {@link ScalarResolution.Rejected} arm to a {@link Rejection} the validator
     * surfaces alongside the rest of the type-classification rejections. Each arm carries the
     * structured payload the per-arm LSP fix-it consumes (Phase 4); the prose here is the
     * build-log surface only.
     */
    private static Rejection asRejection(ScalarResolution resolution, String scalarName) {
        return switch (resolution) {
            case ScalarResolution.Successful ignored ->
                throw new IllegalStateException("asRejection invoked on Successful");
            case ScalarResolution.Rejected.ClassNotFound r -> Rejection.structural(
                "scalar '" + scalarName + "': @scalarType references class '" + r.fqn()
                    + "' which is not on the codegen classpath.");
            case ScalarResolution.Rejected.FieldNotFound r -> Rejection.structural(
                "scalar '" + scalarName + "': @scalarType references field '" + r.fieldName()
                    + "' on '" + r.className() + "' which does not exist.");
            case ScalarResolution.Rejected.FieldNotAccessible r -> Rejection.structural(
                "scalar '" + scalarName + "': @scalarType references '" + r.className() + "."
                    + r.fieldName() + "' which is not public static (isPublic=" + r.isPublic()
                    + ", isStatic=" + r.isStatic() + ").");
            case ScalarResolution.Rejected.NullAtCodegen r -> Rejection.structural(
                "scalar '" + scalarName + "': @scalarType references '" + r.className() + "."
                    + r.fieldName() + "' which evaluates to null at codegen.");
            case ScalarResolution.Rejected.NotAScalarType r -> Rejection.structural(
                "scalar '" + scalarName + "': @scalarType references '" + r.className() + "."
                    + r.fieldName() + "' whose type is '" + r.actualTypeFqn()
                    + "', not graphql.schema.GraphQLScalarType.");
            case ScalarResolution.Rejected.CoercingErased r -> Rejection.structural(
                "scalar '" + scalarName + "': the Coercing on '" + r.coercingClass()
                    + "' has erased type parameters (" + r.declarationKind()
                    + "). Declare concrete <Input, Output> parameters so the resolver can "
                    + "recover the Java type without falling back to Object.");
        };
    }

    private GraphitronType buildTableType(GraphQLObjectType objType) {
        String name = objType.getName();
        SourceLocation location = locationOf(objType);
        String tableName = argString(objType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = svc.resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, ctx.unknownTableRejection(tableName));
        }
        TableRef tableRef = tableOpt.get();

        // Platform-id synthesis. The malformed-metadata diagnostic runs unconditionally so SDL
        // authors see the issue even when they try to override values with explicit @node.
        // Beyond that, NodeType promotion is opt-in via `implements Node @node`: a `@table` type
        // without `@node` stays a TableType regardless of whether the backing jOOQ class carries
        // node-id metadata. Auto-promoting on metadata alone silently collided typeIds across
        // types whose backing tables shared `__NODE_TYPE_ID`, with no SDL-side opt-out.
        Optional<String> metadataDiagnostic = ctx.catalog.nodeIdMetadataDiagnostic(tableRef.tableName());
        if (metadataDiagnostic.isPresent()) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "KjerneJooqGenerator metadata on table '" + tableRef.tableName() + "' is malformed: "
                + metadataDiagnostic.get()));
        }
        Optional<JooqCatalog.NodeIdMetadata> metadata = ctx.catalog.nodeIdMetadata(tableRef.tableName());

        boolean hasNode = objType.hasAppliedDirective(DIR_NODE);
        if (!hasNode) {
            return new TableType(name, location, tableRef);
        }

        // @node declared — the type must implement the Relay Node interface (id: ID!).
        // `implements Node` is a schema-level contract published to clients; we cannot promote
        // a type to NodeType without it.
        if (!implementsNode(objType)) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "@node requires the type to implement the Relay Node interface — add 'implements Node' to the type declaration"));
        }

        // Resolve SDL-declared values.
        String sdlTypeId = argString(objType, DIR_NODE, ARG_TYPE_ID).orElse(null);
        List<String> sdlKeyColumnNames = argStringList(objType, DIR_NODE, ARG_KEY_COLUMNS);
        var keyColumnErrors = new ArrayList<String>();
        var sdlKeyColumns = new ArrayList<ColumnRef>();
        for (String colName : sdlKeyColumnNames) {
            Optional<ColumnRef> kc = svc.resolveKeyColumn(colName, tableRef.tableName());
            if (kc.isEmpty()) {
                keyColumnErrors.add("key column '" + colName + "' in @node could not be resolved in the jOOQ table"
                    + candidateHint(colName, ctx.catalog.columnJavaNamesOf(tableRef.tableName())));
            } else {
                sdlKeyColumns.add(kc.get());
            }
        }
        if (!keyColumnErrors.isEmpty()) {
            return new UnclassifiedType(name, location, Rejection.structural(String.join("; ", keyColumnErrors)));
        }

        if (metadata.isEmpty()) {
            // @node-only path: SDL values win verbatim on any declared axis; fill the omitted
            // ones from sensible defaults (docs: typeId defaults to type name, keyColumns to PK).
            String resolvedTypeId = sdlTypeId != null ? sdlTypeId : name;
            List<ColumnRef> resolvedKeyColumns;
            if (!sdlKeyColumnNames.isEmpty()) {
                resolvedKeyColumns = List.copyOf(sdlKeyColumns);
            } else {
                var pk = ctx.catalog.findPkColumns(tableRef.tableName()).stream()
                    .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()))
                    .toList();
                if (pk.isEmpty()) {
                    return new UnclassifiedType(name, location, Rejection.structural(
                        "@node on " + name + " omits keyColumns but table '" + tableRef.tableName()
                        + "' has no primary key — declare `keyColumns:` on @node or add a primary key"));
                }
                resolvedKeyColumns = pk;
            }
            return buildNodeType(name, location, tableRef, resolvedTypeId, resolvedKeyColumns);
        }

        // Both @node and metadata present. SDL wins — it is the author's published wire-format
        // contract, decoupled from whatever the jOOQ generator happens to output:
        //  - typeId: SDL overrides silently. The entire point of @node(typeId:) is to let
        //    authors pin the wire format independent of the jOOQ table name.
        //  - keyColumns: SDL overrides. If the column *sets* differ, one side is wrong about
        //    the schema — hard error. If sets are equal but *order* differs, SDL wins with a
        //    WARN (the author pinned a specific order; worth surfacing but not blocking).
        //  - Values omitted on an axis fall through to metadata.
        var meta = metadata.get();
        String resolvedTypeId = sdlTypeId != null ? sdlTypeId : meta.typeId();
        List<ColumnRef> resolvedKeyColumns;
        if (sdlKeyColumnNames.isEmpty()) {
            resolvedKeyColumns = List.copyOf(meta.keyColumns());
        } else {
            if (!columnSetsMatch(sdlKeyColumns, meta.keyColumns())) {
                return new UnclassifiedType(name, location, Rejection.structural(
                    "@node(keyColumns: " + keyColumnsLiteral(sdlKeyColumns)
                    + ") on " + name + " disagrees with KjerneJooqGenerator metadata (keyColumns: "
                    + keyColumnsLiteral(meta.keyColumns())
                    + ") — the column sets are different; one side is wrong about the schema"));
            }
            if (!columnListsMatch(sdlKeyColumns, meta.keyColumns())) {
                LOGGER.warn("@node(keyColumns: {}) on {} pins an order different from KjerneJooqGenerator metadata ({}); SDL order wins",
                    keyColumnsLiteral(sdlKeyColumns), name, keyColumnsLiteral(meta.keyColumns()));
            }
            resolvedKeyColumns = List.copyOf(sdlKeyColumns);
        }
        return buildNodeType(name, location, tableRef, resolvedTypeId, resolvedKeyColumns);
    }

    /**
     * Constructs a {@link NodeType} with pre-resolved {@link HelperRef} references for the
     * per-type {@code encode<TypeName>} / {@code decode<TypeName>} helpers. The encoder class is
     * the same {@link NodeIdEncoderClassGenerator#CLASS_NAME} emitted under
     * {@code outputPackage + ".util"}; the helper method name is derived from the GraphQL type
     * name (not the {@code typeId}, which is the wire string and may differ).
     */
    private NodeType buildNodeType(String name, SourceLocation location, TableRef tableRef,
                                   String typeId, List<ColumnRef> keyColumns) {
        ClassName encoderClass = ClassName.get(
            ctx.ctx.outputPackage() + ".util",
            NodeIdEncoderClassGenerator.CLASS_NAME);
        var encodeMethod = new HelperRef.Encode(encoderClass, "encode" + name, keyColumns);
        var decodeMethod = new HelperRef.Decode(encoderClass, "decode" + name, keyColumns);
        return new NodeType(name, location, tableRef, typeId, keyColumns, encodeMethod, decodeMethod);
    }

    private static boolean implementsNode(GraphQLObjectType objType) {
        return objType.getInterfaces().stream()
            .anyMatch(i -> "Node".equals(((GraphQLNamedType) i).getName()));
    }

    private static boolean columnListsMatch(List<ColumnRef> a, List<ColumnRef> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sqlName().equalsIgnoreCase(b.get(i).sqlName())) return false;
        }
        return true;
    }

    private static boolean columnSetsMatch(List<ColumnRef> a, List<ColumnRef> b) {
        if (a.size() != b.size()) return false;
        var aNames = a.stream().map(c -> c.sqlName().toLowerCase()).collect(Collectors.toSet());
        var bNames = b.stream().map(c -> c.sqlName().toLowerCase()).collect(Collectors.toSet());
        return aNames.equals(bNames);
    }

    private static String keyColumnsLiteral(List<ColumnRef> cols) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(cols.get(i).sqlName()).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * Constructs the appropriate {@link ResultType} sub-type from the resolved backing class.
     * R96/R276: {@link RecordBindingResolver} reflection is the only source. This is reached only
     * for a type with a resolved producer binding (gated in {@link #classifyType}); the
     * {@code @record} directive is deprecated and ignored, surfaced by the directive-ignored
     * warning at {@link #emitDirectiveIgnoredWarning} rather than consulted here.
     */
    private GraphitronType buildResultType(String name, SourceLocation location) {
        Class<?> cls = bindings.resolveResult(name).orElseThrow(() -> new IllegalStateException(
            "buildResultType reached for '" + name + "' without a reflected producer binding; "
            + "classifyType must gate on bindings.resolveResult(name).isPresent()"));
        String className = cls.getName();
        if (cls.isRecord()) {
            return new GraphitronType.JavaRecordType(name, location, className);
        }
        if (org.jooq.TableRecord.class.isAssignableFrom(cls)) {
            TableRef table = svc.resolveTableByRecordClass(cls).orElse(null);
            return new GraphitronType.JooqTableRecordType(name, location, className, table);
        }
        if (org.jooq.Record.class.isAssignableFrom(cls)) {
            return new GraphitronType.JooqRecordType(name, location, className);
        }
        return new GraphitronType.PojoResultType.Backed(name, location, className);
    }

    private GraphitronType buildTableInterfaceType(GraphQLInterfaceType iface) {
        String name = iface.getName();
        SourceLocation location = locationOf(iface);
        String tableName = argString(iface, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = svc.resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, ctx.unknownTableRejection(tableName));
        }
        String discriminatorRaw = argString(iface, DIR_DISCRIMINATE, ARG_ON).orElse(null);
        // Resolve to the SQL column name so generators can use DSL.name(col) with the correct
        // casing. findColumn accepts both Java names and SQL names. Falls back to the raw value
        // when unresolvable (the validator will report the bad column name).
        JooqCatalog.ColumnEntry discriminatorEntry = discriminatorRaw == null ? null
            : ctx.catalog.findColumn(tableOpt.get().tableName(), discriminatorRaw).orElse(null);
        String discriminatorColumn = discriminatorEntry != null ? discriminatorEntry.sqlName() : discriminatorRaw;
        // R317 slice 1 — enrich at classify time (see the interface arm of classifyType). The
        // single-table interface passes its own table so each participant's cross-table fields are
        // detected against it.
        var participants = buildParticipantList(implementorNames(name), false, tableOpt.get());
        if (participants.error() != null) {
            return new UnclassifiedType(name, location, Rejection.structural(participants.error()));
        }
        return new TableInterfaceType(name, location, discriminatorColumn, tableOpt.get(), participants.list());
    }

    private GraphitronType buildErrorType(GraphQLObjectType objType) {
        String name = objType.getName();
        SourceLocation location = locationOf(objType);

        // Structural field check: every @error type declares path: [String!]! and message: String!.
        // Extras are admitted here and validated against each handler's source class by the
        // per-(channel, @error type, handler) accessor check on the carrier (FieldBuilder).
        List<String> rejectReasons = new ArrayList<>();
        var pathField = objType.getFieldDefinition("path");
        if (pathField == null) {
            rejectReasons.add("missing required field 'path: [String!]!'");
        } else if (!isStringNonNullListNonNull(pathField.getType())) {
            rejectReasons.add("'path' must be declared as [String!]! (got '"
                + GraphQLTypeUtil.simplePrint(pathField.getType()) + "')");
        }
        var messageField = objType.getFieldDefinition("message");
        if (messageField == null) {
            rejectReasons.add("missing required field 'message: String!'");
        } else if (!isStringNonNull(messageField.getType())) {
            rejectReasons.add("'message' must be declared as String! (got '"
                + GraphQLTypeUtil.simplePrint(messageField.getType()) + "')");
        }

        var dir = objType.getAppliedDirective(DIR_ERROR);
        var handlersArg = dir.getArgument(ARG_HANDLERS);
        Object value = handlersArg.getValue();
        List<?> items = value instanceof List<?> l ? l : value == null ? List.of() : List.of(value);
        List<ErrorType.Handler> handlers = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map)) continue;
            ErrorType.Handler h = parseErrorHandler(asMap(item), rejectReasons);
            if (h != null) handlers.add(h);
        }

        if (!rejectReasons.isEmpty()) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "@error type rejected: " + String.join("; ", rejectReasons)));
        }
        return new ErrorType(name, location, List.copyOf(handlers));
    }

    private static boolean isStringNonNull(GraphQLType type) {
        if (!(type instanceof GraphQLNonNull nn)) return false;
        return nn.getWrappedType() instanceof GraphQLScalarType st && "String".equals(st.getName());
    }

    private static boolean isStringNonNullListNonNull(GraphQLType type) {
        if (!(type instanceof GraphQLNonNull outer)) return false;
        if (!(outer.getWrappedType() instanceof GraphQLList list)) return false;
        return isStringNonNull(list.getWrappedType());
    }

    private GraphitronType buildInputType(GraphQLInputObjectType inputType) {
        String name = inputType.getName();
        SourceLocation location = locationOf(inputType);
        // R96: @table wins on the (@table + @record) combination on input types. The legacy
        // emission that surfaced the redundancy as a standalone warning here is removed; the
        // signal is now carried by the "Shadowed by @table" variant of the directive-ignored
        // warning, emitted per-type during the classification pass in emitDirectiveIgnoredWarning.
        if (inputType.hasAppliedDirective(DIR_TABLE)) {
            String tableName = argString(inputType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
            Optional<TableRef> tableOpt = svc.resolveTable(tableName);
            if (tableOpt.isEmpty()) {
                return new UnclassifiedType(name, location, ctx.unknownTableRejection(tableName));
            }
            return buildTableInputType(name, location, inputType.getFieldDefinitions(), tableOpt.get(), inputType);
        }
        if (isUsedWithOverrideCondition(name)) {
            return buildNonTableInputType(inputType, name, location);
        }
        var tables = findReturnTablesForInput(name);
        if (tables.isEmpty()) {
            return buildNonTableInputType(inputType, name, location);
        }
        if (tables.size() > 1) {
            return buildNonTableInputType(inputType, name, location);
        }
        return buildTableInputType(name, location, inputType.getFieldDefinitions(), tables.values().iterator().next(), inputType);
    }

    /**
     * Resolves a list of raw input fields against a {@link TableRef} into a {@link TableInputType}.
     */
    GraphitronType buildTableInputType(String name, SourceLocation location,
            List<GraphQLInputObjectField> fields, TableRef tableRef, GraphQLInputObjectType inputType) {
        var failures = new ArrayList<InputFieldResolution.Unresolved>();
        var conditionErrors = new ArrayList<String>();
        var resolvedFields = new ArrayList<InputField>();
        for (var f : fields) {
            // R215 §3: @table input column-coverage is deferred to consumption. The classifier
            // already lifts column-miss to InputField.UnboundField; non-column-miss failures
            // (notGenerated, @reference path, NodeId resolution, circular nesting) remain
            // Unresolved and surface here.
            var resolution = ctx.classifyInputField(f, name, tableRef, ClassifyContext.root(), conditionErrors);
            switch (resolution) {
                case InputFieldResolution.Resolved r -> resolvedFields.add(r.field());
                case InputFieldResolution.Unresolved u -> failures.add(u);
            }
        }
        if (!failures.isEmpty()) {
            String reasons = failures.stream()
                .map(u -> "'" + u.fieldName() + "': " + u.reason())
                .collect(Collectors.joining("; "));
            String hint = failures.stream()
                .map(InputFieldResolution.Unresolved::lookupColumn)
                .filter(c -> c != null)
                .findFirst()
                .map(c -> candidateHint(c, ctx.catalog.columnJavaNamesOf(tableRef.tableName())))
                .orElse("");
            return new UnclassifiedType(name, location, Rejection.structural(
                "mapped to table '" + tableRef.tableName() + "' — unresolvable fields: " + reasons + hint));
        }
        if (!conditionErrors.isEmpty()) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "mapped to table '" + tableRef.tableName() + "' — bad @condition on fields: "
                + String.join("; ", conditionErrors)));
        }
        var shape = buildInputRecordShape(name, inputType);
        if (shape == null) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "mapped to table '" + tableRef.tableName() + "' — input-record component types could not be resolved"));
        }
        return new TableInputType(name, location, tableRef, List.copyOf(resolvedFields), inputType, shape);
    }

    /**
     * Constructs the appropriate {@link InputType} sub-type from the resolved backing class.
     * R96/R276: symmetric with {@link #buildResultType}, {@link RecordBindingResolver} reflection
     * is the only source. An input type with no reflected producer binding is a backing-less
     * {@link GraphitronType.PojoInputType}; the {@code @record} directive is deprecated and
     * ignored (it never supplies a fallback className).
     */
    private GraphitronType buildNonTableInputType(GraphQLInputObjectType inputType, String name, SourceLocation location) {
        var shape = buildInputRecordShape(name, inputType);
        if (shape == null) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "input-record component types could not be resolved for '" + name + "'"));
        }
        Class<?> cls = bindings.resolveInput(name).orElse(null);
        if (cls == null) {
            return new GraphitronType.PojoInputType(name, location, null, inputType, shape);
        }
        String className = cls.getName();
        if (cls.isRecord()) {
            return new GraphitronType.JavaRecordInputType(name, location, className, inputType, shape);
        }
        if (org.jooq.TableRecord.class.isAssignableFrom(cls)) {
            TableRef table = svc.resolveTableByRecordClass(cls).orElse(null);
            return new GraphitronType.JooqTableRecordInputType(name, location, className, table, inputType, shape);
        }
        if (org.jooq.Record.class.isAssignableFrom(cls)) {
            return new GraphitronType.JooqRecordInputType(name, location, className, inputType, shape);
        }
        return new GraphitronType.PojoInputType(name, location, className, inputType, shape);
    }

    /**
     * Derives the graphitron-emitted record shape for one SDL input type, walking each declared
     * field and resolving its Java type. SDL scalar fields lift via R101's
     * {@link no.sikt.graphitron.rewrite.ScalarTypeResolver}; SDL enum fields lift to
     * {@code String} (graphql-java delivers enum values as their name string); SDL list wraps
     * compose {@code List<X>}; nested input refs resolve to the emitted record's
     * {@link ClassName} (forward-declared — javapoet does not require the class to exist at
     * codegen). An SDL field whose scalar fails to classify surfaces as a {@code null} return,
     * causing the caller to route the input type through {@link UnclassifiedType}.
     *
     * <p>Producer-side rejection: empty {@code components} fails the compact constructor on
     * {@link InputRecordShape} (an SDL input type without fields is structurally rejected by
     * graphql-java earlier in the pipeline, so the guard is defence in depth).
     */
    private InputRecordShape buildInputRecordShape(String name, GraphQLInputObjectType inputType) {
        ClassName recordClass = ClassName.get(ctx.ctx.outputPackage() + ".inputs", name);
        var fields = inputType.getFieldDefinitions();
        if (fields.isEmpty()) {
            return null;
        }
        var components = new ArrayList<InputComponent>(fields.size());
        for (var f : fields) {
            var resolution = resolveInputFieldJavaType(f.getType());
            if (resolution == null) {
                return null;
            }
            components.add(new InputComponent(
                f.getName(),
                f.getName(),
                resolution.javaType(),
                resolution.nullable()
            ));
        }
        return new InputRecordShape(recordClass, components);
    }

    private record InputFieldTypeResolution(TypeName javaType, boolean nullable) {}

    /**
     * Walks an SDL {@code GraphQLInputType} (with its non-null and list wrappers) into a Java
     * {@link TypeName} for an {@link InputComponent}. Returns {@code null} when a leaf type
     * cannot be resolved (scalar with no classification, etc.); the caller maps that into
     * {@link UnclassifiedType} on the parent input type.
     *
     * <p>List wrapping always boxes in {@code java.util.List}; the {@code nullable} flag tracks
     * the outermost non-null wrap on the field declaration, not on the list element.
     */
    private InputFieldTypeResolution resolveInputFieldJavaType(GraphQLType type) {
        boolean nullable = true;
        GraphQLType current = type;
        if (current instanceof GraphQLNonNull nn) {
            nullable = false;
            current = nn.getWrappedType();
        }
        TypeName javaType = resolveInputElementJavaType(current);
        if (javaType == null) {
            return null;
        }
        return new InputFieldTypeResolution(javaType, nullable);
    }

    private TypeName resolveInputElementJavaType(GraphQLType type) {
        GraphQLType current = type;
        if (current instanceof GraphQLNonNull nn) {
            current = nn.getWrappedType();
        }
        if (current instanceof GraphQLList list) {
            TypeName elem = resolveInputElementJavaType(list.getWrappedType());
            if (elem == null) return null;
            return ParameterizedTypeName.get(ClassName.get(List.class), elem);
        }
        if (current instanceof GraphQLInputObjectType nested) {
            // Forward reference to the sibling emitted record; javapoet does not require the
            // referenced class to exist at codegen, so mutually recursive input types resolve
            // cleanly without a topological sort over input types.
            return ClassName.get(ctx.ctx.outputPackage() + ".inputs", nested.getName());
        }
        if (current instanceof graphql.schema.GraphQLEnumType) {
            // graphql-java delivers enum values as their declared name string at the
            // argument-binding seam; the record component stores the raw String so the
            // validator's reflection walks a stable Java type. The actual enum semantics ride
            // at jOOQ-bind time via DSL.val(rawValue, col.getDataType()).
            return ClassName.get(String.class);
        }
        if (current instanceof GraphQLScalarType scalar) {
            var resolution = ScalarTypeResolver.resolveBuiltIn(scalar.getName());
            if (resolution instanceof ScalarResolution.Resolved r) {
                return r.javaType();
            }
            // Consumer-declared scalar that didn't resolve as a spec built-in: defer to whatever
            // the ScalarType classifier produced, if any. The classifier has either succeeded
            // (the entry is a GraphitronType.ScalarType on ctx.typeRegistry) or rejected the
            // type; if it rejected, the surrounding input type would already be UnclassifiedType
            // by reachable-closure on the input field. Treat absence as Object so the validator
            // walk still works; jOOQ rebinds at value-set time via getDataType().
            var classified = ctx.typeRegistry.get(scalar.getName());
            if (classified instanceof GraphitronType.ScalarType st) {
                return st.resolution().javaType();
            }
            return ClassName.get(Object.class);
        }
        return null;
    }

    /**
     * Walks all field definitions in the schema to find which tables are referenced by fields
     * that accept a given input type (excluding @service, @tableMethod, @mutation fields).
     */
    private Map<String, TableRef> findReturnTablesForInput(String inputTypeName) {
        var tables = new LinkedHashMap<String, TableRef>();
        for (var namedType : ctx.schema.getAllTypesAsList()) {
            if (!(namedType instanceof GraphQLObjectType objType)) continue;
            if (namedType.getName().startsWith("__")) continue;
            for (var fieldDef : objType.getFieldDefinitions()) {
                if (fieldDef.hasAppliedDirective(DIR_SERVICE)
                        || fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)
                        || fieldDef.hasAppliedDirective(DIR_MUTATION)) continue;
                boolean usesInput = fieldDef.getArguments().stream()
                    .filter(arg -> !arg.hasAppliedDirective(DIR_ORDER_BY))
                    .anyMatch(arg -> inputTypeName.equals(
                        ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(arg.getType())).getName()));
                if (!usesInput) continue;
                var returnBase = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
                if (!(returnBase instanceof GraphQLObjectType returnObj)) continue;
                if (!returnObj.hasAppliedDirective(DIR_TABLE)) continue;
                String tableName = argString(returnObj, DIR_TABLE, ARG_NAME)
                    .orElse(returnObj.getName().toLowerCase());
                if (!tables.containsKey(tableName.toLowerCase())) {
                    svc.resolveTable(tableName).ifPresent(tr -> tables.put(tableName.toLowerCase(), tr));
                }
            }
        }
        return tables;
    }

    /**
     * Returns true if {@code inputTypeName} is used as the type of any field argument where
     * either the field or the argument carries {@code @condition(override: true)}. When override
     * is set, the consumer supplies custom condition code, so the input's fields should not be
     * validated against table columns.
     *
     * <p>Only argument-level and field-level {@code @condition} are inspected here;
     * {@code INPUT_FIELD_DEFINITION} override is also checked: if any field in the input type
     * itself carries {@code @condition(override: true)}, the whole type bypasses column validation.
     */
    private boolean isUsedWithOverrideCondition(String inputTypeName) {
        var typeFromSchema = ctx.schema.getType(inputTypeName);
        if (typeFromSchema instanceof GraphQLInputObjectType iot) {
            for (var fieldDef : iot.getFieldDefinitions()) {
                if (fieldDef.hasAppliedDirective(DIR_CONDITION)
                        && argBoolean(fieldDef, DIR_CONDITION, ARG_OVERRIDE, false)) return true;
            }
        }
        for (var namedType : ctx.schema.getAllTypesAsList()) {
            if (!(namedType instanceof GraphQLObjectType objType)) continue;
            if (namedType.getName().startsWith("__")) continue;
            for (var fieldDef : objType.getFieldDefinitions()) {
                boolean fieldOverride = fieldDef.hasAppliedDirective(DIR_CONDITION)
                    && argBoolean(fieldDef, DIR_CONDITION, ARG_OVERRIDE, false);
                for (var arg : fieldDef.getArguments()) {
                    String argTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(arg.getType())).getName();
                    if (!inputTypeName.equals(argTypeName)) continue;
                    if (fieldOverride) return true;
                    if (arg.hasAppliedDirective(DIR_CONDITION)
                            && argBoolean(arg, DIR_CONDITION, ARG_OVERRIDE, false)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Lifts one entry from the {@code handlers} array on an {@code @error} directive into the
     * sealed {@link ErrorType.Handler} variant matching the entry's discriminator. Returns
     * {@code null} and appends a reason to {@code rejectReasons} if the entry is unparseable
     * or violates one of the parse-time intra-handler reject rules (rules 1–5 and the
     * no-handler case in the {@code error-handling-parity} spec). Channel-level reject rules
     * (7–9) live with the carrier classifier; rule 6 (no fields beyond path/message) is
     * applied by the caller.
     */
    private ErrorType.Handler parseErrorHandler(Map<String, Object> item, List<String> rejectReasons) {
        Object handlerRaw = item.get(ARG_HANDLER);
        if (handlerRaw == null) {
            rejectReasons.add("@error handler entry missing required 'handler' field");
            return null;
        }
        ErrorHandlerType handlerType;
        try {
            handlerType = ErrorHandlerType.valueOf(handlerRaw.toString());
        } catch (IllegalArgumentException e) {
            rejectReasons.add("@error handler entry has unknown 'handler' value '" + handlerRaw
                + "' (expected GENERIC, DATABASE, or VALIDATION)");
            return null;
        }
        String className = strip(item.get(ARG_CLASS_NAME));
        String code = strip(item.get(ARG_CODE));
        String sqlState = strip(item.get(ARG_SQL_STATE));
        String matches = strip(item.get(ARG_MATCHES));
        String description = strip(item.get(ARG_DESCRIPTION));
        Optional<String> matchesOpt = Optional.ofNullable(matches);
        Optional<String> descriptionOpt = Optional.ofNullable(description);

        switch (handlerType) {
            case GENERIC -> {
                // Rule 1: GENERIC requires className.
                if (className == null) {
                    rejectReasons.add("@error handler {handler: GENERIC} missing required 'className'");
                    return null;
                }
                // Rule 2: GENERIC ignores SQL discriminators.
                if (sqlState != null || code != null) {
                    rejectReasons.add("@error handler {handler: GENERIC} cannot carry 'sqlState' or 'code'"
                        + " (those apply to DATABASE only)");
                    return null;
                }
                String resolveError = validateExceptionClass(className, "GENERIC");
                if (resolveError != null) {
                    rejectReasons.add(resolveError);
                    return null;
                }
                return new ErrorType.ExceptionHandler(className, matchesOpt, descriptionOpt);
            }
            case DATABASE -> {
                // Rule 3: DATABASE cannot AND both vendor discriminators.
                if (sqlState != null && code != null) {
                    rejectReasons.add("@error handler {handler: DATABASE} cannot carry both 'sqlState' and 'code'"
                        + " (vendor-conflicting); split into two entries — one per discriminator");
                    return null;
                }
                // Rule 4: DATABASE no longer matches on class identity; explicit className is misleading.
                if (className != null) {
                    rejectReasons.add("@error handler {handler: DATABASE} cannot carry 'className'"
                        + " (DATABASE matches any SQLException; use {handler: GENERIC, className: \"...\"} for class-narrowed matching)");
                    return null;
                }
                if (sqlState != null) {
                    return new ErrorType.SqlStateHandler(sqlState, matchesOpt, descriptionOpt);
                }
                if (code != null) {
                    return new ErrorType.VendorCodeHandler(code, matchesOpt, descriptionOpt);
                }
                // No-discriminator DATABASE lifts to ExceptionHandler(SQLException). The legacy
                // "DataAccessException-only" nominal match becomes a documented behaviour shift.
                // SQLException always resolves on the classifier classpath, so no Class.forName
                // check is needed here.
                return new ErrorType.ExceptionHandler("java.sql.SQLException", matchesOpt, descriptionOpt);
            }
            case VALIDATION -> {
                // Rule 5: VALIDATION takes neither discriminators nor matches.
                List<String> disallowed = new ArrayList<>();
                if (className != null) disallowed.add("className");
                if (sqlState != null) disallowed.add("sqlState");
                if (code != null) disallowed.add("code");
                if (matches != null) disallowed.add("matches");
                if (!disallowed.isEmpty()) {
                    rejectReasons.add("@error handler {handler: VALIDATION} cannot carry "
                        + String.join(", ", disallowed)
                        + " (validation runs as a wrapper pre-execution step against jakarta.validation.Validator; SQL discriminators do not apply)");
                    return null;
                }
                return new ErrorType.ValidationHandler(descriptionOpt);
            }
        }
        throw new IllegalStateException("unreachable: " + handlerType);
    }

    private static String strip(Object value) {
        if (value == null) return null;
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }

    /**
     * Resolves an {@code ExceptionHandler.exceptionClassName} on the classifier classpath and
     * verifies it's a {@link Throwable} subtype. Returns a non-null reject reason when the
     * class cannot be loaded or doesn't extend {@code Throwable}; returns {@code null} on a
     * clean resolution. Mirrors the {@code Class.forName} check already used by
     * {@code @record(record: {className: ...})} in {@link #buildResultType}.
     *
     * <p>The runtime matcher walks the cause chain testing each link with
     * {@code Class.isInstance}; a non-{@code Throwable} class would never match anything and
     * is almost certainly a typo.
     */
    // Instance method (not static) so it can read `ctx.codegenLoader()`; the single caller is
    // `parseErrorHandler` on the same class, which already holds `ctx`. The explicit-parameter
    // sibling lives at `CheckedExceptionMatcher.unmatched`, which crosses a class boundary.
    private String validateExceptionClass(String className, String handlerKind) {
        try {
            Class<?> cls = Class.forName(className, false, ctx.codegenLoader());
            if (!Throwable.class.isAssignableFrom(cls)) {
                return "@error handler {handler: " + handlerKind + ", className: \"" + className
                    + "\"} resolves to a class that does not extend java.lang.Throwable; "
                    + "the matcher walks the cause chain testing each link with isInstance, "
                    + "so a non-Throwable class would never match";
            }
            return null;
        } catch (ClassNotFoundException e) {
            return "@error handler {handler: " + handlerKind + ", className: \"" + className
                + "\"} could not be loaded on the classifier classpath";
        }
    }

    // ===== Conflict detection =====

    /**
     * Returns a reason string when mutually exclusive type-classification directives appear
     * together on one OBJECT, or {@code null} when the combination is allowed.
     *
     * <p>{@code @table} and {@code @error} are mutually exclusive. {@code @table} resolves columns
     * from jOOQ metadata; {@code @error} declares an SDL-side error shape (the runtime source is
     * the matched throwable itself; there is no developer-supplied data class for an {@code @error}
     * type).
     *
     * <p>R276: {@code @record} is deprecated and ignored, so its mere presence is not a conflict.
     * A {@code @table} + {@code @record} or {@code @error} + {@code @record} combination is allowed
     * to classify ({@code @table}/{@code @error} wins) and surfaces the directive-ignored warning
     * in {@link #emitDirectiveIgnoredWarning} rather than a hard rejection.
     */
    private static Rejection.InvalidSchema.DirectiveConflict detectTypeDirectiveConflict(GraphQLObjectType objType) {
        boolean hasTable = objType.hasAppliedDirective(DIR_TABLE);
        boolean hasError = objType.hasAppliedDirective(DIR_ERROR);
        int present = (hasTable ? 1 : 0) + (hasError ? 1 : 0);
        if (present > 1) {
            var bareNames = new java.util.ArrayList<String>();
            var atNames = new java.util.ArrayList<String>();
            if (hasTable)  { bareNames.add(DIR_TABLE);  atNames.add("@" + DIR_TABLE); }
            if (hasError)  { bareNames.add(DIR_ERROR);  atNames.add("@" + DIR_ERROR); }
            return new Rejection.InvalidSchema.DirectiveConflict(
                bareNames, String.join(", ", atNames) + " are mutually exclusive");
        }
        return null;
    }

    // ===== Structural helpers =====

    /**
     * Returns a rejection message when the {@code ExternalCodeReference} value at {@code ref}
     * carries a non-blank {@code argMapping} on a structurally-inert directive site, or
     * {@code null} otherwise. {@code directiveName} is included in the message bare ("record",
     * "enum") and gets prefixed with {@code @} by the caller.
     */
    private static String checkArgMappingInert(Map<String, Object> ref, String directiveName) {
        String rawArgMapping = Optional.ofNullable(ref.get(no.sikt.graphitron.rewrite.BuildContext.ARG_ARG_MAPPING))
            .map(Object::toString).orElse(null);
        if (rawArgMapping == null || rawArgMapping.isBlank()) return null;
        return "argMapping is not supported on @" + directiveName
            + " — this directive does not consume GraphQL-argument-bound parameters";
    }

    /**
     * Walks {@code @enum} on the given enum type's directives and returns a rejection message
     * when its {@code enumReference} carries a non-blank {@code argMapping}, or {@code null}
     * otherwise.
     */
    private static String checkEnumArgMappingInert(graphql.schema.GraphQLEnumType enumType) {
        var dir = enumType.getAppliedDirective("enum");
        if (dir == null) return null;
        var refArg = dir.getArgument("enumReference");
        if (refArg == null || refArg.getValue() == null) return null;
        Map<String, Object> ref = asMap(refArg.getValue());
        return checkArgMappingInert(ref, "enum");
    }

    // ===== Result container =====

    private record ParticipantListResult(List<ParticipantRef> list, String error) {}
}
