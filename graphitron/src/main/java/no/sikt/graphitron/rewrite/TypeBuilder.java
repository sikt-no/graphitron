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
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.ErrorHandlerType;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.NodeProvenance;
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
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
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
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.argString;
import static no.sikt.graphitron.rewrite.BuildContext.argStringList;
import static no.sikt.graphitron.rewrite.BuildContext.asMap;
import static no.sikt.graphitron.rewrite.BuildContext.candidateHint;
import static no.sikt.graphitron.rewrite.BuildContext.locationOf;

/**
 * Classifies all named types in the schema into the {@link GraphitronType} hierarchy.
 *
 * <p>Classification is field-first and reachability-driven: each type is classified as the single
 * walk in {@link GraphitronSchemaBuilder} reaches it (see {@link #classifyAndRegister}), including
 * interface / union participant lists, which are a registry-free function of SDL plus the
 * reflection fixed point.
 */
class TypeBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(TypeBuilder.class);

    private static final Set<String> ROOT_TYPE_NAMES = Set.of("Query", "Mutation", "Subscription");

    private final BuildContext ctx;
    private final ServiceCatalog svc;
    private final Map<String, Class<?>> recordBackingClasses = new LinkedHashMap<>();
    /**
     * The reflection-driven SDL-to-backing-class binding resolver. Populated by
     * {@link #prepareForWalk()} before per-type classification; consulted by
     * {@link #buildResultType} and {@link #buildPlainInputType} to decide the backed variant.
     */
    private RecordBindingResolver bindings;

    /** Lazily computed by {@link #retainedSupportTypes()}; null until the first support-type gate runs. */
    private Set<String> retainedSupportTypes;
    /** Per-name memo for {@link #lookAheadVerdict}; {@code Optional.empty()} caches a null verdict. */
    private final Map<String, Optional<GraphitronType>> lookAheadMemo = new java.util.HashMap<>();

    TypeBuilder(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    /**
     * Backing classes for reflection-bound result and input types, keyed by GraphQL type name.
     * Populated by the {@link RecordBindingResolver} walk before per-type classification runs; the
     * schema builder threads each loaded class through {@link FieldBuilder#classifyField} so the
     * per-field accessor resolver does not re-load. Classifier-time scratch state; does not
     * survive into the persisted model.
     */
    Map<String, Class<?>> recordBackingClasses() {
        return recordBackingClasses;
    }

    /**
     * DML payload binding produced by {@link RecordBindingResolver#groundDmlMutationField}, keyed
     * by payload SDL type name. Threaded into {@link FieldBuilder#classifyField} so a payload
     * field's child classification routes through the inner {@code TableRef} the DML producer
     * carries. Empty until {@link #prepareForWalk()} resolves the bindings.
     */
    java.util.Optional<no.sikt.graphitron.rewrite.model.ProducerBinding.DmlEmitted> dmlEmittedBinding(String sdlTypeName) {
        return bindings == null ? java.util.Optional.empty() : bindings.resolveDmlEmitted(sdlTypeName);
    }

    /**
     * The {@link no.sikt.graphitron.rewrite.model.ProducerBinding.ServiceEmitted} binding for an
     * SDL payload type whose producer is an {@code @service} mutation field with a carrier-shaped
     * payload. Mirrors {@link #dmlEmittedBinding}.
     */
    java.util.Optional<no.sikt.graphitron.rewrite.model.ProducerBinding.ServiceEmitted> serviceEmittedBinding(String sdlTypeName) {
        return bindings == null ? java.util.Optional.empty() : bindings.resolveServiceEmitted(sdlTypeName);
    }

    /**
     * The {@link no.sikt.graphitron.rewrite.model.ProducerBinding.RoutineEmitted} binding for an
     * SDL payload type whose producer is a hop-less {@code @routine} Mutation field with a
     * carrier-shaped payload. Mirrors {@link #dmlEmittedBinding}.
     */
    java.util.Optional<no.sikt.graphitron.rewrite.model.ProducerBinding.RoutineEmitted> routineEmittedBinding(String sdlTypeName) {
        return bindings == null ? java.util.Optional.empty() : bindings.resolveRoutineEmitted(sdlTypeName);
    }

    /**
     * The one-question presence probe over the emitted-carrier arms: the
     * {@link no.sikt.graphitron.rewrite.model.EmittedCarrierBinding} bound to an SDL payload
     * type, whichever producer family grounded it. This is the accessor the
     * {@code activeChannel} gate in {@code FieldBuilder.transportForParent} reads, so a new
     * emitted-carrier arm extends this method instead of a hand-maintained disjunction at the
     * gate.
     */
    java.util.Optional<no.sikt.graphitron.rewrite.model.EmittedCarrierBinding> emittedCarrierBinding(String sdlTypeName) {
        if (bindings == null) return java.util.Optional.empty();
        return bindings.resolveDmlEmitted(sdlTypeName)
            .<no.sikt.graphitron.rewrite.model.EmittedCarrierBinding>map(b -> b)
            .or(() -> bindings.resolveServiceEmitted(sdlTypeName).map(b -> b))
            .or(() -> bindings.resolveRoutineEmitted(sdlTypeName).map(b -> b));
    }

    /**
     * The {@code @service} producer's arrival cardinality for a payload SDL type, decided once at
     * the reflection boundary and read by the classify-time shape verdict at the {@code @service}
     * carrier seat. Mirrors {@link #serviceEmittedBinding}.
     */
    java.util.Optional<no.sikt.graphitron.rewrite.model.Arity> serviceCarrierProducerArrival(String parentType, String fieldName) {
        return bindings == null ? java.util.Optional.empty() : bindings.resolveServiceCarrierProducerArrival(parentType, fieldName);
    }

    /**
     * The gated accessor near-miss (if any) the binding walk recorded while failing to ground a
     * child SDL type through a parent accessor. Consumed by the dangling-type-reference backstop
     * ({@code GraphitronSchemaBuilder.rejectDanglingTypeReferences}) so a sole-producer type whose
     * only near-grounding was a gated accessor surfaces the accessor gate rather than the generic
     * "did not classify into the model" cascade.
     */
    java.util.Optional<RecordBindingResolver.AccessorGateReason> accessorGateReason(String sdlTypeName) {
        return bindings == null ? java.util.Optional.empty() : bindings.accessorGateReason(sdlTypeName);
    }

    // ===== Type map construction =====

    /**
     * The pre-walk preparation, shared by the production single walk
     * ({@link GraphitronSchemaBuilder#buildSchema}) and the types-only test seam
     * ({@link GraphitronSchemaBuilder#buildContextForTests}). Resolves the reflection-driven
     * SDL-to-backing-class bindings and builds the fixed-point classification indices. No type is
     * classified here: every kind, output composite and input / scalar / enum leaf alike, is
     * classified as the walk reaches it ({@link #classifyAndRegister}); an unreached type of any
     * kind is an orphan, deliberately pruned. Field classification never reads a referenced type's
     * verdict from the registry under construction; it recomputes through
     * {@link #lookAheadVerdict} (see {@link BuildContext#lookAheadVerdict}), so a leaf may be
     * visited after the field that references it.
     *
     * <p>The one validation reduction that depends on the walk's output
     * ({@link #validateNodeTypeIdUniqueness}) runs after it ({@link #finishTypeClassification}).
     */
    void prepareForWalk() {
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
        // Build the fixed-point reverse indices the field pass reads. Derived from SDL + catalog
        // via the same producers classification uses (buildTableType / buildTableInterfaceType),
        // not from the registry, so they may be built before the walk.
        buildClassificationIndices();
        // The routine-carrier grounding runs after the indices: its structural scan detects the
        // payload's errors-shaped field through the ErrorIndex, which the root-producer pass
        // predates. See RecordBindingResolver.groundRoutineCarriers.
        bindings.groundRoutineCarriers();
        // Emit the directive-ignored warning in a dedicated pass over getAllTypesAsList so the
        // warning order is stable (SDL order) and independent of walk order. It reads only the
        // reflection-binding fixed point and SDL directives, never the registry.
        for (var namedType : ctx.schema.getAllTypesAsList()) {
            if (namedType.getName().startsWith("__")) continue;
            emitDirectiveIgnoredWarning(namedType);
        }
        // Surface multi-producer rejections as UnclassifiedType before the walk. When the walk
        // reaches a rejected type, classifyAndRegister's rejection-first guard reconstructs the
        // same payload, so the register is an equals-idempotent no-op, never a re-demote.
        surfaceMultiProducerRejections();
        // resolveAll's DML grounding probes the structural payload scan (and through it
        // lookAheadVerdict) while the binding fold is still forming; verdicts computed then
        // predate the fixed point and must not stick. Only post-preparation verdicts memoize.
        lookAheadMemo.clear();
    }

    /**
     * Classifies one reached type and registers its verdict, the per-type work the single walk
     * drives on enter (see {@link GraphitronSchemaBuilder}'s {@code ClassifyingVisitor}). The
     * verdict is {@link #lookAheadVerdict}'s, so the registry entry and every mid-walk read of
     * the same type are two materializations of one (memoized) computation: the rejection-first
     * guard, the {@link #classifyType} verdict and the carrier fallback cannot drift between the
     * registering visit and a reading edge, and classification side effects (the id-reference
     * shim WARN, participant diagnostics) fire once per type however many edges read it. In
     * particular a binding-rejected type re-registers the exact {@link UnclassifiedType} that
     * {@link #surfaceMultiProducerRejections} seeded, so {@code TypeRegistry.register}'s
     * equals-idempotent arm fires; classifying it live instead would hit the
     * incompatible-classes demote arm and clobber the typed payload with a generic structural
     * one.
     *
     * <p>A {@code null} verdict (a directiveless nesting target or orphan) registers nothing
     * here; the nesting verdict lands at the embedding edge during field classification, or
     * stays absent for an orphan.
     */
    GraphitronType classifyAndRegister(GraphQLNamedType namedType) {
        var gType = lookAheadVerdict(namedType.getName());
        if (gType != null) {
            ctx.typeRegistry.register(namedType.getName(), gType);
        }
        return gType;
    }

    /**
     * The reproduced multi-producer demotion for a binding-rejected type, or {@code null} when
     * the type carries no rejection. The single producer of the rejection-first precedence,
     * shared by {@link #lookAheadVerdict} (which {@link #classifyAndRegister} routes through)
     * and {@link #participantClassification}: because every consumer constructs the demotion
     * here, the {@link UnclassifiedType} the walk re-registers is {@code equals}-identical to
     * the one {@link #surfaceMultiProducerRejections} seeded, and {@code TypeRegistry.register}'s
     * idempotent arm fires by construction rather than by mirrored bodies staying in sync.
     *
     * <p>Scoped to the kinds the record-binding fold governs, exactly like
     * {@link #surfaceMultiProducerRejections}: objects and input objects. The fold's accessor
     * probes can accrue observations against a <em>scalar</em> SDL child (an {@code ID} input
     * field whose Java accessor returns a record class), and such a "rejection" on a built-in
     * scalar is fold noise, not an authoring error; a scalar or enum never takes its verdict from
     * a backing class, so it classifies live regardless of the fold.
     */
    private UnclassifiedType bindingRejectionVerdict(String typeName, GraphQLNamedType named) {
        if (!(named instanceof GraphQLObjectType || named instanceof GraphQLInputObjectType)) {
            return null;
        }
        var rejection = bindings.rejection(typeName).orElse(null);
        if (rejection == null) {
            return null;
        }
        return new UnclassifiedType(typeName, locationOf(named), rejection);
    }

    /**
     * The one validation reduction that runs after the single walk, because it depends
     * on the composite types the walk classifies. Field classification reads node membership through
     * the pure {@link NodeIndex} (which carries no typeId exclusion), so running this demotion after
     * the field walk leaves field classification unchanged.
     */
    void finishTypeClassification() {
        // NodeType typeId uniqueness: two types cannot share a typeId because Query.node(id:)
        // dispatch extracts the typeId prefix and routes to one GraphQL type. Colliding nodes keep
        // their NodeType verdict; the collision surfaces as a build-time diagnostic the validator
        // drains, so the build fails before generation while a verdict read after the walk equals
        // the verdict classification produced.
        validateNodeTypeIdUniqueness();
    }

    /**
     * The producer-bound backing for a directiveless single-record carrier payload. Registry-free:
     * derived from the structural carrier scans ({@link BuildContext#scanStructuralDmlPayload} /
     * {@link BuildContext#scanStructuralServiceCarrierPayload} /
     * {@link BuildContext#scanStructuralRoutineCarrierPayload}) plus the producer binding fixed
     * point ({@code DmlEmitted} / {@code ServiceEmitted} / {@code RoutineEmitted}), never from
     * the in-progress type registry. A producer-backed carrier binds its wrapper to the producer's record, single or
     * multi cardinality alike, so the inner data field reads off the record through the standard
     * record-backed path. Each producer family gates on its own scan; DML carriers keep the strict
     * forbidden-directive set, {@code @service} carriers tolerate {@code @splitQuery} on the data
     * field.
     *
     * <p>Sole producer of the carrier fact, shared by
     * {@link GraphitronSchemaBuilder#classifyFieldsOfObject} (registers the carrier's
     * {@link ResultType} verdict via {@link #carrierVerdict}) and
     * {@link #isDirectivelessNestingTarget} (excludes producer-backed carriers from the nesting
     * verdict), so the two cannot drift. A carrier-shaped payload that no producer returns
     * (orphan) is {@link CarrierBinding.NotACarrier}; it stays a {@link NestingType} and is
     * rejected by the soundness pass.
     *
     * <p>Sealed over the two backing shapes a producer-backed carrier can have; see the record
     * javadocs. The structural {@code RecordElement} {@code Admit} from
     * {@link BuildContext#scanStructuralServiceCarrierPayload} is the single shape signal both
     * this recognizer and the emit-side data-field interception consume.
     */
    sealed interface CarrierBinding
        permits CarrierBinding.TableBacked, CarrierBinding.ClassBacked, CarrierBinding.NotACarrier {
        /** DML {@code RETURNING} / single-level {@code @service} carrier: backed by a jOOQ table record. */
        record TableBacked(TableRef table) implements CarrierBinding {}
        /** Two-level {@code @service} carrier: backed by the per-element composite class. */
        record ClassBacked(Class<?> recordClass) implements CarrierBinding {}
        /** Not a producer-backed carrier (orphan, or not carrier-shaped at all). */
        record NotACarrier() implements CarrierBinding {}
    }

    CarrierBinding carrierBinding(String name) {
        if (ctx.scanStructuralDmlPayload(name) instanceof BuildContext.DmlPayloadScan.Admit) {
            var table = dmlEmittedBinding(name).map(b -> b.tableRef()).orElse(null);
            if (table != null) return new CarrierBinding.TableBacked(table);
        }
        if (ctx.scanStructuralRoutineCarrierPayload(name) instanceof BuildContext.DmlPayloadScan.Admit) {
            var table = routineEmittedBinding(name).map(b -> b.tableRef()).orElse(null);
            if (table != null) return new CarrierBinding.TableBacked(table);
        }
        if (ctx.scanStructuralServiceCarrierPayload(name) instanceof BuildContext.DmlPayloadScan.Admit admit) {
            var table = serviceEmittedBinding(name).map(b -> b.tableRef()).orElse(null);
            if (table != null) return new CarrierBinding.TableBacked(table);
            // The composite carrier: a RecordElement data field whose element type bound to a
            // consumer composite on the result axis. The payload's backing is that per-element
            // class, with the arrival cardinality on the data field, mirroring how a bulk @table
            // carrier above names the element table. Two gates keep the scan's structural
            // RecordElement Admit (which fires for any record-backed DTO with a single object
            // field) from over-recognizing: the payload must NOT be a directly result-axis-bound
            // result type (resolveResult(name) present), and it must be returned by an @service
            // field (an orphan payload whose data-field element binds via an unrelated producer is
            // not a carrier). Both are BindsDataFieldElement's preconditions, read off the binding
            // fixed point + schema, not re-derived.
            if (admit.element() instanceof BuildContext.DmlElementKind.RecordElement
                    && bindings.resolveResult(name).isEmpty()
                    && ctx.isServiceProducedPayload(name)) {
                String elementSdl = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(
                    admit.dataField().getType())).getName();
                Class<?> cls = bindings.resolveResult(elementSdl).orElse(null);
                if (cls != null) return new CarrierBinding.ClassBacked(cls);
            }
        }
        return new CarrierBinding.NotACarrier();
    }

    /**
     * The {@link GraphitronType} verdict a producer-backed carrier payload classifies as, or
     * {@code null} when {@code name} is not such a carrier ({@link CarrierBinding.NotACarrier}).
     * The single projection of {@link #carrierBinding} into a registered type, shared by
     * {@link #lookAheadVerdict} and the producing-edge registration in
     * {@link GraphitronSchemaBuilder}, so the carrier verdict cannot drift between the two.
     */
    GraphitronType carrierVerdict(String name) {
        var binding = carrierBinding(name);
        if (binding instanceof CarrierBinding.NotACarrier) return null;
        // A non-NotACarrier binding implies the structural scan admitted, which requires the type to
        // be a GraphQLObjectType; getObjectType is safe here (it asserts object-ness, and a union /
        // interface reached as a field return — e.g. an errors-shaped union — is NotACarrier above).
        var objType = ctx.schema.getObjectType(name);
        return switch (binding) {
            case CarrierBinding.TableBacked tb ->
                new GraphitronType.JooqTableRecordType(name, locationOf(objType), null, tb.table());
            case CarrierBinding.ClassBacked cb ->
                buildResultTypeFromClass(name, locationOf(objType), cb.recordClass());
            case CarrierBinding.NotACarrier ignored -> null;
        };
    }

    /**
     * The {@link GraphitronType.TableType} verdict for a type bound by being what a hop-less
     * {@code @routine} read field returns, or {@code null} when no such field returns it. The
     * derived form of the {@code @table} an author writes on a routine's return type: the same
     * verdict the directive would produce, from the producing edge rather than from the type's own
     * directives, which is how {@link #carrierVerdict} already binds a payload carrier.
     *
     * <p>A {@link GraphitronType.TableType} and not a
     * {@link GraphitronType.JooqTableRecordType}, because the rows are projected out of the
     * statement that calls the routine rather than handed back by a producer; that is the same
     * shape the directive gives today, so nothing downstream of the verdict changes.
     *
     * <p>Which fields land in this population, and why the boundary is hop-less, is
     * {@code RecordBindingResolver.groundRoutineReturnType}'s.
     */
    GraphitronType routineReturnVerdict(String name) {
        if (bindings == null) return null;
        return bindings.resolveRoutineReturn(name)
            .<GraphitronType>map(table -> new GraphitronType.TableType(
                name, locationOf(ctx.schema.getObjectType(name)), table))
            .orElse(null);
    }

    /**
     * Registry-free verdict for whether an SDL object reached at an embedding edge is a
     * directiveless nesting target: a plain object with no competing classification, projected as
     * a {@link GraphitronType.NestingType} from the embedding parent's table context. Computed
     * from the type's own SDL plus the binding fixed points, never from the in-progress type
     * registry, so an embedding edge decides nesting independently of whether a sibling edge
     * already registered the same {@code NestingType} (an edge never reads a sibling edge's
     * classification). True iff the type is a {@link GraphQLObjectType} that {@link #classifyType}
     * leaves unclassified, is not a multi-producer rejection (those classify as
     * {@link UnclassifiedType}), and is not a producer-bound single-record carrier
     * ({@link #carrierBinding}).
     */
    boolean isDirectivelessNestingTarget(String name) {
        if (!(ctx.schema.getType(name) instanceof GraphQLObjectType obj)) return false;
        if (bindings.rejection(name).isPresent()) return false;
        if (classifyType(obj) != null) return false;
        // A routine's return type is table-backed from its producing edge, so it is no more a
        // nesting target than a @table type is; excluding it here is the same exclusion the
        // carrier gets below, and for the same reason (the registration gate is this predicate).
        if (routineReturnVerdict(name) != null) return false;
        return carrierBinding(name) instanceof CarrierBinding.NotACarrier;
    }

    /**
     * The nesting-<em>edge</em> verdict: whether an SDL object reached at an embedding edge is
     * projected as a {@code NestingField} from the parent's table context, whether or not it also
     * classifies as a producer-backed result. The broader relation
     * {@link #isDirectivelessNestingTarget} is a subset of: it additionally admits a directiveless
     * object whose only competing type-level verdict is a {@link GraphitronType.ResultType}. Such
     * a type is reached both as a nesting projection and via its producer; the embedding edge
     * builds the {@code NestingField} while the type's own visit registers the {@code ResultType},
     * so the registration gate stays {@link #isDirectivelessNestingTarget}. The per-coordinate
     * legality of the resulting shape-set union is decided post-walk over
     * {@link GraphitronSchema#reachableSourceShapes}, not here. Registry-free, so the edge decides
     * independently of any sibling edge's registration.
     */
    boolean isNestingEdgeTarget(String name) {
        if (isDirectivelessNestingTarget(name)) return true;
        return lookAheadVerdict(name) instanceof GraphitronType.ResultType;
    }

    /** The first observed result-axis producer binding for an SDL type; see {@code RecordBindingResolver.resultProducer}. */
    java.util.Optional<no.sikt.graphitron.rewrite.model.ProducerBinding> resultProducerFor(String name) {
        return bindings.resultProducer(name);
    }

    /**
     * Registry-free look-ahead at a field's target type: the verdict the target type name
     * resolves to, computed from SDL + reflection bindings + catalog ({@link #classifyType}) plus
     * the producer-bound single-record carrier fixed point ({@link #carrierVerdict}), never read
     * from the in-progress type registry. This lets the field pass resolve an
     * {@link InterfaceType} / {@link UnionType} / {@link ResultType} target without depending on
     * that target having been registered, which is what allows type and field classification to
     * share one enter-only walk where a field's output target is a not-yet-visited child of the
     * field's parent.
     *
     * <p>The two fallbacks cover the verdicts {@link #classifyType} leaves {@code null} but the
     * registry holds non-null, both bound at the producing edge rather than by the type's own
     * directives: the directiveless single-record carrier ({@link #carrierVerdict}), and the return
     * type of a hop-less {@code @routine} read, bound to the routine's result
     * ({@link #routineReturnVerdict}). They cannot both answer, the routine grounding skipping a
     * carrier-shaped return. A directiveless nesting target / orphan classifies to
     * {@code null} under both, matching the registry's absent entry; the nesting branch in
     * {@link FieldBuilder} is decided separately by {@link #isDirectivelessNestingTarget}, not by
     * this verdict.
     *
     * <p>The multi-producer rejection guard ({@link #bindingRejectionVerdict}) runs first:
     * {@link #surfaceMultiProducerRejections} demotes every
     * binding-rejected type (result <em>and</em> input) to {@link UnclassifiedType} before the
     * field pass reads it, so the look-ahead reproduces that demotion rather than the live verdict
     * {@link #classifyType} would compute. The other post-walk demotions do not change any arm
     * this look-ahead is read for: a typeId-collided node is read through the {@code NodeIndex}
     * (and is table-backed, never one of these arms), and the case-fold collision pass runs after
     * the field walk.
     *
     * <p>Memoized per type name: after {@link #prepareForWalk} every input this reads (SDL,
     * reflection bindings, catalog, the carrier fixed point) is fixed, so the verdict is a pure
     * function of the name and the memo makes the registry and the look-ahead two materializations
     * of one computation. During {@code prepareForWalk} itself the inputs are still forming (the
     * DML grounding probes the payload scan mid-fold), so {@code prepareForWalk} clears the memo
     * at its end and only post-fixed-point verdicts stick.
     */
    GraphitronType lookAheadVerdict(String typeName) {
        var memo = lookAheadMemo.get(typeName);
        if (memo != null) {
            return memo.orElse(null);
        }
        GraphitronType verdict = computeLookAheadVerdict(typeName);
        lookAheadMemo.put(typeName, Optional.ofNullable(verdict));
        return verdict;
    }

    private GraphitronType computeLookAheadVerdict(String typeName) {
        if (!(ctx.schema.getType(typeName) instanceof GraphQLNamedType named)) return null;
        var demoted = bindingRejectionVerdict(typeName, named);
        if (demoted != null) {
            return demoted;
        }
        var verdict = classifyType(named);
        if (verdict != null) return verdict;
        var carrier = carrierVerdict(typeName);
        if (carrier != null) return carrier;
        return routineReturnVerdict(typeName);
    }

    private void validateNodeTypeIdUniqueness() {
        var byTypeId = new LinkedHashMap<String, List<NodeType>>();
        for (var type : ctx.typeRegistry.entries().values()) {
            if (type instanceof NodeType nt) {
                byTypeId.computeIfAbsent(nt.typeId(), k -> new ArrayList<>()).add(nt);
            }
        }
        for (var entry : byTypeId.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            String typeId = entry.getKey();
            // "declared on" would be wrong for a typeId the author never wrote, which is the common
            // shape once nodehood can be inferred: two types over tables that share
            // `__NODE_TYPE_ID` collide without either one naming the value. Attribute each member to
            // where its typeId actually came from, so the message points at the fact to change.
            String others = entry.getValue().stream()
                .sorted(java.util.Comparator.comparing(NodeType::name))
                .map(TypeBuilder::describeTypeIdSource)
                .collect(Collectors.joining(", "));
            for (var nt : entry.getValue()) {
                // Register a diagnostic instead of demoting the NodeType; ValidationError.forType
                // applies the standard "Type '<name>': " prefix.
                ctx.addDiagnostic(ValidationError.forType(nt.name(),
                    Rejection.structural("typeId '" + typeId + "' is used by multiple types (" + others
                    + ") — Query.node dispatch would be nondeterministic; pick one via @node(typeId:)"),
                    nt.location()));
            }
        }
    }

    /** {@code "Foo"} with the origin of its typeId appended, for the collision diagnostic above. */
    private static String describeTypeIdSource(NodeType nt) {
        return switch (nt.provenance().typeId()) {
            case DECLARED -> nt.name() + " via @node(typeId:)";
            case METADATA -> nt.name() + " via __NODE_TYPE_ID on table '" + nt.table().tableName() + "'";
            case DEFAULTED -> nt.name() + " defaulted from the type name";
        };
    }

    /**
     * Builds the fixed-point reverse indices ({@link BuildContext#nodes},
     * {@link BuildContext#tables}, {@link BuildContext#errors},
     * {@link BuildContext#crossTableFieldsByParticipant}) and the scalar fixed point
     * ({@link BuildContext#scalarVerdicts}) the field pass reads at classification
     * edges. Derived from the SDL declarations via the same producers classification uses
     * ({@link #buildTableType} for nodes and tables, {@link #buildTableInterfaceType} for
     * table-interfaces, {@link #buildErrorType} for errors, {@link #classifyScalarType} for
     * scalars).
     *
     * <p>All the indices are built over <b>all</b> declared types (a superset of the
     * reachable set), which lets them be built before the walk, and are <b>pure</b>: no demotion,
     * no reachability prune, and no typeId-uniqueness exclusion
     * ({@link #validateNodeTypeIdUniqueness} is the sole owner of uniqueness, as a validation
     * reduction over the registry). The superset is sound because every type a field read actually
     * queries is already reachable; the extra entries are never read.
     *
     * <p>Node membership in {@link BuildContext#nodes} is driven off the {@code classifyType}
     * verdict ({@code tbt instanceof NodeType}), never off {@code @node} presence, so a node
     * inferred from {@code implements Node} plus catalog metadata enters the index with no extra
     * work here. {@link NodeDeclaration} is the predicate the promotion gate applies to get there.
     *
     * <p>Multiple node types on one table is legitimate (distinct node ids over the same
     * rows): {@code byTable} is one-to-many, the implicit "encoder for this table" lookup is
     * resolved at the call site (which rejects the zero and ambiguous cases), and {@code byName}
     * keys on the distinct type names so each node resolves independently through the explicit
     * {@code @nodeId(typeName:)} path.
     */
    private void buildClassificationIndices() {
        // Iteration follows SDL declaration order, the order classifyType registers the reachable
        // subset in, so byName / byTable ordering matches for the consulted (reachable) entries.
        var byTable = new LinkedHashMap<String, List<NodeType>>();
        var byName = new LinkedHashMap<String, NodeType>();
        var byTableType = new LinkedHashMap<String, TableBackedType>();
        var byErrorName = new LinkedHashMap<String, ErrorType>();
        for (var named : ctx.schema.getAllTypesAsList()) {
            if (named.getName().startsWith("__")) continue;
            if (!(named instanceof GraphQLObjectType || named instanceof GraphQLInterfaceType)) continue;
            // Drive membership off the same classifyType verdict the registry stores (classifyType
            // is registry-free / pure), so the index agrees with the registry by construction:
            // calling classifyType directly, rather than the producers in isolation, keeps the
            // index from resolving a verdict the conflict / federation / support-type gates would
            // have suppressed.
            var verdict = classifyType(named);
            switch (verdict) {
                case TableBackedType tbt -> {
                    byTableType.put(tbt.name(), tbt);
                    if (tbt instanceof NodeType nt) {
                        // byTable is keyed on the lowercased table name so NodeIndex.forTable
                        // lookups are case-folded (the TableRef.sameTable contract).
                        byTable.computeIfAbsent(nt.table().tableName().toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>()).add(nt);
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
        // Per single-table participant, which discriminated interface declares each of its field
        // names: the representative is the lexicographically first declaring interface, so a type
        // participating in several agrees with every sibling on one owner per field name.
        var declaringInterfaceByParticipant = new LinkedHashMap<String, java.util.TreeMap<String, String>>();
        for (var named : ctx.schema.getAllTypesAsList()) {
            if (named.getName().startsWith("__")) continue;
            if (!(named instanceof GraphQLInterfaceType iface)) continue;
            if (!iface.hasAppliedDirective(DIR_TABLE) || !iface.hasAppliedDirective(DIR_DISCRIMINATE)) continue;
            if (!(buildTableInterfaceType(iface) instanceof TableInterfaceType tit)) continue;
            for (var p : tit.participants()) {
                if (!(p instanceof ParticipantRef.TableBound tb)) continue;
                var fields = byParticipant.computeIfAbsent(tb.typeName(), k -> new LinkedHashMap<>());
                for (var ctf : tb.crossTableFields()) {
                    // First-wins across interfaces.
                    fields.putIfAbsent(ctf.fieldName(), ctf);
                }
                var declaring = declaringInterfaceByParticipant.computeIfAbsent(
                    tb.typeName(), k -> new java.util.TreeMap<>());
                for (var fieldDef : iface.getFieldDefinitions()) {
                    declaring.merge(fieldDef.getName(), iface.getName(),
                        (existing, candidate) -> existing.compareTo(candidate) <= 0 ? existing : candidate);
                }
            }
        }
        var participantIndex = new LinkedHashMap<String, Map<String, ParticipantRef.TableBound.CrossTableField>>();
        byParticipant.forEach((k, v) -> participantIndex.put(k, Map.copyOf(v)));
        ctx.crossTableFieldsByParticipant = Map.copyOf(participantIndex);
        ctx.aliasOwnerByParticipant = aliasOwnerIndex(declaringInterfaceByParticipant);

        // The scalar fixed point: SDL scalar name -> classifyScalarType's verdict, the axis the
        // wire-coercion predicate (WireCoercionResolver.checkScalar) and the service slot-type
        // mapping read mid-walk. Same rationale as the indices above: an all-declared,
        // registry-free superset, so a scalar-axis read never observes walk order; an unreachable
        // scalar's entry is never read from a reachable coordinate.
        var scalarVerdicts = new LinkedHashMap<String, GraphitronType>();
        for (var named : ctx.schema.getAllTypesAsList()) {
            if (named.getName().startsWith("__")) continue;
            if (!(named instanceof graphql.schema.GraphQLScalarType scalarType)) continue;
            var verdict = classifyScalarType(scalarType);
            if (verdict != null) {
                scalarVerdicts.put(named.getName(), verdict);
            }
        }
        ctx.scalarVerdicts = java.util.Collections.unmodifiableMap(scalarVerdicts);
    }

    /**
     * The alias-owner fixed point ({@link BuildContext#aliasOwnerByParticipant}) from the
     * declaring-interface map the discriminated-interface scan accumulated: one entry per
     * {@link ParticipantRef.TableBound} participant, keyed by the participant's <em>own</em>
     * declared field names, so a field's namespace verdict is a lookup rather than a read-time
     * fallback. A name a discriminated interface declares is owned by that interface, which makes
     * every participant's arm mint the identical alias, so the query's field set collapses the
     * agreeing terms exactly as it does today; a name only the participant declares is owned by
     * the participant type, which is what keeps two participants' same-named fields from sharing
     * one alias and silently dropping the second projection.
     *
     * <p>The participant's own field list is the key set, not the interface's: SDL forces every
     * interface field onto every implementer, so the object type's field definitions are the union
     * of both halves and every {@code (type, field)} pair a fetcher can bind appears here.
     */
    private Map<String, Map<String, no.sikt.graphitron.rewrite.model.AliasOwner>> aliasOwnerIndex(
            Map<String, java.util.TreeMap<String, String>> declaringInterfaceByParticipant) {
        var index = new LinkedHashMap<String, Map<String, no.sikt.graphitron.rewrite.model.AliasOwner>>();
        declaringInterfaceByParticipant.forEach((typeName, declaring) -> {
            var participantObj = ctx.schema.getObjectType(typeName);
            if (participantObj == null) return;
            var owners = new LinkedHashMap<String, no.sikt.graphitron.rewrite.model.AliasOwner>();
            for (var fieldDef : participantObj.getFieldDefinitions()) {
                var declaringInterface = declaring.get(fieldDef.getName());
                owners.put(fieldDef.getName(), no.sikt.graphitron.rewrite.model.AliasOwner.qualifiedBy(
                    declaringInterface != null ? declaringInterface : typeName));
            }
            index.put(typeName, Map.copyOf(owners));
        });
        return Map.copyOf(index);
    }

    /**
     * Emits the directive-ignored warning for a reachable SDL type carrying {@code @record}.
     * Called once per type from the dedicated pass in {@link #prepareForWalk}; the reflection
     * bindings are a fixed point by then. Three message variants selected by context:
     *
     * <ul>
     *   <li><b>Shadowed by @table</b>: the type also carries {@code @table}, so the binding comes
     *     from {@code @table}-driven reflection. Takes precedence over Matches / Disagrees.</li>
     *   <li><b>Matches</b>: the directive's {@code className} equals the reflected class, or the
     *     directive carries no {@code className} (equivalent to no {@code @record} at all, since
     *     {@code className} is the only field that ever participated in binding).</li>
     *   <li><b>Disagrees</b>: the directive's {@code className} differs from the reflected class.
     *     Reflection's class is used; the directive's claim is informational only.</li>
     * </ul>
     *
     * <p>A type whose reflection walk produced a multi-producer rejection emits no warning; the
     * error supersedes the warning at the same site.
     */
    private void emitDirectiveIgnoredWarning(graphql.schema.GraphQLNamedType named) {
        if (!(named instanceof graphql.schema.GraphQLDirectiveContainer container)) return;
        if (!container.hasAppliedDirective(DIR_RECORD)) return;
        String name = named.getName();
        if (bindings.rejection(name).isPresent()) return;

        boolean isInput = named instanceof GraphQLInputObjectType;
        boolean reachable = isInput
            ? bindings.resolveInput(name).isPresent()
            : bindings.resolveResult(name).isPresent();
        if (!reachable) return;

        String declaredClassName = readRecordClassName(container);
        SourceLocation loc = named instanceof GraphQLObjectType obj
            ? locationOf(obj)
            : named instanceof GraphQLInputObjectType inp
                ? locationOf(inp)
                : null;

        // Safe deletion fix for the ignored directive: offered only for the bare @record form, since
        // graphql-java gives no end location to span @record(record: {...}).
        var recordFix = LintFix.deleteBareAppliedDirective(
            container.getAppliedDirective(DIR_RECORD), "Remove the redundant @record");

        // Shadowed by @table. A @table + @record combination is not a hard conflict
        // (@record never claims a classification, so the authored-claim conflict detection
        // ignores it), so OBJECT carriers reach this site;
        // @table wins and @record is ignored. The arm is OBJECT-only because @table on an input
        // contributes nothing to binding: it shadows nothing there, so an input carrying both
        // falls through to the Matches / Disagrees arms on the reflected class alone.
        if (!isInput && container.hasAppliedDirective(DIR_TABLE)) {
            String message = "Type '" + name + "' carries both @table and "
                + formatRecordRef(declaredClassName)
                + ". Graphitron derives the backing class from @table; "
                + "the @record directive is ignored. Remove it.";
            ctx.addWarning(new BuildWarning.LintFinding(
                message, loc, LintRule.REDUNDANT_RECORD_DIRECTIVE, recordFix));
            return;
        }

        Class<?> reflectedClass = isInput
            ? bindings.resolveInput(name).orElse(null)
            : bindings.resolveResult(name).orElse(null);
        if (reflectedClass == null) return;

        boolean matches = declaredClassName == null
            || declaredClassName.equals(reflectedClass.getName());
        if (matches) {
            String message = "Type '" + name + "' carries "
                + formatRecordRef(declaredClassName)
                + ". Graphitron derives the same backing class from the producing field's "
                + "reflected return type. The directive is redundant; remove it.";
            ctx.addWarning(new BuildWarning.LintFinding(
                message, loc, LintRule.REDUNDANT_RECORD_DIRECTIVE, recordFix));
        } else {
            String message = "Type '" + name + "' carries "
                + formatRecordRef(declaredClassName)
                + ". Graphitron derives a different backing class (" + reflectedClass.getName()
                + ") from the producing field's reflected return type and uses that; "
                + "the directive is ignored. Remove it.";
            ctx.addWarning(new BuildWarning.LintFinding(
                message, loc, LintRule.REDUNDANT_RECORD_DIRECTIVE, recordFix));
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
     * For every multi-producer disagreement the resolver reported, demote the SDL type to
     * {@link UnclassifiedType} carrying the typed
     * {@link Rejection.AuthorError.RecordBindingMultiProducer} payload. The validator picks the
     * demotion up through its standard {@link UnclassifiedType} pass.
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
            // The reconciling register entry stores when the type is absent (a directiveless
            // object with no single agreed producer was never registered) and demotes when a prior
            // verdict is present.
            ctx.typeRegistry.register(name, unclassified);
        }
    }

    private List<String> implementorNames(String interfaceName) {
        var iface = (GraphQLInterfaceType) ctx.schema.getType(interfaceName);
        return ctx.schema.getImplementations(iface).stream().map(obj -> obj.getName()).toList();
    }

    /**
     * The participant's classification verdict, computed as a pure function of SDL plus the
     * already-resolved reflection bindings, with <em>no</em> read of the type registry, so
     * {@link #buildParticipantList} can run before the registry is populated. A multi-producer
     * rejection ({@link #bindingRejectionVerdict}) is reproduced first, as an
     * {@link UnclassifiedType} (routing to {@link #buildParticipantList}'s error arm); otherwise
     * the verdict is {@link #classifyType}'s, which is {@code null} for a directiveless object (a
     * directiveless single-record carrier classifies only at the producing edge via
     * {@link #carrierVerdict}, so it is {@code null} here too).
     *
     * <p>{@code classifyType} is a value-builder over SDL + bindings + catalog with no registry
     * writes, so re-invoking it here yields the same verdict. It is not fully side-effect-free:
     * the {@code @table @discriminate} interface arm registers located diagnostics
     * ({@code ctx.addDiagnostic} in {@link #buildParticipantList}'s join resolution) and the
     * {@code @node} keyColumns order mismatch logs a {@code LOGGER.warn}, so re-invocation can
     * repeat those emissions.
     */
    private GraphitronType participantClassification(String typeName) {
        var named = (GraphQLNamedType) ctx.schema.getType(typeName);
        var demoted = bindingRejectionVerdict(typeName, named);
        if (demoted != null) {
            return demoted;
        }
        return named == null ? null : classifyType(named);
    }

    /**
     * Classifies each interface implementor / union member into a {@link ParticipantRef}: a
     * {@code @table}-bound member becomes {@link ParticipantRef.TableBound}; a non-table member
     * becomes {@link ParticipantRef.Unbound} when the context admits non-table members
     * ({@code allowNonTableMembers}, a plain interface), else it is an error.
     *
     * <p><b>Interim:</b> {@code ParticipantRef.Unbound} is overloaded here for two distinct
     * things, {@code @error} members (e.g. an {@code @error}-only union) and directiveless
     * implementors of a plain interface, and the participant role is derived from the member
     * type's standalone classification rather than from the field that returns the polymorphic
     * type.
     *
     * @param allowNonTableMembers whether non-table members are admitted as {@link ParticipantRef.Unbound}
     *     (true for a plain {@link InterfaceType}; false for unions and {@code TableInterfaceType},
     *     where a non-table member is an error).
     * @param interfaceTable the {@code TableInterfaceType}'s own table when building participants
     *     for a single-table interface. Used to detect each participant's cross-table fields
     *     (those whose {@code @reference} terminates on a different table than the interface
     *     table); the resulting {@link ParticipantRef.TableBound.CrossTableField} list is lowered
     *     to the capped correlated subselects the interface fetchers project. {@code null} for
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
                // Joined-table (class-table) inheritance: a participant whose own @table is a
                // detail table distinct from the discriminated base. Its inherited (base-resident)
                // fields carry a parent-@reference back to the base; the participant cross-table pass
                // (which exists for the inverse workaround shape, @table == base referencing OUT to
                // detail tables) does not apply, so it is skipped. The child->parent hop is resolved
                // from the declared @reference; PK=FK and same-base invariants surface as diagnostics.
                if (interfaceTable != null && !tbt.table().denotesSameTableAs(interfaceTable)) {
                    var joined = resolveJoinedTableParticipant(typeName, tbt.table(), interfaceTable, discriminatorValue);
                    if (joined != null) result.add(joined);
                    continue;
                }
                List<ParticipantRef.TableBound.CrossTableField> crossTableFields = interfaceTable != null
                    ? extractCrossTableFields(typeName, interfaceTable)
                    : List.of();
                result.add(new ParticipantRef.TableBound(typeName, tbt.table(), discriminatorValue, crossTableFields));
            } else if (gt == null && allowNonTableMembers) {
                // Directiveless implementor of a plain interface (see the Interim note).
                result.add(new ParticipantRef.Unbound(typeName));
            } else if (gt != null && !(gt instanceof UnclassifiedType)) {
                // A classified non-table member, e.g. an @error type in an @error-only union
                // (see the Interim note). A discriminated interface has no such population:
                // every participant shares (or joins to) the discriminated base, so a classified
                // non-table implementor is an authoring error there, not an Unbound participant
                // the emitters would silently skip.
                if (interfaceTable != null) {
                    errors.add("implementing type '" + typeName + "' of a single-table"
                        + " discriminated interface is not table-bound; every participant must"
                        + " carry @table (the shared base, or a joined detail table)");
                } else {
                    result.add(new ParticipantRef.Unbound(typeName));
                }
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
     * The interface query projects one correlated subselect (gated by the participant's discriminator
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
            if (!(parsed.elements().get(0) instanceof JoinStep.Hop fk
                && fk.on() instanceof On.ColumnPairs)) continue;
            if (fk.targetTable().denotesSameTableAs(interfaceTable)) continue;

            // Resolve the column on the target table that the field maps to. @field(name:) is the
            // primary signal; fall back to the GraphQL field name when the directive is absent.
            String columnSqlName = argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(fieldName);

            // A @reference field whose resolved column already exists on the interface/base table
            // is a contradiction: the column is read directly off the base table, so a cross-table
            // @reference is meaningless. The pathological case is the discriminator column itself,
            // re-declared on a detail table by a composite FK: classifying it as cross-table emits
            // a fetcher that reads a join-only alias never populated in a non-inline-fragment
            // query, so the read silently finds nothing. Resolve the predicate here (the catalog
            // is in scope) and surface a rejection through the diagnostic channel; the validator
            // has no catalog, so it reads the surfaced rejection rather than recomputing. Skipping
            // the field also keeps any ParticipantColumnReferenceField from being emitted. A
            // participant-only field (column lives only on the detail table) is not matched and
            // stays a valid cross-table field.
            if (interfaceTable.column(columnSqlName).isPresent()) {
                var baseColumns = interfaceTable.allColumns().stream().map(ColumnRef::sqlName).collect(Collectors.toSet());
                var detailOnlyColumns = fk.targetTable().allColumns().stream().map(ColumnRef::sqlName)
                    .filter(c -> !baseColumns.contains(c))
                    .toList();
                ctx.addDiagnostic(ValidationError.forField(
                    participantTypeName + "." + fieldName,
                    Rejection.invalidSchema(
                        "carries @reference but its resolved column '" + columnSqlName
                        + "' already exists on the interface/base table '" + interfaceTable.tableName()
                        + "'; the column is read directly from the base table, so the cross-table @reference is "
                        + "meaningless and must be removed"
                        + BuildContext.candidateHint(columnSqlName, detailOnlyColumns,
                            ". A cross-table @reference must name a column that lives only on the detail table, e.g.: ")),
                    BuildContext.locationOf(fieldDef)));
                continue;
            }

            var column = fk.targetTable().column(columnSqlName).orElse(null);
            if (column == null) continue;

            String aliasName = participantTypeName + "_" + fieldName;
            out.add(new ParticipantRef.TableBound.CrossTableField(fieldName, column, fk, aliasName));
        }
        return List.copyOf(out);
    }

    /**
     * Resolves a joined-table inheritance participant: a participant whose own {@code @table}
     * ({@code detailTable}) is distinct from the discriminated {@code baseTable}. Its inherited
     * (base-resident) fields carry a parent-{@code @reference} back to the base; the single-hop
     * FK-derived {@link JoinStep.Hop} they name is the participant's child&rarr;parent hop, stored on the
     * {@link ParticipantRef.JoinedTableBound}.
     *
     * <p>Two invariants are checked here (the catalog is in scope; the validator has none, so it reads
     * the surfaced rejection rather than recomputing, per "Classification belongs at the parse
     * boundary" / the diagnostic-channel pattern):
     * <ul>
     *   <li>every parent-{@code @reference} must resolve to the discriminated base, not some other
     *       table (a non-base reference is not a base bridge);</li>
     *   <li>the child&rarr;parent hop must be PK=FK: the detail table's FK columns to the base
     *       <em>are</em> the detail's own primary key (single-column or composite), so the
     *       base&rarr;detail join the interface fetcher emits is single-valued.</li>
     * </ul>
     * When no inherited field carries a parent-{@code @reference} that names the join, the join cannot
     * be pinned in the unambiguous joined-table shape (disambiguation of the ambiguous shapes is a
     * separate concern); reject with a candidate-FK
     * hint. On any rejection the method surfaces the diagnostic and returns {@code null} (the
     * participant is dropped; the diagnostic fails the build before generation).
     */
    private ParticipantRef resolveJoinedTableParticipant(
            String typeName, TableRef detailTable, TableRef baseTable, String discriminatorValue) {
        var participantObj = ctx.schema.getObjectType(typeName);
        JoinStep.Hop hop = null;
        boolean sawNonBaseReference = false;
        if (participantObj != null) {
            for (var fieldDef : participantObj.getFieldDefinitions()) {
                if (!fieldDef.hasAppliedDirective(DIR_REFERENCE)) continue;
                var parsed = ctx.parsePath(fieldDef, fieldDef.getName(), detailTable.tableName(), null);
                if (parsed.hasError() || parsed.elements().size() != 1) continue;
                if (!(parsed.elements().get(0) instanceof JoinStep.Hop fk
                    && fk.on() instanceof On.ColumnPairs)) continue;
                if (!fk.targetTable().denotesSameTableAs(baseTable)) {
                    sawNonBaseReference = true;
                    continue;
                }
                if (hop == null) hop = fk;
            }
        }

        if (sawNonBaseReference) {
            ctx.addDiagnostic(ValidationError.forType(typeName,
                Rejection.invalidSchema(
                    "joined-table participant '" + typeName + "' declares a parent-@reference that does not "
                    + "resolve to the discriminated base table '" + baseTable.tableName()
                    + "'; an inherited field's @reference must bridge back to the base"),
                participantObj == null ? null : BuildContext.locationOf(participantObj)));
            return null;
        }

        if (hop == null) {
            var candidateFks = ctx.catalog.findForeignKeysBetweenTables(detailTable.tableName(), baseTable.tableName())
                .stream().map(org.jooq.ForeignKey::getName).toList();
            ctx.addDiagnostic(ValidationError.forType(typeName,
                Rejection.invalidSchema(
                    "joined-table participant '" + typeName + "' (detail table '" + detailTable.tableName()
                    + "') has no base-resident field carrying @reference to name the base->detail join; "
                    + "declare one inherited field with @reference back to '" + baseTable.tableName() + "'"
                    + BuildContext.candidateHint(baseTable.tableName(), candidateFks,
                        ". Candidate foreign keys between the tables: ")),
                participantObj == null ? null : BuildContext.locationOf(participantObj)));
            return null;
        }

        var detailFkColumns = ((On.ColumnPairs) hop.on()).sourceSideColumns().stream()
            .map(c -> c.sqlName().toLowerCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        var detailPk = ctx.catalog.candidateKeys(detailTable.tableName()).stream()
            .filter(JooqCatalog.KeyEntry::primary).findFirst();
        var detailPkColumns = detailPk
            .map(k -> k.columns().stream().map(c -> c.sqlName().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet()))
            .orElse(java.util.Set.of());
        if (detailPkColumns.isEmpty() || !detailPkColumns.equals(detailFkColumns)) {
            String fkColsText = ((On.ColumnPairs) hop.on()).sourceSideColumns().stream().map(ColumnRef::sqlName).toList().toString();
            String pkColsText = detailPk.map(k -> k.columns().stream()
                .map(JooqCatalog.ColumnEntry::sqlName).toList().toString()).orElse("absent");
            ctx.addDiagnostic(ValidationError.forType(typeName,
                Rejection.invalidSchema(
                    "joined-table participant '" + typeName + "': the base->detail join is not single-valued. "
                    + "The detail table '" + detailTable.tableName() + "'s foreign-key columns to the base "
                    + fkColsText + " must be the detail table's own primary key (single-column or composite), "
                    + "but the detail primary key is " + pkColsText),
                participantObj == null ? null : BuildContext.locationOf(participantObj)));
            return null;
        }

        return new ParticipantRef.JoinedTableBound(typeName, detailTable, discriminatorValue, hop);
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
            // A @table + @error combination is no longer intercepted here: both claims are rows
            // in the store's authored claim views, and the conflict is that relation's grouping
            // rule, reported as a located ValidationError while the type classifies by arm order
            // (@table first) instead of tombstoning.
            if (objType.hasAppliedDirective(DIR_TABLE)) {
                return buildTableType(objType);
            }
            if (objType.hasAppliedDirective(DIR_ERROR)) {
                return buildErrorType(objType);
            }
            // The reflection-derived binding from the resolver is the only signal; the deprecated
            // @record directive never drives classification.
            if (bindings.resolveResult(name).isPresent()) {
                return buildResultType(name, location);
            }
            // A directiveless object with no producer is left unclassified here: the type builder
            // cannot yet know what it is. It becomes a NestingType at the embedding edge if a
            // NestingField references it (so NestingType implies a corresponding NestingField by
            // construction); a producer-backed carrier-shaped payload is bound at its visit in the
            // field pass (carrierVerdict); anything else is an orphan, caught at the field edge
            // where the referencing field classifies as UnclassifiedField.
            return null;
        }
        if (namedType instanceof GraphQLInterfaceType iface) {
            if (iface.hasAppliedDirective(DIR_TABLE) && iface.hasAppliedDirective(DIR_DISCRIMINATE)) {
                return buildTableInterfaceType(iface);
            }
            // Classify a plain interface with its participants at its own visit.
            // participantClassification is registry-free, so the participant list is a pure
            // function of SDL + the reflection fixed point.
            var participants = buildParticipantList(implementorNames(name), true, null);
            if (participants.error() != null) {
                return new UnclassifiedType(name, location, Rejection.structural(participants.error()));
            }
            return new InterfaceType(name, location, participants.list());
        }
        if (namedType instanceof GraphQLUnionType union) {
            // Union members are classified into participants at the union's own visit; see the
            // interface arm above.
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
     * Resolution order:
     *
     * <ul>
     *   <li><b>Spec built-ins</b> resolve through the resolver's closed built-in table;
     *       {@code @scalarType} on one is a
     *       {@link Rejection.InvalidSchema.DirectiveConflict}.</li>
     *   <li><b>Federation-namespace scalars</b> resolve via
     *       {@link ScalarTypeResolver#resolveFederationNamespaceScalar(String)} to a
     *       {@link ScalarResolution.Synthesised}; see the inline note.</li>
     *   <li><b>{@code @scalarType(scalar: "FQN.FIELD")}</b>: the single explicit binding path for
     *       any other scalar. The resolver looks up the named class + field through
     *       {@link BuildContext#codegenLoader}, validates the field, and reflects on the
     *       {@code Coercing<I, O>} type parameters.</li>
     *   <li><b>Unresolved</b>: a hard validation error pointing at {@code @scalarType(scalar:)} as
     *       the fix.</li>
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

        return new UnclassifiedType(name, location, Rejection.structural(
            "scalar '" + name + "' is not resolvable to a Java type. Add "
                + "@" + DIR_SCALAR_TYPE + "(" + ARG_SCALAR + ": \"fully.qualified.Class.FIELD\") "
                + "pointing at a public static final GraphQLScalarType."));
    }

    /**
     * Registers the {@link GraphitronType.ScalarType} row for {@code name} when {@code name} is a
     * scalar and nothing has registered it yet.
     *
     * <p>The demand entry for a surface the generator synthesises rather than the author writing
     * it. Registration is otherwise sourced from author-reachability (the classification walk
     * classifies the scalars a reachable coordinate names), and the two sets diverge the moment a
     * minted surface references a scalar the SDL never mentions: connection synthesis names
     * {@code Int}, {@code String} and {@code Boolean} on its pagination shapes whatever the author
     * wrote. The registered set is what {@code GraphitronSchemaClassGenerator} turns into
     * {@code schemaBuilder.additionalType(...)} calls, so a missing row means the generated schema
     * class names the scalar through a {@code typeRef} with nothing registering it and consumer
     * assembly fails with "type Int not found in schema".
     *
     * <p>Two no-op arms make the call order irrelevant and the caller ignorant of the type axis:
     * a name the registry already carries keeps its existing row (so the walk and a demand cannot
     * produce two rows for one name), and a name that is not a scalar at all falls through (the
     * caller sweeps every named reference on a minted form and lets this method decide, so an
     * object type or a not-yet-minted connection name needs no filtering there).
     *
     * <p>Row construction goes through {@link #classifyScalarType}, the one producer, over the
     * assembled schema's instance when it carries one. A spec built-in the assembled schema omits
     * is classified from its {@code graphql.Scalars} constant instead; that instance has no SDL
     * definition node, so the row's {@code location} is {@code null}, which is right on its own
     * terms: the scalar has no authored site, and no single carrier referencing it is the
     * actionable one.
     */
    void ensureScalarRegistered(String name) {
        if (ctx.typeRegistry.contains(name)) {
            return;
        }
        var instance = ctx.schema.getType(name) instanceof GraphQLScalarType declared
            ? declared
            : ScalarTypeResolver.specBuiltInInstance(name);
        if (instance == null) {
            return;
        }
        ctx.typeRegistry.register(name, classifyScalarType(instance));
    }

    /**
     * Projects a {@link ScalarResolution.Rejected} arm to a {@link Rejection} the validator
     * surfaces alongside the rest of the type-classification rejections. Each arm carries the
     * structured payload the per-arm LSP fix-it consumes; the prose here is the build-log surface
     * only.
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
        String tableName = NodeDeclaration.boundTableName(objType);
        Optional<TableRef> tableOpt = svc.resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, ctx.unknownTableRejection(tableName));
        }
        TableRef tableRef = tableOpt.get();

        // Platform-id synthesis. The malformed-metadata diagnostic runs unconditionally so SDL
        // authors see the issue even when they try to override values with explicit @node.
        Optional<String> metadataDiagnostic = ctx.catalog.nodeIdMetadataDiagnostic(tableRef.tableName());
        if (metadataDiagnostic.isPresent()) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "KjerneJooqGenerator metadata on table '" + tableRef.tableName() + "' is malformed: "
                + metadataDiagnostic.get()));
        }
        Optional<JooqCatalog.NodeIdMetadata> metadata = ctx.catalog.nodeIdMetadata(tableRef.tableName());

        // `implements Node` is the author's SDL-level declaration of nodehood; @node supplies or
        // overrides the two identity parameters (typeId, keyColumns). When the backing jOOQ class
        // has already published both, @node is redundant and the classifier takes them from the
        // catalog — the same values-resolution the @node-with-no-arguments path below computes.
        // NodeDeclaration is the shared predicate; keeping the gate on it is what stops the
        // reachability seeds, the federation entity set and the LSP node view from disagreeing with
        // this verdict. `@table` + metadata *without* `implements Node` stays a TableType: nesting
        // projections over a node-bearing table must not become second nodes.
        boolean hasNode = objType.hasAppliedDirective(DIR_NODE);
        if (!hasNode) {
            if (NodeDeclaration.implementsNode(objType) && metadata.isPresent()) {
                var meta = metadata.get();
                return buildNodeType(name, location, tableRef, meta.typeId(),
                    List.copyOf(meta.keyColumns()), NodeProvenance.fromMetadata());
            }
            return new TableType(name, location, tableRef);
        }

        // @node declared — the type must implement the Relay Node interface (id: ID!).
        // `implements Node` is a schema-level contract published to clients; we cannot promote
        // a type to NodeType without it.
        if (!NodeDeclaration.implementsNode(objType)) {
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
            var provenance = new NodeProvenance(
                sdlTypeId != null ? NodeProvenance.Origin.DECLARED : NodeProvenance.Origin.DEFAULTED,
                sdlKeyColumnNames.isEmpty() ? NodeProvenance.Origin.DEFAULTED : NodeProvenance.Origin.DECLARED);
            List<ColumnRef> resolvedKeyColumns;
            if (!sdlKeyColumnNames.isEmpty()) {
                resolvedKeyColumns = List.copyOf(sdlKeyColumns);
            } else {
                var pk = ctx.catalog.findPkColumns(tableRef.tableName()).stream()
                    .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass(), e.columnType()))
                    .toList();
                if (pk.isEmpty()) {
                    return new UnclassifiedType(name, location, Rejection.structural(
                        "@node on " + name + " omits keyColumns but table '" + tableRef.tableName()
                        + "' has no primary key — declare `keyColumns:` on @node or add a primary key"));
                }
                resolvedKeyColumns = pk;
            }
            return buildNodeType(name, location, tableRef, resolvedTypeId, resolvedKeyColumns, provenance);
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
        var provenance = new NodeProvenance(
            sdlTypeId != null ? NodeProvenance.Origin.DECLARED : NodeProvenance.Origin.METADATA,
            sdlKeyColumnNames.isEmpty() ? NodeProvenance.Origin.METADATA : NodeProvenance.Origin.DECLARED);
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
        return buildNodeType(name, location, tableRef, resolvedTypeId, resolvedKeyColumns, provenance);
    }

    /**
     * Constructs a {@link NodeType} with pre-resolved {@link HelperRef} references for the
     * per-type {@code encode<TypeName>} / {@code decode<TypeName>} helpers. The encoder class is
     * the same {@link NodeIdEncoderClassGenerator#CLASS_NAME} emitted under
     * {@code outputPackage + ".util"}; the helper method name is derived from the GraphQL type
     * name (not the {@code typeId}, which is the wire string and may differ).
     */
    private NodeType buildNodeType(String name, SourceLocation location, TableRef tableRef,
                                   String typeId, List<ColumnRef> keyColumns,
                                   NodeProvenance provenance) {
        ClassName encoderClass = ClassName.get(
            ctx.ctx.outputPackage() + ".util",
            NodeIdEncoderClassGenerator.CLASS_NAME);
        var encodeMethod = new HelperRef.Encode(encoderClass, "encode" + name, keyColumns);
        var decodeMethod = new HelperRef.Decode(encoderClass, "decode" + name, keyColumns, typeId);
        return new NodeType(name, location, tableRef, typeId, keyColumns, encodeMethod, decodeMethod,
            provenance);
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
     * Constructs the appropriate {@link ResultType} sub-type from the resolved backing class;
     * {@link RecordBindingResolver} reflection is the only source. Reached only for a type with a
     * resolved producer binding (gated in {@link #classifyType}); the deprecated {@code @record}
     * directive is surfaced by {@link #emitDirectiveIgnoredWarning} rather than consulted here.
     */
    private GraphitronType buildResultType(String name, SourceLocation location) {
        Class<?> cls = bindings.resolveResult(name).orElseThrow(() -> new IllegalStateException(
            "buildResultType reached for '" + name + "' without a reflected producer binding; "
            + "classifyType must gate on bindings.resolveResult(name).isPresent()"));
        return buildResultTypeFromClass(name, location, cls);
    }

    /**
     * Constructs the {@link ResultType} sub-type for an SDL type backed by a known {@code Class}.
     * Shared by {@link #buildResultType} (the result-axis binding) and {@link #carrierVerdict}'s
     * {@code ClassBacked} carrier arm, where the backing class is the carrier payload's
     * per-element composite class rather than the payload's own (absent) result-axis binding.
     */
    private GraphitronType buildResultTypeFromClass(String name, SourceLocation location, Class<?> cls) {
        String className = cls.getName();
        return switch (resultVariantKindFor(cls)) {
            case JAVA_RECORD -> new GraphitronType.JavaRecordType(name, location, className);
            case JOOQ_TABLE_RECORD ->
                new GraphitronType.JooqTableRecordType(name, location, className,
                    svc.resolveTableByRecordClass(cls).orElse(null));
            case JOOQ_RECORD -> new GraphitronType.JooqRecordType(name, location, className);
            case POJO -> new GraphitronType.PojoResultType.Backed(name, location, className);
        };
    }

    /**
     * The {@link GraphitronType.ResultType} variant a backing class maps to, as a pure reflection
     * function (the {@code svc}-dependent table resolution in {@link #buildResultTypeFromClass} does
     * not affect which variant is chosen). Single-sourced here so the order-bridge meta-test can pin
     * {@link ClassAccessorResolver#forBackingClass} (the reflection walk's candidate-order derivation)
     * against the same class-shape decision the emission side derives its order from: the walk uses
     * {@code RECORD_FIRST} exactly when this returns {@link ResultVariantKind#JAVA_RECORD}, which is
     * exactly when {@code buildResultTypeFromClass} produces a {@code JavaRecordType}. A future change
     * to this classification breaks the meta-test rather than silently splitting walk and emission
     * order.
     */
    enum ResultVariantKind { JAVA_RECORD, JOOQ_TABLE_RECORD, JOOQ_RECORD, POJO }

    static ResultVariantKind resultVariantKindFor(Class<?> cls) {
        if (cls.isRecord()) return ResultVariantKind.JAVA_RECORD;
        if (org.jooq.TableRecord.class.isAssignableFrom(cls)) return ResultVariantKind.JOOQ_TABLE_RECORD;
        if (org.jooq.Record.class.isAssignableFrom(cls)) return ResultVariantKind.JOOQ_RECORD;
        return ResultVariantKind.POJO;
    }

    private GraphitronType buildTableInterfaceType(GraphQLInterfaceType iface) {
        String name = iface.getName();
        SourceLocation location = locationOf(iface);
        String tableName = argString(iface, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = svc.resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, ctx.unknownTableRejection(tableName));
        }
        // @discriminate(on:) is String! in the directive schema, so the argument is present
        // whenever the directive is; the empty fallback keeps the lookup total either way.
        String discriminatorRaw = argString(iface, DIR_DISCRIMINATE, ARG_ON).orElse("");
        // The whole ColumnEntry is kept, not just its SQL name: the reference qualifies off
        // sqlName and the comparison binds off javaName's getDataType(), which is what types a
        // Postgres-enum discriminator's operand. findColumn accepts both naming conventions.
        JooqCatalog.ColumnEntry col =
            ctx.catalog.findColumn(tableOpt.get().tableName(), discriminatorRaw).orElse(null);
        if (col == null) {
            // No raw-string fallback: emitting the comparison needs a generated field to read
            // getDataType() off, and the fallback only ever produced code that failed at query
            // time with a column-does-not-exist error. This is the sole enforcer of the invariant
            // the manual states ("the on column must exist on that table").
            return new UnclassifiedType(name, location, Rejection.unknownColumn(
                "interface '" + name + "': @discriminate(on: \"" + discriminatorRaw
                + "\") does not resolve to a column of table '" + tableOpt.get().tableName() + "'",
                discriminatorRaw, ctx.catalog.columnJavaNamesOf(tableOpt.get().tableName())));
        }
        var discriminatorColumn =
            new ColumnRef(col.sqlName(), col.javaName(), col.columnClass(), col.columnType());
        // The single-table interface passes its own table so each participant's cross-table
        // fields are detected against it.
        var participants = buildParticipantList(implementorNames(name), false, tableOpt.get());
        if (participants.error() != null) {
            return new UnclassifiedType(name, location, Rejection.structural(participants.error()));
        }
        var closedDomain = discriminatorLiteralRejection(name, discriminatorColumn, participants.list());
        if (closedDomain != null) {
            return new UnclassifiedType(name, location, closedDomain);
        }
        return new TableInterfaceType(name, location, discriminatorColumn, tableOpt.get(), participants.list());
    }

    /**
     * The closed-domain check on {@code @discriminator(value:)}: when the discriminator column's
     * value domain is closed, every participant's literal must be in it. A jOOQ-generated enum
     * closes it; a varchar column's domain is open and owes no check.
     *
     * <p>This guards the typed bind the renderer emits rather than adding a second feature.
     * {@code DSL.val(literal, <enum data type>)} converts an unknown literal to {@code null} with
     * no warning, so the bind would match no row and the query would silently return nothing,
     * trading the loud failure the typed bind fixes for a silent wrong answer. Rejecting at
     * classify time keeps the mechanism total.
     *
     * <p>The comparison runs against the <em>database literal</em>
     * ({@link EnumMappingResolver.ConstantSpelling#DATABASE_LITERAL}), not the Java constant name:
     * {@code @discriminator(value:)} names what the column stores, which is also what the
     * generated {@code TypeResolver} switches on when it reads the routing alias back, so both
     * ends of the comparison live in one namespace.
     *
     * @return the rejection, or {@code null} when the domain is open or every literal is in it
     */
    private Rejection discriminatorLiteralRejection(String interfaceName, ColumnRef discriminator,
            List<ParticipantRef> participants) {
        Class<?> columnClass;
        try {
            columnClass = Class.forName(discriminator.columnClass(), false, ctx.codegenLoader());
        } catch (ClassNotFoundException notLoadable) {
            return null;
        }
        if (!columnClass.isEnum()) {
            return null;
        }
        // Every participant of a discriminated interface is table-backed (buildParticipantList's
        // discriminated arm rejects anything else), and a participant that omits @discriminator
        // carries a null value; that omission is its own unrejected shape, not this check's.
        var targets = participants.stream()
            .filter(p -> p instanceof ParticipantRef.TableBacked)
            .map(p -> ((ParticipantRef.TableBacked) p).discriminatorValue())
            .filter(java.util.Objects::nonNull)
            .map(v -> new EnumMappingResolver.EnumConstantParity.Target(v, v))
            .toList();
        var mismatches = EnumMappingResolver.constantMismatches(columnClass,
            EnumMappingResolver.ConstantSpelling.DATABASE_LITERAL, targets);
        if (mismatches.isEmpty()) {
            return null;
        }
        var rendered = mismatches.stream()
            .map(m -> "'" + m.sdlValueName() + "'"
                + BuildContext.candidateHint(m.runtimeValue(), m.candidates()))
            .collect(java.util.stream.Collectors.joining("; "));
        var literals = EnumMappingResolver.constantNames(columnClass,
            EnumMappingResolver.ConstantSpelling.DATABASE_LITERAL);
        return Rejection.structural("interface '" + interfaceName + "': discriminator column '"
            + discriminator.sqlName() + "' is the enum type " + columnClass.getSimpleName()
            + ", whose literals are " + String.join(", ", literals)
            + "; these @discriminator(value:) values are not among them: " + rendered);
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

        // Read @field(name:) on each extra field (everything except path / message) into the
        // accessor-override list the classify-time check and the runtime fetcher both consult. In
        // SDL declaration order so the emitter's fetcher-registration order is deterministic.
        List<ErrorType.FieldAccessorOverride> accessorOverrides = new ArrayList<>();
        for (var f : objType.getFieldDefinitions()) {
            String fieldName = f.getName();
            if ("path".equals(fieldName) || "message".equals(fieldName)) {
                if (f.getAppliedDirective(DIR_FIELD) != null) {
                    rejectReasons.add("@field on '" + fieldName + "' is not allowed: that field is "
                        + "populated by Graphitron, so the directive can never take effect");
                }
                continue;
            }
            var override = argString(f, DIR_FIELD, ARG_NAME);
            if (override.isEmpty()) continue;
            if (override.get().isBlank()) {
                rejectReasons.add("extra field '" + fieldName + "' carries @field(name:) with a "
                    + "blank value; give it the source-class accessor name to read, or drop the "
                    + "directive to read by the field's own name");
                continue;
            }
            accessorOverrides.add(new ErrorType.FieldAccessorOverride(fieldName, override.get()));
        }

        if (!rejectReasons.isEmpty()) {
            return new UnclassifiedType(name, location, Rejection.structural(
                "@error type rejected: " + String.join("; ", rejectReasons)));
        }
        return new ErrorType(name, location, List.copyOf(handlers), List.copyOf(accessorOverrides));
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
        // @table on an input is deprecated and inert: an input carrying it gets the same verdict
        // it would get without it. The name: argument is never read, so the directive cannot
        // decide a backing class, a write target, or a nesting boundary. The deprecation is
        // announced per usage by
        // {@code GraphitronSchemaBuilder.emitTableOnInputDeprecationWarnings}, which runs
        // post-classification because the per-verb replacement wording needs the classified field
        // registry; emitting from here would make warning multiplicity a function of
        // {@link #lookAheadVerdict} memo timing.
        // The input is not itself a modeled relation, so it has no table to decide here; its
        // fields resolve against the consuming field's return table at each call site
        // (FieldBuilder.classifyArgument's plain-input path via InputFieldResolver, or the
        // arg-level @lookupKey path via resolveInputFields), so an input reused across tables
        // resolves per-consumer.
        return buildPlainInputType(inputType, name, location);
    }

    /**
     * The narrow field-resolution fact a table-relative input-field resolution produces: either
     * the resolved {@link InputField} list or the typed consequence of its failures. Deliberately a
     * bare fields carrier and not a per-type model record: the field-derived write-target paths
     * in {@code FieldBuilder} need only the fields, resolved against the consuming field's
     * table.
     */
    sealed interface InputFieldsResolution {
        record Resolved(List<InputField> fields) implements InputFieldsResolution {}

        /**
         * The causes are already minted as located diagnostics at the input fields that carry them
         * (see {@link BuildContext#mintInputFieldFailures}), so this arm carries the consequence's
         * facts rather than their prose: which input type, against which table, and how many
         * failures were minted. A bare {@code Failed(Rejection)} would be the same
         * cause-versus-consequence polymorphism the classifier's own carrier refuses, and would
         * lose the count a related-information renderer needs.
         */
        record Failed(String inputTypeName, TableRef tableRef, int mintedCount)
                implements InputFieldsResolution {
            /** The consuming coordinate's rejection, rendered from the facts above. */
            Rejection consequence() {
                return Rejection.structural("input type '" + inputTypeName + "' mapped to table '"
                    + tableRef.tableName() + "': " + mintedCount + " input field"
                    + (mintedCount == 1 ? "" : "s") + " could not be resolved");
            }
        }
    }

    /**
     * Resolves a list of raw input fields against a {@link TableRef} into fully-classified
     * {@link InputField}s (or the accumulated-failure prose). The single home of the input-field
     * classification loop for the field-derived write-target paths in {@code FieldBuilder}, so
     * every route classifies identical schema defects identically.
     */
    InputFieldsResolution resolveInputFields(String name, List<GraphQLInputObjectField> fields, TableRef tableRef) {
        var failures = new ArrayList<InputFieldResolution.Unresolved>();
        var conditionFailures = new ArrayList<InputFieldConditionFailure>();
        var resolvedFields = new ArrayList<InputField>();
        for (var f : fields) {
            // @table input column-coverage is deferred to consumption. The classifier
            // already lifts column-miss to InputField.UnboundField; non-column-miss failures
            // (notGenerated, @reference path, NodeId resolution, circular nesting) remain
            // Unresolved and surface here.
            var resolution = ctx.classifyInputField(f, name, tableRef, ClassifyContext.root(), conditionFailures);
            switch (resolution) {
                case InputFieldResolution.Resolved r -> resolvedFields.add(r.field());
                case InputFieldResolution.Unresolved u -> failures.add(u);
            }
        }
        if (!failures.isEmpty() || !conditionFailures.isEmpty()) {
            // One located diagnostic per cause, at the input field that carries it; the consuming
            // coordinate keeps the consequence alone.
            int minted = ctx.mintInputFieldFailures(name, failures, conditionFailures);
            return new InputFieldsResolution.Failed(name, tableRef, minted);
        }
        return new InputFieldsResolution.Resolved(List.copyOf(resolvedFields));
    }

    /**
     * Constructs the appropriate {@link InputType} sub-type from the resolved backing class,
     * symmetric with {@link #buildResultType}: {@link RecordBindingResolver} reflection is the
     * only source. An input type with no reflected producer binding is a backing-less
     * {@link GraphitronType.PojoInputType}; the deprecated {@code @record} directive never
     * supplies a fallback className.
     */
    private GraphitronType buildPlainInputType(GraphQLInputObjectType inputType, String name, SourceLocation location) {
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
     * field and resolving its Java type. SDL scalar fields lift via the
     * {@link no.sikt.graphitron.rewrite.ScalarTypeResolver}; SDL enum fields lift to
     * {@code String} (graphql-java delivers enum values as their name string); SDL list wraps
     * compose {@code List<X>}; nested input refs resolve to the emitted record's
     * {@link ClassName} (forward-declared; javapoet does not require the class to exist at
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
            // Consumer-declared scalar that didn't resolve as a spec built-in: defer to the
            // scalar fixed point (BuildContext.scalarVerdicts, registry-free, so this read is
            // safe while the walk is mid-flight). The classifier has either succeeded (a
            // GraphitronType.ScalarType entry) or rejected the type; if it rejected, the
            // surrounding input type would already be UnclassifiedType by reachable-closure on
            // the input field. Treat absence as Object so the validator walk still works; jOOQ
            // rebinds at value-set time via getDataType().
            var classified = ctx.scalarVerdicts.get(scalar.getName());
            if (classified instanceof GraphitronType.ScalarType st) {
                return st.resolution().javaType();
            }
            return ClassName.get(Object.class);
        }
        return null;
    }

    /**
     * Lifts one entry from the {@code handlers} array on an {@code @error} directive into the
     * sealed {@link ErrorType.Handler} variant matching the entry's discriminator. Returns
     * {@code null} and appends a reason to {@code rejectReasons} if the entry is unparseable or
     * violates one of the parse-time intra-handler reject rules (the numbered rules below).
     * Channel-level reject rules live with the carrier classifier; the no-fields-beyond-
     * path/message rule is applied by the caller.
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
        // The description fork is resolved here, once: every downstream consumer switches on the
        // arm instead of re-branching an Optional, and the emitted message() body picks its
        // statement per arm rather than testing a decided value at runtime.
        ErrorType.ClientMessage clientMessage = description == null
            ? new ErrorType.ClientMessage.FromSource()
            : new ErrorType.ClientMessage.Static(description);

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
                return new ErrorType.ExceptionHandler(className, matchesOpt, clientMessage);
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
                    return new ErrorType.SqlStateHandler(sqlState, matchesOpt, clientMessage);
                }
                if (code != null) {
                    return new ErrorType.VendorCodeHandler(code, matchesOpt, clientMessage);
                }
                // No-discriminator DATABASE lifts to ExceptionHandler(SQLException). SQLException
                // always resolves on the classifier classpath, so no Class.forName check is
                // needed here.
                return new ErrorType.ExceptionHandler("java.sql.SQLException", matchesOpt, clientMessage);
            }
            case VALIDATION -> {
                // Rule 5: VALIDATION takes neither discriminators, nor matches, nor a client
                // message. The tail has to be true of whichever subset fired, so it states both
                // reasons: the pre-execution step never runs a match (which is what rules out the
                // discriminators), and it produces one GraphQLError per ConstraintViolation, each
                // already carrying its own interpolated message (which is what rules out
                // 'description', and names where that authoring surface does live).
                List<String> disallowed = new ArrayList<>();
                if (className != null) disallowed.add("className");
                if (sqlState != null) disallowed.add("sqlState");
                if (code != null) disallowed.add("code");
                if (matches != null) disallowed.add("matches");
                if (description != null) disallowed.add("description");
                if (!disallowed.isEmpty()) {
                    rejectReasons.add("@error handler {handler: VALIDATION} cannot carry "
                        + String.join(", ", disallowed)
                        + " (validation runs as a wrapper pre-execution step against"
                        + " jakarta.validation.Validator, matching nothing, so class identity and"
                        + " SQL discriminators do not apply; and it emits one error per"
                        + " constraint violation carrying that violation's own interpolated"
                        + " message, so a single client-facing string belongs on the constraint"
                        + " annotation's 'message' attribute, not here)");
                    return null;
                }
                return new ErrorType.ValidationHandler();
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
     * verifies it's a {@link Throwable} subtype. Returns a non-null reject reason when the class
     * cannot be loaded or doesn't extend {@code Throwable}; returns {@code null} on a clean
     * resolution. The runtime matcher walks the cause chain testing each link with
     * {@code Class.isInstance}, so a non-{@code Throwable} class would never match anything and
     * is almost certainly a typo.
     */
    // Instance method (not static) so it can read ctx.codegenLoader(); the explicit-parameter
    // sibling lives at CheckedExceptionMatcher.unmatched, which crosses a class boundary.
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
