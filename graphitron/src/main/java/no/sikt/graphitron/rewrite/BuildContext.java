package no.sikt.graphitron.rewrite;

import graphql.language.ArrayValue;
import graphql.schema.GraphQLType;
import graphql.language.BooleanValue;
import graphql.language.NullValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.ConditionJoin;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import org.jooq.ForeignKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared build-time state and stateless utilities used by {@link TypeBuilder},
 * {@link FieldBuilder}, and {@link ServiceCatalog}.
 *
 * <p>{@link #types} is a live view of the type registry, empty until the single classification walk
 * (see {@link TypeBuilder#classifyAndRegister} and {@link TypeBuilder#finishTypeClassification()})
 * populates it. Field classification reads it only for the field's own parent verdict, never a
 * not-yet-visited sibling's (R317 slices 3a–3e make every cross-type read registry-free).
 */
class BuildContext {

    private static final Logger NODE_ID_SHIM_LOGGER = LoggerFactory.getLogger(BuildContext.class);
    private static final Logger ID_REF_SHIM_LOGGER =
        LoggerFactory.getLogger(BuildContext.class.getName() + ".idRefShim");

    // ===== Directive names =====

    static final String DIR_TABLE               = "table";
    static final String DIR_SCALAR_TYPE         = "scalarType";
    static final String DIR_RECORD              = "record";
    static final String DIR_DISCRIMINATE        = "discriminate";
    static final String DIR_NODE                = "node";
    static final String DIR_NOT_GENERATED       = "notGenerated";
    static final String DIR_MULTITABLE_REFERENCE = "multitableReference";
    static final String DIR_NODE_ID             = "nodeId";
    static final String DIR_FIELD               = "field";
    static final String DIR_REFERENCE           = "reference";
    static final String DIR_ERROR               = "error";
    static final String DIR_TABLE_METHOD        = "tableMethod";
    static final String DIR_ROUTINE             = "routine";
    static final String DIR_DEFAULT_ORDER       = "defaultOrder";
    static final String DIR_SPLIT_QUERY         = "splitQuery";
    static final String DIR_SERVICE             = "service";
    static final String DIR_EXTERNAL_FIELD      = "externalField";
    static final String DIR_LOOKUP_KEY          = "lookupKey";
    static final String DIR_ORDER_BY            = "orderBy";
    static final String DIR_CONDITION           = "condition";
    static final String DIR_MUTATION            = "mutation";
    static final String DIR_DISCRIMINATOR       = "discriminator";
    static final String DIR_AS_CONNECTION       = "asConnection";
    static final String DIR_SOURCE_ROW          = "sourceRow";

    // ===== Argument names =====

    static final String ARG_CONTEXT_ARGUMENTS  = "contextArguments";
    static final String ARG_RECORD             = "record";
    static final String ARG_SERVICE_REF        = "service";
    static final String ARG_EXTERNAL_FIELD_REF = "reference";
    static final String ARG_METHOD             = "method";
    static final String ARG_ARG_MAPPING        = "argMapping";
    static final String ARG_VALUE              = "value";
    static final String ARG_NAME               = "name";
    static final String ARG_ON                 = "on";
    static final String ARG_TYPE_ID            = "typeId";
    static final String ARG_KEY_COLUMNS        = "keyColumns";
    static final String ARG_TYPE_NAME          = "typeName";
    static final String ARG_SCALAR             = "scalar";
    static final String ARG_PATH               = "path";
    static final String ARG_KEY                = "key";
    static final String ARG_CONDITION          = "condition";
    static final String ARG_TABLE_REF          = "table";
    static final String ARG_INDEX              = "index";
    static final String ARG_FIELDS             = "fields";
    static final String ARG_PRIMARY_KEY        = "primaryKey";
    static final String ARG_DIRECTION          = "direction";
    static final String ARG_COLLATE            = "collate";
    static final String ARG_HANDLERS           = "handlers";
    static final String ARG_HANDLER            = "handler";
    static final String ARG_CLASS_NAME         = "className";
    static final String ARG_CODE               = "code";
    static final String ARG_SQL_STATE          = "sqlState";
    static final String ARG_MATCHES            = "matches";
    static final String ARG_DESCRIPTION        = "description";
    static final String ARG_DEFAULT_FIRST_VALUE = "defaultFirstValue";
    static final String ARG_CONNECTION_NAME     = "connectionName";
    static final String ARG_OVERRIDE            = "override";
    static final String ARG_MULTI_ROW           = "multiRow";

    // ===== Shared state =====

    final GraphQLSchema schema;
    final JooqCatalog catalog;
    final RewriteContext ctx;
    /**
     * Type-axis classification registry. The single classification walk
     * ({@link TypeBuilder#classifyAndRegister} on the walk, {@link TypeBuilder#finishTypeClassification()}
     * after it) populates it via
     * {@link TypeRegistry#classify}, {@link TypeRegistry#enrich}, {@link TypeRegistry#demote},
     * and {@link TypeRegistry#synthesize}; downstream code reads
     * via {@link TypeRegistry#get} / {@link TypeRegistry#entries}. Replaces the previously bare
     * {@code Map<String, GraphitronType> types} field; the backing map is private so any new
     * write site has to go through one of the four named operations (visible in code review).
     */
    final TypeRegistry typeRegistry = new TypeRegistry();
    /**
     * Field-axis classification registry. {@code GraphitronSchemaBuilder.buildSchema} routes
     * output-field writes through {@link FieldRegistry#classify}; every call site of
     * {@link #classifyInputField} also notifies via {@link FieldRegistry#classifyInput} for
     * trace emission only (input fields are stored embedded in their parent type, not in a
     * central map).
     */
    final FieldRegistry fieldRegistry = new FieldRegistry();
    /**
     * Live, read-only view of {@link #typeRegistry}. Reads delegate to the registry's
     * backing map and reflect any subsequent {@code classify} / {@code enrich} / {@code demote}
     * / {@code synthesize} calls. Mutating operations on this map throw
     * {@link UnsupportedOperationException} — the only path to update classifications is the
     * registry itself.
     */
    final Map<String, GraphitronType> types = typeRegistry.entries();
    /**
     * R317 slice 2 — fixed-point reverse index over the schema's {@code @node} types
     * ({@link NodeIndex}), keyed by backing table and by type name. Populated once by
     * {@link TypeBuilder#buildClassificationIndices}. Field classification resolves every node-id
     * encoder through this index instead of scanning or keying into the type registry (retires
     * {@code FieldBuilder}'s four {@code types.values()} NodeType scans and the keyed
     * {@code ctx.types.get} at the {@code @nodeId(typeName:)} path), which is part of the precondition
     * for collapsing classification into a single walk (R317 slice 4). {@link NodeIndex#EMPTY} for
     * tests that build a registry without running the classification walk.
     */
    NodeIndex nodes = NodeIndex.EMPTY;
    /**
     * R317 slice 3d — pure, typename-keyed fixed-point index over the schema's table-backed types
     * ({@link TableIndex}). Populated once by {@link TypeBuilder#buildClassificationIndices}. Field
     * classification resolves the table-backed fact at its return/element/data-field edges through
     * this index instead of keying into the type registry (retires the {@code TableBackedType}
     * {@code ctx.types.get} reads, including the deep payload-data-field reads two hops below the
     * field being classified), which is part of the precondition for collapsing classification into a
     * single walk (R317 slice 4). {@link TableIndex#EMPTY} for tests that build a registry without
     * running the classification walk.
     */
    TableIndex tables = TableIndex.EMPTY;
    /**
     * R317 slice 3d — pure, typename-keyed fixed-point index over the schema's {@code @error} types
     * ({@link ErrorIndex}). Populated once by {@link TypeBuilder#buildClassificationIndices}. The
     * error-channel union-member scan resolves the error-member fact through this index instead of
     * keying into the type registry (retires the {@code ErrorType} {@code ctx.types.get} read two
     * hops below the field being classified). {@link ErrorIndex#EMPTY} for tests that build a
     * registry without running the classification walk.
     */
    ErrorIndex errors = ErrorIndex.EMPTY;
    /**
     * R317 slice 2 — fixed-point index, participant type name &rarr; (field name &rarr; the
     * participant's {@link ParticipantRef.TableBound.CrossTableField}). Populated once by
     * {@link TypeBuilder#buildClassificationIndices} from the {@code @table}+{@code @discriminate}
     * interface scan and the participants' {@code @reference} SDL. Retires
     * {@code FieldBuilder.lookupParticipantCrossTableField}'s nested registry scan.
     */
    Map<String, Map<String, ParticipantRef.TableBound.CrossTableField>> crossTableFieldsByParticipant = Map.of();
    /**
     * Set by {@link GraphitronSchemaBuilder} immediately after constructing {@link ServiceCatalog}.
     * Used by {@link #resolveConditionRef} for condition-join method reflection.
     */
    ServiceCatalog svc;

    /**
     * R317 slice 4 — set by {@link GraphitronSchemaBuilder} immediately after constructing the
     * {@link TypeBuilder}. The single classify-and-emit walk classifies a field's output target only
     * when the walk reaches it, so during a field's classification its target composite may not be
     * registered yet; the return-type and DML-element resolvers below read the target verdict through
     * {@link TypeBuilder#lookAheadVerdict} (a registry-free recompute) rather than {@code types.get},
     * keeping field classification independent of walk order (the read-free visitor invariant). Null
     * only before the walk has been wired (the indices and {@code lookAheadVerdict} both need
     * {@code prepareForWalk} to have run first).
     */
    TypeBuilder typeBuilder;

    /**
     * SDL-level scalar applied-directive map, populated by {@link GraphitronSchemaBuilder} from
     * the {@link graphql.schema.idl.TypeDefinitionRegistry} *before* the classification walk
     * runs. Graphql-java's {@code SchemaGenerator} strips applied directives from spec built-in
     * scalar redeclarations (it picks its own {@code GraphQLString} / {@code GraphQLInt} / ...
     * instances and discards the SDL's applied directives), so a check that relies on the
     * assembled schema cannot detect {@code @scalarType} on a built-in. Pre-reading the registry
     * here keeps the {@code Rejection.InvalidSchema.DirectiveConflict} signal from getting lost.
     *
     * <p>Entry: SDL scalar type name → bare directive name set (e.g. {@code "scalarType"}). Empty
     * for tests that don't go through the registry-aware {@code buildBundle} path.
     */
    private final Map<String, Set<String>> sdlScalarDirectiveNames = new LinkedHashMap<>();

    /**
     * Non-fatal advisories collected during classification. Surfaced to the Maven log by
     * the plugin's validate / generate mojos; never fail the build. See {@link BuildWarning}.
     */
    private final List<BuildWarning> warnings = new ArrayList<>();
    /**
     * Build-time validation diagnostics the immutable validate phase (R317 slice 5) accumulates
     * instead of demoting a classified verdict to {@code UnclassifiedType} / {@code UnclassifiedField}.
     * The global soundness reductions (node-typeId uniqueness, case-fold collisions, the
     * dangling-reference backstop, the federation {@code @key} checks, and the multi-producer
     * {@code DomainReturnType} agreement) register a {@link ValidationError} here rather than
     * overwriting the registry, so a verdict read after the walk equals the verdict classification
     * produced. {@code GraphitronSchemaBuilder} hands the list to {@link GraphitronSchema}; the
     * validator drains it into the same {@link ValidationError} stream it emits today, so which
     * schemas pass or fail is unchanged.
     */
    private final List<ValidationError> diagnostics = new ArrayList<>();
    private final Map<String, List<String>> typeNamesByTableKey;
    private final NodeIdLeafResolver nodeIdLeafResolver;

    BuildContext(GraphQLSchema schema, JooqCatalog catalog, RewriteContext ctx) {
        // schema and catalog stay nullable for tests that focus on plumbing the other half; ctx
        // is required because every classifier the BuildContext fans into reads at least one of
        // its fields (codegenLoader, jooqPackage, classpathRoots). Test sites that don't care
        // about ctx still construct a deterministic stub via RewriteContext's 6-arg overload.
        this.schema = schema;
        this.catalog = catalog;
        this.ctx = java.util.Objects.requireNonNull(ctx, "ctx");
        this.typeNamesByTableKey = buildTypeNamesByTableKey(schema);
        this.nodeIdLeafResolver = new NodeIdLeafResolver(this);
    }

    /**
     * Records the SDL-declared applied-directive names on a scalar type, read from the
     * {@link graphql.schema.idl.TypeDefinitionRegistry} before {@code SchemaGenerator} strips
     * directives off spec built-in redeclarations. See the {@link #sdlScalarDirectiveNames}
     * field javadoc for rationale.
     */
    void recordSdlScalarDirectives(String scalarName, Set<String> directiveNames) {
        if (directiveNames.isEmpty()) return;
        sdlScalarDirectiveNames.put(scalarName, Set.copyOf(directiveNames));
    }

    /**
     * Returns the SDL-declared applied-directive names on a scalar (whether or not graphql-java
     * carried them onto the assembled {@link graphql.schema.GraphQLScalarType}). Empty when the
     * scalar isn't declared in the SDL or when the pre-pass wasn't run.
     */
    Set<String> sdlScalarDirectiveNames(String scalarName) {
        return sdlScalarDirectiveNames.getOrDefault(scalarName, Set.of());
    }

    /**
     * Resolver for {@code @nodeId} leaf shape (same-table lookup vs FK-target filter). Constructed
     * once per {@code BuildContext} and shared by both {@link #classifyInputField} (input-field
     * leaves) and {@link FieldBuilder#classifyArgument} (top-level argument leaves) so the
     * shape decision lives in one place.
     */
    NodeIdLeafResolver nodeIdLeafResolver() {
        return nodeIdLeafResolver;
    }

    RewriteContext ctx() {
        return ctx;
    }

    /**
     * Loader for consumer-declared classes (service, record, condition, jOOQ catalog). Mirrors
     * {@link RewriteContext#codegenLoader()} so reflection sites holding a {@code BuildContext}
     * do not have to chain through {@code ctx().codegenLoader()}.
     */
    ClassLoader codegenLoader() {
        return ctx.codegenLoader();
    }

    void addWarning(BuildWarning warning) {
        warnings.add(warning);
    }

    List<BuildWarning> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * Records a build-time validation diagnostic (R317 slice 5). Used by the immutable validate
     * phase's global reductions in place of demoting a classified verdict; see {@link #diagnostics}.
     */
    void addDiagnostic(ValidationError diagnostic) {
        diagnostics.add(diagnostic);
    }

    List<ValidationError> diagnostics() {
        return List.copyOf(diagnostics);
    }

    // ===== Directive-reading helpers =====

    /**
     * Returns the stripped String value of an applied directive argument, if present.
     */
    static Optional<String> argString(GraphQLDirectiveContainer container, String directive, String arg) {
        var dir = container.getAppliedDirective(directive);
        if (dir == null) return Optional.empty();
        var argument = dir.getArgument(arg);
        if (argument == null) return Optional.empty();
        Object value = argument.getValue();
        if (value instanceof StringValue sv) return Optional.of(sv.getValue().strip());
        if (value instanceof String s) return Optional.of(s.strip());
        return Optional.empty();
    }

    /**
     * Returns the String values of a list applied-directive argument, or an empty list if absent.
     */
    static List<String> argStringList(GraphQLDirectiveContainer container, String directive, String arg) {
        var dir = container.getAppliedDirective(directive);
        if (dir == null) return List.of();
        var argument = dir.getArgument(arg);
        if (argument == null) return List.of();
        Object value = argument.getValue();
        if (value instanceof StringValue sv) return List.of(sv.getValue().strip());
        if (value instanceof String s) return List.of(s.strip());
        if (value instanceof ArrayValue av) {
            return av.getValues().stream()
                .map(v -> v instanceof NullValue ? null : ((StringValue) v).getValue().strip())
                .toList();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(v -> v == null ? null : v.toString().strip())
                .toList();
        }
        return List.of();
    }

    /**
     * Returns the boolean value of an applied directive argument, handling both AST literal form
     * ({@link BooleanValue}) and coerced form ({@link Boolean}). Returns {@code defaultValue} if
     * the directive or argument is absent, or the value cannot be interpreted as a boolean.
     */
    static boolean argBoolean(GraphQLDirectiveContainer container, String directive, String arg, boolean defaultValue) {
        var dir = container.getAppliedDirective(directive);
        if (dir == null) return defaultValue;
        var argument = dir.getArgument(arg);
        if (argument == null) return defaultValue;
        Object value = argument.getValue();
        if (value instanceof BooleanValue bv) return bv.isValue();
        if (value instanceof Boolean b) return b;
        return defaultValue;
    }

    /**
     * Casts an object to {@code Map<String, Object>}. Used when processing input object values
     * returned by graphql-java after directive coercion.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object v) {
        return (Map<String, Object>) v;
    }

    // ===== Source-location helpers =====

    static SourceLocation locationOf(GraphQLObjectType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLInterfaceType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLUnionType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLFieldDefinition field) {
        var def = field.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLInputObjectField field) {
        var def = field.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(GraphQLInputObjectType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    static SourceLocation locationOf(graphql.schema.GraphQLScalarType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    /** Dispatches to the correct typed overload for any {@link GraphQLNamedType}. */
    static SourceLocation locationOf(GraphQLNamedType namedType) {
        return switch (namedType) {
            case GraphQLObjectType t    -> locationOf(t);
            case GraphQLInterfaceType t -> locationOf(t);
            case GraphQLUnionType t     -> locationOf(t);
            case graphql.schema.GraphQLScalarType t -> locationOf(t);
            default                     -> null;
        };
    }

    // ===== Connection-type helpers =====

    /**
     * Returns {@code true} when {@code typeName} refers to a Relay connection type — i.e. an object
     * type whose {@code edges} field's element type has a {@code node} field.
     */
    boolean isConnectionType(String typeName) {
        if (!(schema.getType(typeName) instanceof GraphQLObjectType connType)) return false;
        var edgesField = connType.getFieldDefinition("edges");
        if (edgesField == null) return false;
        var edgeType = GraphQLTypeUtil.unwrapAll(edgesField.getType());
        return edgeType instanceof GraphQLObjectType edgeObj && edgeObj.getFieldDefinition("node") != null;
    }

    /** Returns the nullability of the {@code edges.node} field for a confirmed connection type. */
    boolean connectionItemNullable(String connectionTypeName) {
        var connType = (GraphQLObjectType) schema.getType(connectionTypeName);
        var edgesField = connType.getFieldDefinition("edges");
        var edgeType = (GraphQLObjectType) GraphQLTypeUtil.unwrapAll(edgesField.getType());
        var nodeField = edgeType.getFieldDefinition("node");
        return !(nodeField.getType() instanceof GraphQLNonNull);
    }

    /** Returns the element type name for a confirmed connection type by navigating {@code edges.node}. */
    String connectionElementTypeName(String connectionTypeName) {
        var connType = (GraphQLObjectType) schema.getType(connectionTypeName);
        var edgesField = connType.getFieldDefinition("edges");
        var edgeType = (GraphQLObjectType) GraphQLTypeUtil.unwrapAll(edgesField.getType());
        var nodeField = edgeType.getFieldDefinition("node");
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(nodeField.getType())).getName();
    }

    // ===== Field type helpers =====

    /** Returns the unwrapped base type name of a field definition. */
    static String baseTypeName(GraphQLFieldDefinition fieldDef) {
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
    }

    /**
     * Converts a type name and wrapper into the correct {@link ReturnTypeRef} variant by
     * consulting the populated {@link #types} map.
     *
     * <p>R75 Phase 1: single-record DML payloads are not short-circuited here. Carrier-shaped
     * SDL Objects resolve through the {@code target instanceof ResultType} arm below and return
     * a {@link ReturnTypeRef.ResultReturnType} the mutation classifier reads structurally.
     */
    ReturnTypeRef resolveReturnType(String targetTypeName, FieldWrapper wrapper) {
        // R317 slice 4 — resolve the target's verdict registry-free (look-ahead), not types.get: under
        // the single classify-and-emit walk a field's output target is a not-yet-visited child, so a
        // registry read would miss it. lookAheadVerdict reproduces the verdict types.get returned in the
        // two-pass world (classifyType + producer-bound carrier), and a NestingType / ConnectionType /
        // carrier (none of the four arms below) falls through to ScalarReturnType under both, identically.
        GraphitronType target = typeBuilder.lookAheadVerdict(targetTypeName);
        if (target instanceof TableBackedType tbt)
            return new ReturnTypeRef.TableBoundReturnType(targetTypeName, tbt.table(), wrapper);
        if (target instanceof InterfaceType || target instanceof UnionType)
            return new ReturnTypeRef.PolymorphicReturnType(targetTypeName, wrapper);
        if (target instanceof ResultType rt)
            return new ReturnTypeRef.ResultReturnType(targetTypeName, wrapper, rt.fqClassName());
        return new ReturnTypeRef.ScalarReturnType(targetTypeName, wrapper);
    }

    /**
     * Builds a {@link FieldWrapper} from the return type shape of {@code fieldDef} (cardinality
     * and nullability only). Ordering is separated into the field-builder pipeline.
     *
     * <p>Connection is detected two ways:
     * <ol>
     *   <li><b>Directive-driven:</b> the field has {@code @asConnection} — the schema type is a
     *       bare list {@code [Film]} but the wrapper is {@link FieldWrapper.Connection}.</li>
     *   <li><b>Structural:</b> the return type has an {@code edges.node} pattern (pre-expanded
     *       connection from the schema transform or hand-written).</li>
     * </ol>
     *
     * <p>Lives on {@link BuildContext} (rather than the field-builder pipeline) so non-builder
     * sites can compute a wrapper without holding a {@code FieldBuilder} reference.
     */
    FieldWrapper buildWrapper(GraphQLFieldDefinition fieldDef) {
        GraphQLType fieldType = fieldDef.getType();
        boolean outerNullable = !(fieldType instanceof GraphQLNonNull);
        GraphQLType unwrappedOnce = GraphQLTypeUtil.unwrapNonNull(fieldType);

        if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION) && unwrappedOnce instanceof GraphQLList) {
            return new FieldWrapper.Connection(outerNullable, PaginationResolver.resolveDefaultFirstValue(fieldDef));
        }

        if (unwrappedOnce instanceof GraphQLList listType) {
            boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
            return new FieldWrapper.List(outerNullable, itemNullable);
        }

        // Structural detection: pre-expanded Connection type with edges.node pattern.
        if (isConnectionType(baseTypeName(fieldDef))) {
            return new FieldWrapper.Connection(outerNullable, FieldWrapper.DEFAULT_PAGE_SIZE);
        }

        return new FieldWrapper.Single(outerNullable);
    }

    /**
     * R178 Phase 4 — sealed result of a payload's structural carrier-shape scan, used by the
     * @mutation classifier and MutationInputResolver paths to decide whether a payload type
     * admits as a single-record DML payload. Three arms:
     *
     * <ul>
     *   <li>{@link Admit}: exactly one non-errors data field whose element classifies into a
     *       recognized DML element kind (Table / Record / Id). The data field's definition and
     *       element kind ride together so callers don't re-walk the SDL.</li>
     *   <li>{@link Reject}: at least one non-errors field that can't be admitted (scalar or
     *       polymorphic / interface / union element types), or multiple recognized data fields.</li>
     *   <li>{@link NotApplicable}: zero non-errors data fields, non-Object payload type, or
     *       a {@code null} payload name. Callers fall through to their non-carrier code paths.</li>
     * </ul>
     */
    public sealed interface DmlPayloadScan {
        record Admit(GraphQLFieldDefinition dataField, DmlElementKind element) implements DmlPayloadScan {}
        record Reject(String reason) implements DmlPayloadScan {}
        record NotApplicable() implements DmlPayloadScan {}
    }

    public sealed interface DmlElementKind {
        record Table(TableRef table, String elementTypeName) implements DmlElementKind {}
        record RecordElement(String fieldName) implements DmlElementKind {}
        record IdElement() implements DmlElementKind {}
    }

    /**
     * R310 — the answer to "would this payload classify as a DML carrier were it not for a
     * forbidden directive on its data field". Returned by {@link #diagnoseForbiddenCarrierDirective}
     * so the {@code @mutation} return-type diagnostic can name the offending field and directive
     * (the {@code @}-prefixed directive name) instead of the misdirected "use ID or a @table type".
     */
    public record ForbiddenCarrierDirective(String dataFieldName, String directiveName) {}

    /**
     * R178 Phase 4 — structural detection of a DML payload's carrier shape. Walks the payload
     * SDL once, accumulating non-errors fields and classifying each into a recognized DML
     * element kind (Table / Record / Id) or rejecting unrecognized shapes. Errors-shaped
     * fields are identified via {@link #detectErrorsFieldShape} and skipped at the data-field
     * level (they carry through the error-channel resolution).
     *
     * <p>Drives the @mutation classifier and the MutationInputResolver shape-coherence check.
     * The scan is referentially transparent given a frozen schema and types.
     */
    private static final java.util.Set<String> FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES = java.util.Set.of(
        DIR_SERVICE, DIR_SOURCE_ROW, DIR_REFERENCE, DIR_AS_CONNECTION, DIR_SPLIT_QUERY,
        DIR_EXTERNAL_FIELD, DIR_CONDITION, DIR_LOOKUP_KEY, DIR_NOT_GENERATED,
        DIR_TABLE_METHOD, DIR_DEFAULT_ORDER, DIR_ORDER_BY, DIR_MULTITABLE_REFERENCE);

    // R275: the @service-carrier scan tolerates @splitQuery on the data field. The carrier's
    // data-field emit already resolves through a PK-keyed follow-up SELECT off the producer's
    // record (FetcherEmitter's Wrap.TableRecord arm), so @splitQuery adds nothing there; the
    // opptak schemas carry it on their `sak`/`saker` data fields, and dropping the whole carrier
    // over a redundant directive produced an invalid assembled schema (dangling payload typeRef).
    // The classifier fires a redundancy advisory instead (FieldBuilder's
    // warnIfSplitQueryOnRecordParent family). DML carriers keep the strict set.
    private static final java.util.Set<String> FORBIDDEN_SERVICE_CARRIER_DATA_FIELD_DIRECTIVES =
        FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES.stream()
            .filter(d -> !d.equals(DIR_SPLIT_QUERY))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * R275 — the carrier family the structural scan is run for. A named axis (not a flag set)
     * because the families differ on two coupled policies: the forbidden-directive set on the
     * data field and the ID-element wrapper admission. DML (DELETE) carriers reject the
     * list-of-nullable {@code [ID]} and Connection wrappers (every element of a successful
     * DELETE response is the encoded PK of an actually-deleted row, so the slot cannot be
     * null); {@code @service} carriers admit {@code [ID]} (the data field projects whatever
     * record list the service returned, and the opptak schemas declare the weaker {@code [ID]}
     * contract) while still rejecting Connection. A third family extends the enum and gets
     * exhaustiveness prompts at both policy sites.
     */
    private enum CarrierFamily {
        DML(FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES),
        SERVICE(FORBIDDEN_SERVICE_CARRIER_DATA_FIELD_DIRECTIVES);

        final java.util.Set<String> forbiddenDataFieldDirectives;
        CarrierFamily(java.util.Set<String> forbiddenDataFieldDirectives) {
            this.forbiddenDataFieldDirectives = forbiddenDataFieldDirectives;
        }
    }

    /**
     * R310 — whether {@link #scanStructuralPayload} consults the family's forbidden-directive set on
     * the data field. {@code ENFORCE} is the only policy the public scan methods use, so their
     * behaviour (and every speculative caller's: {@code TypeBuilder.carrierTableBinding}, the existing
     * {@code validateReturnType} Reject probe) is byte-for-byte unchanged. {@code IGNORE} is consulted
     * solely by {@link #diagnoseForbiddenCarrierDirective}, to answer "would this admit as a DML
     * carrier were it not for the forbidden directive". This is an orthogonal, private gate on whether
     * the family's forbidden set is consulted, not a third {@link CarrierFamily}: the family stays
     * {@code DML} under {@code IGNORE}, so its other policy axis (ID-element wrapper admission) still
     * applies.
     */
    private enum ForbiddenDirectivePolicy { ENFORCE, IGNORE }

    public DmlPayloadScan scanStructuralDmlPayload(String payloadSdlName) {
        return scanStructuralPayload(payloadSdlName, CarrierFamily.DML);
    }

    /**
     * R275 — the {@code @service}-carrier variant of {@link #scanStructuralDmlPayload}. Identical
     * structural walk, but {@code @splitQuery} on the data field does not route the type away from
     * the carrier mold (on a producer-backed carrier the data field's fetcher already runs a
     * PK-keyed follow-up SELECT off the producer's record, so the directive is redundant rather
     * than a different fetcher contract), and an ID-element data field admits the list-of-nullable
     * {@code [ID]} wrapper (see {@link CarrierFamily}). Consulted by
     * {@code TypeBuilder.carrierTableBinding} for {@code ServiceEmitted}-bound candidates
     * and by the {@code @service} classifier's orphan-payload diagnostics.
     */
    public DmlPayloadScan scanStructuralServiceCarrierPayload(String payloadSdlName) {
        return scanStructuralPayload(payloadSdlName, CarrierFamily.SERVICE);
    }

    /**
     * Whether {@code payloadTypeName} is the (unwrapped) return type of a root {@code @service} field
     * on Query or Mutation. Read straight off the assembled schema, so it is independent of
     * field-classification order. The single producer of this fact, shared by
     * {@code FieldBuilder.isRootServiceProducedPayload} (which selects the payload-side errors
     * {@code WrapperArm} transport) and {@code TypeBuilder.carrierBinding} (R329 — which gates the
     * record-composite {@code ClassBacked} carrier recognition on the payload actually being
     * {@code @service}-produced, so an orphan payload whose data-field element happens to bind via an
     * unrelated producer is not mistaken for a carrier); the two cannot drift.
     */
    public boolean isServiceProducedPayload(String payloadTypeName) {
        return hasServiceFieldReturning(schema.getQueryType(), payloadTypeName)
            || hasServiceFieldReturning(schema.getMutationType(), payloadTypeName);
    }

    private static boolean hasServiceFieldReturning(GraphQLObjectType root, String payloadTypeName) {
        if (root == null) return false;
        for (var f : root.getFieldDefinitions()) {
            if (f.hasAppliedDirective(DIR_SERVICE)
                    && payloadTypeName.equals(
                        ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(f.getType())).getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * R310 — the would-admit-but-for-the-directive probe. Answers exactly the question the
     * misdirected "use ID or a @table type" diagnostic fails to: would {@code payloadSdlName} classify
     * as a DML carrier were it not for a forbidden directive on its data field, and if so, which
     * directive (on which field) blocked it?
     *
     * <p>Re-runs the structural DML scan with the forbidden-directive check disabled
     * ({@link ForbiddenDirectivePolicy#IGNORE}). If that pass {@code Admit}s, the payload is a
     * structurally valid DML carrier in every respect except the forbidden directive; the offending
     * directive is read off the admitted data field (the first
     * {@link #FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES} entry the field carries) and returned,
     * {@code @}-prefixed. Reading it off the admitted field is a single-field lookup, not a re-walk.
     * If the {@code IGNORE} pass {@code Reject}s or is {@code NotApplicable}, the directive is not the
     * sole blocker (the type has a different or additional problem) and the result is empty, so the
     * caller falls through to its generic diagnostic. The family stays {@code DML}, so the question is
     * specifically "would this admit as a <em>DML</em> carrier", not "under some looser family".
     */
    public Optional<ForbiddenCarrierDirective> diagnoseForbiddenCarrierDirective(String payloadSdlName) {
        if (!(scanStructuralPayload(payloadSdlName, CarrierFamily.DML, ForbiddenDirectivePolicy.IGNORE)
                instanceof DmlPayloadScan.Admit admit)) {
            return Optional.empty();
        }
        var dataField = admit.dataField();
        for (String forbidden : FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES) {
            if (dataField.hasAppliedDirective(forbidden)) {
                return Optional.of(new ForbiddenCarrierDirective(dataField.getName(), "@" + forbidden));
            }
        }
        return Optional.empty();
    }

    private DmlPayloadScan scanStructuralPayload(String payloadSdlName, CarrierFamily family) {
        return scanStructuralPayload(payloadSdlName, family, ForbiddenDirectivePolicy.ENFORCE);
    }

    private DmlPayloadScan scanStructuralPayload(String payloadSdlName, CarrierFamily family, ForbiddenDirectivePolicy policy) {
        if (payloadSdlName == null) return new DmlPayloadScan.NotApplicable();
        var payloadType = schema.getType(payloadSdlName);
        if (!(payloadType instanceof GraphQLObjectType payloadObj)) {
            return new DmlPayloadScan.NotApplicable();
        }
        GraphQLFieldDefinition admittedDataField = null;
        DmlElementKind admittedElement = null;
        int dataChannelCount = 0;
        for (var f : payloadObj.getFieldDefinitions()) {
            var errorTypes = detectErrorsFieldShape(f);
            if (errorTypes != null) {
                // §1 channel-level rules: rule 7 (handler cardinality) and rule 8 (duplicate
                // match criteria). Test fixtures pin the wording, so the diagnostic family is
                // preserved through the structural scan.
                String rule7 = FieldBuilder.checkChannelLevelHandlerRules(errorTypes);
                if (rule7 != null) {
                    return new DmlPayloadScan.Reject(
                        "errors-shaped carrier field '" + f.getName() + "': " + rule7);
                }
                String rule8 = FieldBuilder.checkDuplicateMatchCriteria(errorTypes);
                if (rule8 != null) {
                    return new DmlPayloadScan.Reject(
                        "errors-shaped carrier field '" + f.getName() + "': " + rule8);
                }
                continue;
            }
            // R159: parse-time check on @field(name:). UnknownSigil ($-prefixed values that
            // aren't recognized literals like $source) reject ahead of the element-shape
            // dispatch with the canonical FieldSourceSigil.unknownSigilMessage wording.
            var fieldNameRef = FieldSourceSigil.parseArgFieldNameRef(f, DIR_FIELD, ARG_NAME);
            if (fieldNameRef instanceof FieldSourceSigil.ParseResult.UnknownSigil unknown) {
                return new DmlPayloadScan.Reject(FieldSourceSigil.unknownSigilMessage(unknown.raw()));
            }
            // Forbidden-directives check. The listed directives signal a different fetcher
            // contract than a payload carrier's data-field path, so their presence routes the
            // type away from the DML-payload mold. Note: @field is intentionally NOT on this
            // list (R178 retires the @field-on-non-$source HardReject — the SettKvotesporsmal
            // bug fix). Pure-metadata directives (@deprecated, custom directives without
            // execution semantics) pass through. R310: skipped under IGNORE so
            // diagnoseForbiddenCarrierDirective can establish would-admit-but-for-the-directive.
            if (policy == ForbiddenDirectivePolicy.ENFORCE) {
                for (String forbidden : family.forbiddenDataFieldDirectives) {
                    if (f.hasAppliedDirective(forbidden)) {
                        return new DmlPayloadScan.NotApplicable();
                    }
                }
            }
            String elementTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(f.getType())).getName();
            // R317 slice 4 — registry-free look-ahead at the payload data field's element type, not
            // types.get: this scan runs during field classification (and via carrierTableBinding), when
            // the element composite may not be registered yet. lookAheadVerdict reproduces the verdict;
            // the element is a @table or record-bound type (classifyType non-null), so the look-ahead
            // resolves without recursing back through carrierTableBinding.
            var elementType = typeBuilder.lookAheadVerdict(elementTypeName);
            DmlElementKind kind;
            if (elementType instanceof GraphitronType.TableBackedType tbt) {
                kind = new DmlElementKind.Table(tbt.table(), elementTypeName);
            } else if (elementType instanceof GraphitronType.ResultType rt && rt.fqClassName() != null) {
                kind = new DmlElementKind.RecordElement(f.getName());
            } else if ("ID".equals(elementTypeName)) {
                // ID-element wrapper-shape rules, per carrier family (see CarrierFamily). DML
                // (R156): list-of-nullable ([ID]) and Connection wrappers reject on
                // payload-returning DELETE; test fixtures pin these diagnostic wordings.
                // SERVICE (R275): [ID] admits (the opptak fjernSakTagger shape); Connection
                // still rejects.
                var wrapper = buildWrapper(f);
                if (family == CarrierFamily.DML
                        && wrapper instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.List list
                        && list.itemNullable()) {
                    return new DmlPayloadScan.Reject(
                        "single-record carrier field '" + f.getName() + "' has element type 'ID' "
                        + "with a list-of-nullable wrapper '[ID]'; payload-returning DELETE requires "
                        + "either singleton (ID / ID!) or list-of-non-null ([ID!] / [ID!]!), since "
                        + "every element of a successful DELETE response is the encoded PK of an "
                        + "actually-deleted row, so the slot cannot be null");
                }
                if (wrapper instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
                    return new DmlPayloadScan.Reject(switch (family) {
                        case DML -> "single-record carrier field '" + f.getName() + "' has element type 'ID' "
                            + "with a Connection wrapper; payload-returning DELETE requires either "
                            + "singleton (ID / ID!) or list-of-non-null ([ID!] / [ID!]!)";
                        case SERVICE -> "single-record carrier field '" + f.getName() + "' has element type 'ID' "
                            + "with a Connection wrapper; an @service-carrier ID data field requires "
                            + "a singleton (ID / ID!) or plain list ([ID] / [ID!] / [ID!]!) wrapper";
                    });
                }
                kind = new DmlElementKind.IdElement();
            } else {
                return new DmlPayloadScan.Reject(
                    "carrier field '" + f.getName() + "' of type '" + elementTypeName
                    + "' is not a recognized DML payload data-field shape "
                    + "(expected @table-element, record-backed element, or ID-element); "
                    + "file a roadmap item if this shape needs admission");
            }
            dataChannelCount++;
            if (admittedDataField == null) {
                admittedDataField = f;
                admittedElement = kind;
            }
        }
        if (dataChannelCount == 0) return new DmlPayloadScan.NotApplicable();
        if (dataChannelCount > 1) {
            return new DmlPayloadScan.Reject(
                "single-record carrier '" + payloadSdlName + "' declares " + dataChannelCount
                + " data-channel-shaped fields; require exactly one (a future Backlog item may "
                + "admit multi-data carriers)");
        }
        return new DmlPayloadScan.Admit(admittedDataField, admittedElement);
    }

    /**
     * Lightweight predicate for "this GraphQL field is an {@code errors}-shaped field" (a
     * polymorphic-of-all-{@code @error} list with the nullability shape §2b allows). Returns
     * the resolved {@code List<ErrorType>} when the shape matches, {@code null} otherwise.
     *
     * <p>Mirrors the lift rules in {@code FieldBuilder.liftToErrorsField};
     * {@code FieldBuilder.detectStructuralDmlErrorChannel} and
     * {@code FieldBuilder.resolveErrorChannel} consume it through the same port so the two
     * classifier paths agree on what counts as an errors-shaped field.
     */
    List<GraphitronType.ErrorType> detectErrorsFieldShape(GraphQLFieldDefinition fieldDef) {
        var returnType = resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef));
        if (!(returnType instanceof ReturnTypeRef.PolymorphicReturnType poly)) {
            return null;
        }
        var schemaType = schema.getType(poly.returnTypeName());
        List<String> memberNames = switch (schemaType) {
            case GraphQLUnionType union -> union.getTypes().stream().map(GraphQLNamedType::getName).toList();
            case GraphQLInterfaceType iface ->
                schema.getImplementations(iface).stream().map(GraphQLObjectType::getName).toList();
            case null, default -> List.of();
        };
        if (memberNames.isEmpty()) return null;
        var errorTypes = new ArrayList<GraphitronType.ErrorType>();
        for (String memberName : memberNames) {
            // R317 slice 4 — error membership through the pure ErrorIndex (a fixed point built before
            // the walk), not types.get: the union members may not be registered yet during the walk.
            var et = errors.forName(memberName).orElse(null);
            if (et == null) {
                return null;
            }
            errorTypes.add(et);
        }
        if (!(poly.wrapper() instanceof FieldWrapper.List list) || !list.listNullable()) {
            return null;
        }
        return errorTypes;
    }

    /**
     * Converts a CamelCase identifier to SCREAMING_SNAKE_CASE. Used by
     * {@code FieldBuilder.detectStructuralDmlErrorChannel} to derive
     * {@code ErrorChannel.LocalContext.mappingsConstantName} from the wrapper SDL type name
     * (e.g. {@code FilmPayload} -&gt; {@code FILM_PAYLOAD}).
     */
    static String toScreamingSnake(String s) {
        if (s == null || s.isEmpty()) return s;
        var sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(s.charAt(i - 1))) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    // ===== Error-message helpers =====

    /**
     * Builds a {@code "; did you mean: X, Y, Z"} hint string for error messages, listing
     * {@code candidates} sorted by Levenshtein distance from {@code attempt}.
     */
    static String candidateHint(String attempt, List<String> candidates) {
        return candidateHint(attempt, candidates, "; did you mean: ");
    }

    /**
     * Builds a hint string with a custom {@code prefix} for error messages, listing
     * {@code candidates} sorted by Levenshtein distance from {@code attempt}.
     */
    static String candidateHint(String attempt, List<String> candidates, String prefix) {
        if (candidates.isEmpty()) return "";
        String lc = attempt.toLowerCase();
        return prefix + candidates.stream()
            .sorted(Comparator.comparingInt(c -> levenshteinDistance(lc, c.toLowerCase())))
            .limit(5)
            .collect(Collectors.joining(", "));
    }

    private static int levenshteinDistance(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1))
                    curr[j] = prev[j - 1];
                else
                    curr[j] = 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    // ===== Reference path parsing =====

    /**
     * Candidate FK names for a {@code @reference(key:)} lookup miss, scoped and namespaced so the
     * "did you mean" hint lands in the author's frame instead of as global noise.
     *
     * <p><b>Scope.</b> When {@code sourceSqlTable} is known (the path position has a table-backed
     * source) the candidates are the FKs touching that table, i.e. exactly the keys that are valid
     * at this position. For a join-table hop that is the two or three outgoing FKs, not the whole
     * catalog. When the source is {@code null} (non-table-backed forward traversal) it falls back
     * to every FK in the catalog.
     *
     * <p><b>Namespace.</b> {@link JooqCatalog#findForeignKey} resolves a key in either the SQL
     * constraint namespace ({@code opptak_samordna_organisasjon_organisasjon_fk}) or the jOOQ
     * Java-constant namespace ({@code opptak_samordna_organisasjon__..._organisasjon_fk}, the
     * {@code TABLE__CONSTRAINT} form). The hint mirrors whichever the author used, detected by the
     * {@code __} separator in their {@code attempt}, so a suggestion never reads as a different
     * namespace than the one they typed.
     */
    private List<String> fkCandidateNames(String sourceSqlTable, String attempt) {
        boolean constantNamespace = attempt.contains("__");
        var touching = catalog.foreignKeysTouchingTable(sourceSqlTable);
        if (touching.isEmpty()) {
            return constantNamespace ? catalog.allForeignKeyConstantNames() : catalog.allForeignKeySqlNames();
        }
        return touching.stream()
            .map(fk -> constantNamespace
                ? catalog.fkJavaConstantName(fk.getName()).orElse(fk.getName())
                : fk.getName())
            .distinct()
            .toList();
    }

    /**
     * Carries the result of {@link #parsePath}: either a fully resolved list of path elements or
     * an error message. When {@code errorMessage()} is non-null the {@code elements()} list is
     * empty and the containing field must be classified as an unclassified variant.
     *
     * <p>{@code terminalTargetVerdict} is the typed outcome of Check 1 (does the path's terminal
     * hop land on the field return type's {@code @table}?), exposed as a small sealed projection
     * (R379) so the LSP layer (R381) can render the verdict without re-parsing
     * {@link #errorMessage()}. It is {@link TerminalTargetVerdict.Mismatch} when the terminal hop
     * lands on the wrong table, {@link TerminalTargetVerdict.Match} when it lands correctly, and
     * {@link TerminalTargetVerdict.NotApplicable} when the check did not run (no table-backed
     * start, no return table, empty path, or an earlier error short-circuited parsing).
     *
     * <p>The verdict is independent of {@link #errorMessage()}: a {@code Mismatch} is carried on an
     * otherwise non-error {@code ParsedPath} (the resolved elements are present). The inline / split
     * output callers ({@code InlineTableFieldEmitter}'s {@code $fields(terminalAlias)} projection,
     * wired through {@code FieldBuilder}) convert {@code Mismatch} into an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} via
     * {@link TerminalTargetVerdict.Mismatch#diagnostic()}; callers with their own terminal-target
     * invariant (e.g. {@code @tableMethod}) keep their existing checks and ignore the verdict.
     */
    record ParsedPath(List<JoinStep> elements, String errorMessage,
            TerminalTargetVerdict terminalTargetVerdict) {
        boolean hasError() { return errorMessage != null; }
    }

    /**
     * Typed outcome of {@link #parsePath}'s terminal-hop landing-table check (R379 Check 1): does
     * the {@code @reference} path's terminal hop land on the field return type's {@code @table}?
     * The emitter ({@code InlineTableFieldEmitter.buildArm}) feeds the terminal hop's alias to a
     * {@code $fields} overload typed for the return table, so a terminal hop that lands elsewhere
     * compiles to generated Java that javac rejects with an incompatible-types error in a
     * downstream consumer's build. This verdict moves that failure to build time.
     *
     * <p>Exposed as a sealed projection rather than only an {@code errorMessage} string so R381's
     * LSP layer can surface the terminal-target diagnostic at edit time, consuming the verdict via
     * the catalog snapshot rather than re-deriving it (the R233 pattern). {@link Mismatch} carries
     * exactly the names {@link Mismatch#diagnostic()} prints, so the rendered string and the
     * structured projection cannot drift.
     */
    public sealed interface TerminalTargetVerdict {

        /** The terminal hop lands on the return table (or is a {@code ConditionJoin}, whose target
         *  is constructed from the return {@code @table} and so matches by construction). */
        record Match() implements TerminalTargetVerdict {}

        /**
         * The terminal hop resolves to {@code terminalTableName}, but the carrier field
         * {@code fieldName}'s return type is bound to {@code @table} {@code returnTableName}.
         */
        record Mismatch(String fieldName, String terminalTableName, String returnTableName)
                implements TerminalTargetVerdict {

            /** The author-facing diagnostic, formatted from this record's fields so the message
             *  and the structured projection share a single source of truth. */
            String diagnostic() {
                return "the @reference terminal hop on field '" + fieldName + "' resolves to table '"
                    + terminalTableName + "', but the field's return type is bound to @table '"
                    + returnTableName + "'; the path must end on '" + returnTableName
                    + "' (the terminal alias is fed to a $fields overload typed for the return table).";
            }
        }

        /** The check did not run: no return table at this site, an empty path, or an earlier
         *  parse error short-circuited the terminal-target assertion. */
        record NotApplicable() implements TerminalTargetVerdict {}
    }

    /**
     * Resolves a GraphQL type name to its expected jOOQ {@code TableRecord} class via the
     * {@code @table} directive on the type. Returns empty when the type isn't in the schema,
     * isn't {@code @table}-annotated, or the catalog can't resolve the table name.
     *
     * <p>Used by {@link ServiceDirectiveResolver}'s parent-table consistency check: a
     * {@code @service} child whose SOURCES element type is a typed {@code TableRecord}
     * subtype must match the parent's expected record class.
     */
    Optional<Class<?>> recordClassForTypeName(String typeName) {
        if (typeName == null) return Optional.empty();
        var raw = schema.getType(typeName);
        if (!(raw instanceof GraphQLObjectType obj) || !obj.hasAppliedDirective(DIR_TABLE)) {
            return Optional.empty();
        }
        String tableName = argString(obj, DIR_TABLE, ARG_NAME).orElse(typeName.toLowerCase());
        return catalog.findRecordClass(tableName);
    }

    /**
     * Resolves a SQL table name to a {@link TableRef} by looking it up in the jOOQ catalog.
     * Accepts both unqualified ({@code "film"}) and schema-qualified ({@code "public.film"})
     * directive values, routed through {@link JooqCatalog#findTable(String)}.
     *
     * <p>Returns empty when:
     * <ul>
     *   <li>the catalog is unavailable</li>
     *   <li>the (qualified or unique-unqualified) name does not match any table</li>
     *   <li>the name is unqualified and matches tables in two or more schemas</li>
     *   <li>the schema package has no generated {@code Tables} class (degenerate codegen)</li>
     * </ul>
     *
     * <p>Callers route empty to {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType}
     * or {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedField}; emitters never
     * see a partial ref.
     */
    Optional<TableRef> resolveTable(String sqlName) {
        return catalog.findTable(sqlName).asEntry().map(e -> e.toTableRef(sqlName));
    }

    /**
     * Builds the {@link Rejection} to attach to an {@code UnclassifiedType} /
     * {@code UnclassifiedField} when a table-name lookup did not produce a {@code Resolved}
     * variant. Switches over the {@link JooqCatalog.TableResolution} sub-taxonomy directly:
     *
     * <ul>
     *   <li>{@link JooqCatalog.TableResolution.Ambiguous} → structural rejection that names the
     *       colliding schemas and suggests qualified forms (the user typed something legitimate
     *       but we cannot pick a winner without more information).</li>
     *   <li>{@link JooqCatalog.TableResolution.NotInCatalog} → {@link Rejection#unknownTable}
     *       rejection with the Levenshtein-ranked candidate hint over every table in the catalog.
     *       Covers genuinely missing names and qualified misses (the qualified form never matches
     *       any schema's bare-name candidate set).</li>
     * </ul>
     *
     * <p>The string-arg overload looks the table up itself; the variant-arg overload is wired
     * directly from rejection sites that already hold a {@link JooqCatalog.TableResolution}
     * result so they avoid a redundant catalog query.
     */
    Rejection unknownTableRejection(String sqlName) {
        return unknownTableRejection(catalog.findTable(sqlName), sqlName);
    }

    Rejection unknownTableRejection(JooqCatalog.TableResolution failure, String sqlName) {
        return switch (failure) {
            case JooqCatalog.TableResolution.Resolved r -> throw new IllegalArgumentException(
                "unknownTableRejection called for resolved table '" + sqlName + "'");
            case JooqCatalog.TableResolution.NotInCatalog n -> Rejection.unknownTable(
                "table '" + sqlName + "' could not be resolved in the jOOQ catalog",
                sqlName, catalog.allTableSqlNames());
            case JooqCatalog.TableResolution.Ambiguous a -> {
                String qualifiedHints = a.schemas().stream()
                    .map(s -> "'" + s + "." + sqlName + "'")
                    .collect(Collectors.joining(", "));
                yield Rejection.structural(
                    "@table(name: '" + sqlName + "') is ambiguous: defined in schemas " + a.schemas()
                        + "; qualify as " + qualifiedHints);
            }
        };
    }

    /**
     * Builds the {@link Rejection} for an FK-name lookup that did not produce a
     * {@link JooqCatalog.ForeignKeyResolution.Resolved} variant. Sibling of
     * {@link #unknownTableRejection}; surfaces a Levenshtein-ranked candidate hint over the
     * catalog's FK names so a typo in {@code @reference(key: "...")} (or in the inferred FK name
     * picked up by the {@code IdReference} synthesis shim) reaches the schema author with a fix-it
     * suggestion rather than just a "not in catalog" string.
     *
     * <p><b>Namespace.</b> {@link JooqCatalog#findForeignKey} resolves a key in either the SQL
     * constraint namespace or the jOOQ Java-constant {@code TABLE__CONSTRAINT} namespace, so the
     * candidate hint mirrors whichever the author used (detected by the {@code __} separator in
     * {@code fkName}); a suggestion never reads as a different namespace than the one they typed.
     * This matches {@link #fkCandidateNames} on the path-element hint surface. The candidate set
     * here is still the whole catalog rather than scoped to the structurally relevant FKs; scoping
     * this sibling needs a source table threaded through its call sites and is tracked as a
     * follow-up (see {@code fk-key-hint-sibling-scope}).
     */
    Rejection unknownForeignKeyRejection(String fkName) {
        boolean constantNamespace = fkName.contains("__");
        return Rejection.unknownForeignKey(
            "foreign key '" + fkName + "' could not be resolved in the jOOQ catalog",
            fkName,
            constantNamespace ? catalog.allForeignKeyConstantNames() : catalog.allForeignKeySqlNames());
    }

    /**
     * Resolves a jOOQ {@code Table} + {@code Field} list to a list of {@link ColumnRef}s for FK
     * source/target column population. Returns an empty list when the catalog is unavailable.
     */
    private List<ColumnRef> resolveFkColumnRefs(org.jooq.Table<?> table, List<? extends org.jooq.Field<?>> fields) {
        return fields.stream()
            .map(f -> catalog.findColumn(table, f.getName()))
            .<JooqCatalog.ColumnEntry>flatMap(Optional::stream)
            .map(ce -> new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()))
            .toList();
    }

    /**
     * Parses the {@code @reference(path:)} directive on {@code container} into a {@link ParsedPath},
     * or — when the directive is absent or carries an empty {@code path:} list — infers a single-hop
     * {@link FkJoin} from the catalog when exactly one foreign key connects {@code startSqlTableName}
     * to {@code targetSqlTableName}.
     *
     * <p>Inference fires only when all three of: {@code startSqlTableName != null},
     * {@code targetSqlTableName != null}, and the two names differ (case-insensitive) — the same
     * constraints as {@link JooqCatalog#findForeignKeysBetweenTables}. Zero or multiple FKs produce
     * a {@code ParsedPath} with a non-null {@code errorMessage} asking the author to write an
     * explicit {@code @reference} directive.
     *
     * <p>Returns {@code ParsedPath(List.of(), null)} when both inference preconditions are
     * unsatisfied (typical for {@code ColumnReferenceField}, service reconnect, and same-table
     * {@code @externalField} / {@code @tableMethod} sites).
     *
     * <p>{@code fieldName} is the GraphQL field name and is used to compute per-step aliases
     * ({@code fieldName + "_" + stepIndex}). {@code startSqlTableName} is the SQL table name at
     * the start of the path (the parent type's table), or {@code null} when the source is not
     * table-backed — in which case FK direction is inferred as forward and connectivity is not
     * validated. {@code targetSqlTableName} is the SQL name of the return type's table when known,
     * or {@code null} to disable implicit-path inference at this site.
     */
    ParsedPath parsePath(GraphQLDirectiveContainer container, String fieldName,
            String startSqlTableName, String targetSqlTableName) {
        return parsePath(container, fieldName, startSqlTableName, targetSqlTableName, /*isList=*/false);
    }

    /**
     * Variant of {@link #parsePath} that accepts the field's list-cardinality, used to
     * disambiguate self-referential FK direction at synthesis time. For {@code category.parent}
     * the parent (source) holds the FK; for {@code category.children} the child (target) holds it,
     * even though both navigate the same FK constraint. Cardinality is the only reliable signal
     * here — the table-name comparison cannot resolve self-ref direction.
     */
    ParsedPath parsePath(GraphQLDirectiveContainer container, String fieldName,
            String startSqlTableName, String targetSqlTableName, boolean isList) {
        var directive = container.getAppliedDirective(DIR_REFERENCE);
        var pathArg = directive != null ? directive.getArgument(ARG_PATH) : null;
        List<?> elements;
        if (pathArg != null) {
            Object pathValue = pathArg.getValue();
            elements = pathValue instanceof List<?> l ? l : List.of(pathValue);
        } else {
            elements = List.of();
        }

        var resolvedElements = new ArrayList<JoinStep>();
        var errors = new ArrayList<String>();
        String currentSource = startSqlTableName;
        int stepIndex = 0;

        int totalElements = (int) elements.stream().filter(v -> v instanceof Map<?, ?>).count();
        for (var v : elements) {
            if (v instanceof Map<?, ?>) {
                boolean isTerminal = (stepIndex == totalElements - 1);
                parsePathElement(asMap(v), currentSource, fieldName, stepIndex,
                    resolvedElements, errors, isList, isTerminal, targetSqlTableName);
                stepIndex++;
                if (!resolvedElements.isEmpty()) {
                    var last = resolvedElements.getLast();
                    // After R232 ConditionJoin carries a resolved targetTable, so advance
                    // currentSource uniformly through HasTargetTable. LiftedHop is never
                    // produced by @reference path parsing (single-hop terminal only).
                    currentSource = last instanceof JoinStep.HasTargetTable ht
                        ? ht.targetTable().tableName() : null;
                }
            }
        }

        if (!errors.isEmpty()) {
            return new ParsedPath(List.of(), String.join("; ", errors), new TerminalTargetVerdict.NotApplicable());
        }
        if (resolvedElements.isEmpty()
                && startSqlTableName != null
                && targetSqlTableName != null
                && !startSqlTableName.equalsIgnoreCase(targetSqlTableName)) {
            var fks = catalog.findForeignKeysBetweenTables(startSqlTableName, targetSqlTableName);
            if (fks.size() == 1) {
                var stepResolution = synthesizeFkJoin(fks.get(0), startSqlTableName, fieldName, 0, null, /*selfRefFkOnSource=*/!isList);
                switch (stepResolution) {
                    case FkJoinResolution.Resolved r -> resolvedElements.add(r.fkJoin());
                    case FkJoinResolution.UnknownTable u -> {
                        return new ParsedPath(List.of(),
                            unknownTableRejection(u.failure(), u.requestedName()).message(),
                            new TerminalTargetVerdict.NotApplicable());
                    }
                    case FkJoinResolution.UnknownForeignKey uf -> {
                        return new ParsedPath(List.of(),
                            unknownForeignKeyRejection(uf.fkName()).message(),
                            new TerminalTargetVerdict.NotApplicable());
                    }
                }
            } else {
                return new ParsedPath(List.of(),
                    fkCountMessage(startSqlTableName, targetSqlTableName, fks, /*directiveAbsent=*/true),
                    new TerminalTargetVerdict.NotApplicable());
            }
        }
        // Check 1 (R379): compute the terminal-target verdict — does the terminal hop land on the
        // return type's @table? The terminal target is already resolved on the last JoinStep (the
        // loop advances currentSource through HasTargetTable.targetTable()); this reuses R232's
        // resolved value rather than re-deriving the hop kind from the directive element. Gated on a
        // non-empty path plus a non-null start AND return table (see computeTerminalTargetVerdict).
        //
        // The verdict is threaded onto ParsedPath as a typed projection rather than forced into
        // errorMessage here: parsePath is shared by callers that have their own terminal-target
        // invariant (e.g. the @tableMethod path's "last hop lands on …" check) and callers whose
        // emit shape does not feed the terminal alias to a $fields overload at all. Only the inline
        // / split output projection (InlineTableFieldEmitter) carries the $fields(terminalAlias)
        // invariant this check protects, so its two FieldBuilder callers (TableBoundReturnType,
        // TableInterfaceType) consume the verdict and reject on Mismatch. R381's LSP layer reads the
        // same projection. This keeps the predicate single-sourced and scoped to where it applies.
        var terminalVerdict = computeTerminalTargetVerdict(resolvedElements, fieldName, startSqlTableName, targetSqlTableName);
        return new ParsedPath(List.copyOf(resolvedElements), null, terminalVerdict);
    }

    /**
     * Computes the R379 Check 1 verdict: does the {@code @reference} path's terminal hop land on
     * the field return type's {@code @table}? Reads the already-resolved terminal
     * {@link JoinStep.HasTargetTable#targetTable()} rather than re-deriving the hop kind from the
     * directive element.
     *
     * <ul>
     *   <li>{@link JoinStep.FkJoin} — {@link TerminalTargetVerdict.Mismatch} when the resolved
     *       target table differs (case-insensitive) from {@code returnSqlTableName}, else
     *       {@link TerminalTargetVerdict.Match}. R232's resolved {@code targetTable} encodes
     *       "where this hop lands" for both terminal {@code {table:}} and terminal {@code {key:}}
     *       elements, so this single comparison subsumes both author forms.</li>
     *   <li>{@link JoinStep.ConditionJoin} — {@link TerminalTargetVerdict.Match} by construction:
     *       {@code resolveConditionJoinTarget}'s terminal branch builds the target from the return
     *       {@code @table}, so the comparison is tautological. The terminal {@code ConditionJoin}'s
     *       method parameters are validated by Check 2, not here.</li>
     *   <li>{@link JoinStep.LiftedHop} — unreachable; {@code @reference} path parsing never
     *       produces a {@code LiftedHop} (single-hop terminal only, from the {@code @sourceRow}
     *       leaf-PK arm, which passes a null start and so never reaches this gate).</li>
     * </ul>
     *
     * <p>Returns {@link TerminalTargetVerdict.NotApplicable} when {@code startSqlTableName} or
     * {@code returnSqlTableName} is null, or the path is empty: the check fires only for the
     * inline/split output projection off a table-backed parent. The {@code @sourceRow} composition
     * (null start) and input-field sites (null return table) are excluded — their paths are not the
     * {@code $fields(terminalAlias)} projection this invariant protects.
     */
    private TerminalTargetVerdict computeTerminalTargetVerdict(
            List<JoinStep> resolvedElements, String fieldName,
            String startSqlTableName, String returnSqlTableName) {
        if (startSqlTableName == null || returnSqlTableName == null || resolvedElements.isEmpty()) {
            return new TerminalTargetVerdict.NotApplicable();
        }
        JoinStep terminal = resolvedElements.getLast();
        return switch (terminal) {
            case JoinStep.FkJoin fk -> fk.targetTable().sameTable(returnSqlTableName)
                ? new TerminalTargetVerdict.Match()
                : new TerminalTargetVerdict.Mismatch(fieldName, fk.targetTable().tableName(), returnSqlTableName);
            case JoinStep.ConditionJoin ignored -> new TerminalTargetVerdict.Match();
            case JoinStep.LiftedHop ignored -> throw new IllegalStateException(
                "JoinStep.LiftedHop is never produced by @reference path parsing (single-hop "
                + "terminal only, from the @sourceRow leaf-PK arm, which passes a null target and "
                + "so never reaches this gate); reaching it here is a contract violation.");
        };
    }

    /**
     * Builds an {@link FkJoin} step for a foreign key that connects {@code sourceSqlName} to some
     * other table, or surfaces the catalog-failure shape when one of the resolution inputs
     * (endpoint table, FK constraint name) is missing. Traversal direction is inferred from which
     * side of the FK the source name touches (case-insensitive). The step alias follows the
     * explicit-path convention, {@code fieldName + "_" + stepIndex}, so inferred and explicit
     * position-0 steps produce record-equivalent {@code FkJoin} values for the same shape.
     *
     * <p>{@code sourceSqlName} must be non-null; callers gate inference on that precondition.
     * {@code whereFilter} is {@code null} for pure inference; the {@code {table:}} and
     * {@code {key:}} branches in {@link #parsePathElement} may pass a resolved {@link MethodRef}
     * when the element carries a {@code condition:} sub-argument.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@link FkJoinResolution.Resolved} when both endpoint tables and the FK constraint name
     *       resolve through the catalog;</li>
     *   <li>{@link FkJoinResolution.UnknownTable} carrying the failing
     *       {@link JooqCatalog.TableResolution} (always {@code NotInCatalog} or {@code Ambiguous})
     *       when an endpoint cannot be resolved;</li>
     *   <li>{@link FkJoinResolution.UnknownForeignKey} when the FK constraint name itself is
     *       absent from every schema's {@code Keys} class.</li>
     * </ul>
     * Callers switch over this result and route each failure shape through the matching diagnostic
     * builder ({@link #unknownTableRejection} / {@link #unknownForeignKeyRejection}).
     *
     * @param selfRefFkOnSource for self-referential FKs (where {@code f.getTable()} equals
     *     {@code f.getKey().getTable()}), the table-name comparison is ambiguous. The caller
     *     supplies this hint: {@code true} when the parent holds the FK (single-cardinality
     *     traversal, e.g. {@code category.parent}), {@code false} when the child holds the FK
     *     (list-cardinality traversal, e.g. {@code category.children}). Ignored for non-self-ref
     *     FKs, where the table-name comparison resolves direction.
     */
    FkJoinResolution synthesizeFkJoin(ForeignKey<?, ?> f, String sourceSqlName, String fieldName,
            int stepIndex, MethodRef whereFilter, boolean selfRefFkOnSource) {
        String fkSideTable  = f.getTable().getName();
        String keySideTable = f.getKey().getTable().getName();
        boolean fkOnSource = catalog.foreignKeyOnSource(f, sourceSqlName, selfRefFkOnSource);
        String targetSqlName = fkOnSource ? keySideTable : fkSideTable;

        var fkResolution = catalog.findForeignKeyByName(f.getName());
        if (!(fkResolution instanceof JooqCatalog.ForeignKeyResolution.Resolved fkResolved)) {
            return new FkJoinResolution.UnknownForeignKey(f.getName());
        }

        var targetResolution = catalog.findTable(targetSqlName);
        if (!(targetResolution instanceof JooqCatalog.TableResolution.Resolved targetResolved)) {
            return new FkJoinResolution.UnknownTable(targetSqlName, targetResolution);
        }
        var originResolution = catalog.findTable(sourceSqlName);
        if (!(originResolution instanceof JooqCatalog.TableResolution.Resolved originResolved)) {
            return new FkJoinResolution.UnknownTable(sourceSqlName, originResolution);
        }

        TableRef targetTable = targetResolved.entry().toTableRef(targetSqlName);
        TableRef originTable = originResolved.entry().toTableRef(sourceSqlName);
        List<JoinSlot.FkSlot> slots = resolveFkSlots(f, fkOnSource);
        String alias = fieldName + "_" + stepIndex;
        return new FkJoinResolution.Resolved(new FkJoin(fkResolved.ref(), originTable,
            targetTable, slots, whereFilter, alias));
    }

    /**
     * The FK-orientation-and-pairing core, shared by {@link #synthesizeFkJoin} (the join path) and the
     * record-population FK resolver ({@link #resolveRecordFkTargetColumns}, R315). Given a catalog
     * {@link ForeignKey} and the SQL name of the side treated as the join/population <em>source</em>,
     * returns the FK's column pairs as {@link JoinSlot.FkSlot}s oriented so each slot's
     * {@link JoinSlot#sourceSide()} is the column on the source table and {@link JoinSlot#targetSide()}
     * is the column on the other table. The FK-direction question (which end of the FK sits on the
     * source) is answered once, here, and baked into the slot pair so every consumer reads
     * direction-blind.
     *
     * <p>Parent (referenced) columns come from {@link ForeignKey#getKeyFields()} — the FK's <em>own</em>
     * referenced-column list (third {@code TableField[]} arg passed to {@code Internal.createForeignKey}
     * at codegen time), which jOOQ keeps parallel to {@link ForeignKey#getFields()}: position {@code i}
     * of the referencing list pairs with position {@code i} of the referenced list.
     * {@code getKey().getFields()} returns the referenced {@code UniqueKey}'s <em>own</em> declaration
     * order, which for an FK whose referenced-column ordering differs from the parent PK's declaration
     * order (e.g. {@code PRIMARY KEY (a, b, c)} referenced as {@code REFERENCES parent (b, c, a)}) does
     * NOT pair positionally with {@code getFields()}. Zipping the two non-parallel lists produced silent
     * mis-paired slots — observable as {@code Field<X>.eq(Field<Y>)} compile errors in generated JOIN ON
     * predicates when the FK column types are heterogeneous, and (R315) as values decoded into the wrong
     * record columns. See {@code SynthesizeFkJoinReorderedKeysTest}.
     *
     * @param fkOnSource the FK orientation, decided once by the caller via
     *     {@link JooqCatalog#foreignKeyOnSource}: {@code true} when the source table is the FK-child
     *     (referencing) side, {@code false} when it is the referenced-key side. Collapsing the
     *     orientation decision into a single precomputed boolean keeps the FK source-side predicate
     *     in exactly one place ({@code JooqCatalog.foreignKeyOnSource}); this method no longer
     *     recomputes it from raw endpoint names, so a schema-qualified source no longer mis-orients
     *     the slot pairing.
     */
    List<JoinSlot.FkSlot> resolveFkSlots(ForeignKey<?, ?> f, boolean fkOnSource) {
        List<ColumnRef> fkSideCols  = resolveFkColumnRefs(f.getTable(), f.getFields());
        List<ColumnRef> keySideCols = resolveFkColumnRefs(f.getKey().getTable(), f.getKeyFields());
        List<JoinSlot.FkSlot> slots = new java.util.ArrayList<>(fkSideCols.size());
        for (int i = 0; i < fkSideCols.size(); i++) {
            ColumnRef fkCol  = fkSideCols.get(i);
            ColumnRef keyCol = keySideCols.get(i);
            slots.add(fkOnSource
                ? new JoinSlot.FkSlot(fkCol, keyCol)
                : new JoinSlot.FkSlot(keyCol, fkCol));
        }
        return slots;
    }

    /**
     * Sub-taxonomy of outcomes for {@link #synthesizeFkJoin}. Lifts the prior
     * {@code Optional<FkJoin>} return into a typed switch over the three catalog-failure shapes
     * an FK-join construction can hit. Diagnostic builders read the carried failure data
     * directly: {@link UnknownTable#failure} routes through {@link #unknownTableRejection};
     * {@link UnknownForeignKey#fkName} routes through {@link #unknownForeignKeyRejection}.
     */
    public sealed interface FkJoinResolution {
        /** Both endpoint tables and the FK name resolved; the {@link FkJoin} is ready. */
        record Resolved(FkJoin fkJoin) implements FkJoinResolution {}

        /**
         * One of the FK's endpoint tables did not resolve. {@code requestedName} is the SQL
         * name of the failing endpoint (the one that produced a non-{@code Resolved} variant);
         * {@code failure} carries the {@link JooqCatalog.TableResolution} so the diagnostic
         * builder can pick {@code unknownTable} vs. {@code structural} ambiguity prose.
         */
        record UnknownTable(String requestedName, JooqCatalog.TableResolution failure)
                implements FkJoinResolution {}

        /**
         * The FK constraint name itself is absent from every schema's {@code Keys} class.
         * {@code fkName} is the SQL constraint name the caller asked for.
         */
        record UnknownForeignKey(String fkName) implements FkJoinResolution {}

        /**
         * Project to {@link Optional}{@code <FkJoin>} for callers that ignore the failure
         * sub-taxonomy. Diagnostic-bearing callers must switch on the variant instead.
         */
        default Optional<FkJoin> asFkJoin() {
            return this instanceof Resolved r ? Optional.of(r.fkJoin()) : Optional.empty();
        }
    }

    /**
     * Renders the zero-FK / multi-FK error message shared by the two inference call sites:
     * empty-elements inference in {@link #parsePath} ({@code directiveAbsent = true}) and the
     * {@code {table:}} branch of {@link #parsePathElement} ({@code directiveAbsent = false}).
     *
     * <p>When {@code directiveAbsent} is true, both arms append "; add a @reference directive to
     * specify the join path" — that's the actionable fix when the user omitted the directive
     * entirely. When false, the zero-FK arm just states the fact (user already wrote
     * {@code {table: "..."}}, so telling them to add a directive is noise) and the multi-FK arm
     * instead enumerates the candidate FK names under "— use 'key' to specify which: …".
     */
    private String fkCountMessage(String source, String target, List<ForeignKey<?, ?>> fks, boolean directiveAbsent) {
        if (fks.isEmpty()) {
            String msg = "no foreign key found between tables '" + source + "' and '" + target + "'";
            if (directiveAbsent) {
                msg += "; add a @reference directive to specify the join path."
                    + " The catalog has no FK directly connecting these two tables, so a single-hop"
                    + " '@reference(path: [{key: \"<fk-name>\"}])' will not resolve. Either chain"
                    + " through an intermediate table that has FKs to both"
                    + " ('@reference(path: [{key: \"<fk-to-intermediate>\"}, {key: \"<fk-from-intermediate>\"}])'),"
                    + " or join on a non-FK predicate via '@reference(path: [{condition: {...}}])'";
            }
            return msg;
        }
        String msg = "multiple foreign keys found between tables '" + source + "' and '" + target + "'";
        String fkNames = fks.stream().map(ForeignKey::getName).collect(Collectors.joining(", "));
        if (directiveAbsent) {
            msg += "; add a @reference directive to specify which one"
                + " — candidates: " + fkNames
                + " (e.g. '@reference(path: [{key: \"" + fks.get(0).getName() + "\"}])')";
        } else {
            msg += " — use 'key' to specify which: " + fkNames;
        }
        return msg;
    }

    /**
     * Resolves one {@code @reference} path element into a {@link JoinStep} and appends it to
     * {@code out}. Errors are accumulated in {@code errors}.
     *
     * <p>{@code currentSourceSqlName} is the SQL table name at the current position in the chain,
     * or {@code null} when the source is not a table-backed type. When non-null, FK connectivity is
     * validated (the FK must touch the current source table) and traversal direction is determined
     * precisely. When null, forward traversal (FK-side → key-side) is assumed without validation.
     *
     * <p>{@code fieldName} and {@code stepIndex} are used to compute the step's alias as
     * {@code fieldName + "_" + stepIndex}.
     */
    private void parsePathElement(Map<String, Object> element, String currentSourceSqlName,
            String fieldName, int stepIndex, List<JoinStep> out, List<String> errors, boolean isList,
            boolean isTerminal, String terminalTargetSqlName) {
        Object keyRaw = element.get(ARG_KEY);
        Object tableRaw = element.get(ARG_TABLE_REF);
        Object conditionRaw = element.get(ARG_CONDITION);

        Optional<String> keyName = Optional.ofNullable(keyRaw)
            .map(Object::toString)
            .filter(s -> !s.isBlank());
        Optional<String> tableName = Optional.ofNullable(tableRaw)
            .map(Object::toString)
            .filter(s -> !s.isBlank());
        boolean hasCondition = conditionRaw instanceof Map;

        String alias = fieldName + "_" + stepIndex;

        if (keyName.isPresent()) {
            Optional<ForeignKey<?, ?>> fk = catalog.findForeignKey(keyName.get());
            if (fk.isEmpty()) {
                errors.add("key '" + keyName.get() + "' could not be resolved in the jOOQ catalog"
                    + candidateHint(keyName.get(), fkCandidateNames(currentSourceSqlName, keyName.get())));
                return;
            }
            var f = fk.get();
            String fkSideTable  = f.getTable().getName();
            String keySideTable = f.getKey().getTable().getName();
            if (currentSourceSqlName != null
                && !catalog.foreignKeyTouchesTable(f, currentSourceSqlName)) {
                errors.add("key '" + f.getName() + "' does not connect to table '" + currentSourceSqlName + "'"
                    + candidateHint(currentSourceSqlName, List.of(fkSideTable, keySideTable)));
                return;
            }
            String effectiveSourceSqlName = currentSourceSqlName != null ? currentSourceSqlName : fkSideTable;
            MethodRef whereFilter = null;
            if (hasCondition) {
                var res = resolveConditionRef(asMap(conditionRaw));
                if (res.error() != null) {
                    errors.add(res.error());
                    return;
                }
                whereFilter = res.ref();
            }
            var keyResolution = synthesizeFkJoin(f, effectiveSourceSqlName, fieldName, stepIndex, whereFilter, /*selfRefFkOnSource=*/!isList);
            switch (keyResolution) {
                case FkJoinResolution.Resolved r -> {
                    out.add(r.fkJoin());
                    validateWhereFilterParamTables(r.fkJoin(), errors);
                }
                case FkJoinResolution.UnknownTable u ->
                    errors.add(unknownTableRejection(u.failure(), u.requestedName()).message());
                case FkJoinResolution.UnknownForeignKey uf ->
                    errors.add(unknownForeignKeyRejection(uf.fkName()).message());
            }
            return;
        }
        if (tableName.isPresent()) {
            if (currentSourceSqlName == null) {
                errors.add("path element with 'table' requires a known source table — use 'key' instead to name the FK explicitly");
                return;
            }
            var fks = catalog.findForeignKeysBetweenTables(currentSourceSqlName, tableName.get());
            if (fks.size() != 1) {
                errors.add(fkCountMessage(currentSourceSqlName, tableName.get(), fks, /*directiveAbsent=*/false));
                return;
            }
            MethodRef whereFilter = null;
            if (hasCondition) {
                var res = resolveConditionRef(asMap(conditionRaw));
                if (res.error() != null) {
                    errors.add(res.error());
                    return;
                }
                whereFilter = res.ref();
            }
            var tableStepResolution = synthesizeFkJoin(fks.get(0), currentSourceSqlName, fieldName, stepIndex, whereFilter, /*selfRefFkOnSource=*/!isList);
            switch (tableStepResolution) {
                case FkJoinResolution.Resolved r -> {
                    out.add(r.fkJoin());
                    validateWhereFilterParamTables(r.fkJoin(), errors);
                }
                case FkJoinResolution.UnknownTable u ->
                    errors.add(unknownTableRejection(u.failure(), u.requestedName()).message());
                case FkJoinResolution.UnknownForeignKey uf ->
                    errors.add(unknownForeignKeyRejection(uf.fkName()).message());
            }
            return;
        }
        if (hasCondition) {
            Map<String, Object> condMap = asMap(conditionRaw);
            var res = resolveConditionRef(condMap);
            if (res.error() != null) {
                errors.add(res.error());
                return;
            }
            if (res.ref() == null) {
                errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
                return;
            }
            var targetResolution = resolveConditionJoinTarget(res.ref(), isTerminal, terminalTargetSqlName);
            switch (targetResolution) {
                case ConditionJoinTargetResolution.Resolved r -> {
                    out.add(new ConditionJoin(res.ref(), r.target(), alias));
                    // Check 2 (R379): the ON-clause method is called method(sourceAlias, targetAlias).
                    // Source is the table entering this hop; target is the resolved ConditionJoin
                    // target (return table for a terminal hop, second-parameter-resolved otherwise).
                    validateConditionParamTables(res.ref(), currentSourceSqlName, r.target().tableName(), errors);
                }
                case ConditionJoinTargetResolution.AuthorError e -> errors.add(e.message());
            }
            return;
        }
        errors.add("path element has neither 'key', 'table', nor 'condition'");
    }

    /**
     * Result of {@link #resolveConditionJoinTarget}: either a fully-resolved {@link TableRef} or
     * an actionable {@code AUTHOR_ERROR} message. {@link Resolved} feeds the
     * {@link ConditionJoin#ConditionJoin(MethodRef, TableRef, String) ConditionJoin compact
     * constructor}'s non-null contract directly; {@link AuthorError} routes through the
     * {@code errors} accumulator in {@link #parsePathElement} and surfaces as a
     * {@link Rejection.AuthorError.Structural} upstream.
     */
    private sealed interface ConditionJoinTargetResolution {
        record Resolved(TableRef target) implements ConditionJoinTargetResolution {}
        record AuthorError(String message) implements ConditionJoinTargetResolution {}
    }

    /**
     * Result of {@link #buildParentCorrelation}: either a fully-synthesised
     * {@link no.sikt.graphitron.rewrite.model.ParentCorrelation} or an actionable
     * {@code AUTHOR_ERROR} message. {@link Resolved} feeds the carrier field's record header
     * directly; {@link AuthorError} routes through the {@link FieldBuilder} call site and
     * surfaces as an {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}
     * with a {@link Rejection.AuthorError.Structural} rejection.
     */
    sealed interface ParentCorrelationResolution {
        record Resolved(no.sikt.graphitron.rewrite.model.ParentCorrelation correlation)
                implements ParentCorrelationResolution {}
        record AuthorError(String message) implements ParentCorrelationResolution {}
    }

    /**
     * Synthesises a {@link no.sikt.graphitron.rewrite.model.ParentCorrelation} for a carrier
     * field given its already-built joinPath. {@code OnFkSlots} when the first hop carries
     * pairable slots (FkJoin or LiftedHop); {@code OnConditionJoin} when the first hop is a
     * condition method. The two-axis decoupling is intentional: any intermediate hop may be
     * a condition join regardless of which arm fires here.
     *
     * <p>Returns an empty {@link ParentCorrelationResolution.Resolved} (wrapping {@code null})
     * for an empty joinPath — the standalone-lookup shape, where the carrier needs no parent
     * correlation. The carrier-side invariant pinned by
     * {@link no.sikt.graphitron.rewrite.model.ParentCorrelation#checkCarrierInvariant} reads
     * this case as "empty joinPath → null correlation".
     *
     * <p>The condition-join arm requires a non-null {@code parentTable}; pass it when the
     * carrier sits on a table-backed parent. For class-backed parents the
     * classifier should never produce a condition-join first hop (no parent table to anchor
     * the ON clause to); pass {@code null} and the helper routes the case to
     * {@link ParentCorrelationResolution.AuthorError}.
     *
     * <p>{@code parentPkCols} is populated for split-rows carriers (where {@code parentInput}
     * is materialised) and empty for inline carriers.
     */
    ParentCorrelationResolution buildParentCorrelation(
            List<JoinStep> joinPath, TableRef parentTable, List<ColumnRef> parentPkCols) {
        if (joinPath.isEmpty()) {
            return new ParentCorrelationResolution.Resolved(null);
        }
        JoinStep first = joinPath.get(0);
        if (first instanceof JoinStep.WithTarget wt) {
            return new ParentCorrelationResolution.Resolved(
                new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnFkSlots(wt));
        }
        if (first instanceof JoinStep.ConditionJoin cj) {
            if (parentTable == null) {
                return new ParentCorrelationResolution.AuthorError(
                    "condition-only first hop on `@reference` path with no parent `@table` "
                    + "binding: the parser cannot anchor the condition method's first argument. "
                    + "Add `@table(name: …)` to the carrier field's parent type, or rewrite the "
                    + "path to use `{table:}` or `{key:}` for the first hop.");
            }
            return new ParentCorrelationResolution.Resolved(
                new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnConditionJoin(
                    cj, parentTable, parentPkCols));
        }
        throw new IllegalStateException(
            "JoinStep permit list exhausted unexpectedly at buildParentCorrelation: "
            + first.getClass().getName());
    }

    /**
     * Resolves the target table for a {@code {condition:}}-only path element. The terminal-hop
     * arm reads the carrier field's return-type {@code @table} binding (passed in via
     * {@code terminalTargetSqlName}); the intermediate-hop arm reflects on the condition method's
     * second parameter type via {@link JooqCatalog#findTableByClass}. Both unresolvable cases
     * surface as {@link ConditionJoinTargetResolution.AuthorError}; the {@link ConditionJoin}
     * compact constructor (in {@link JoinStep}) is the structural safety net for
     * pre-resolution.
     *
     * <p>Wildcard parameter types ({@code Table<?>}) are supported on the terminal-hop arm
     * (resolution does not consult the method signature there); the intermediate-hop arm
     * requires a concrete generated jOOQ table class.
     */
    private ConditionJoinTargetResolution resolveConditionJoinTarget(
            MethodRef methodRef, boolean isTerminal, String terminalTargetSqlName) {
        if (isTerminal) {
            if (terminalTargetSqlName != null) {
                var entry = catalog.findTable(terminalTargetSqlName).asEntry();
                if (entry.isPresent()) {
                    return new ConditionJoinTargetResolution.Resolved(
                        entry.get().toTableRef(terminalTargetSqlName));
                }
            }
            return new ConditionJoinTargetResolution.AuthorError(
                "condition-only `@reference` path: cannot resolve target table because the "
                + "carrier field's return type has no `@table` binding. Add `@table(name: …)` "
                + "to the return type, or rewrite the path to include `{table:}` or `{key:}`.");
        }
        var params = methodRef.params();
        if (params.size() < 2) {
            return new ConditionJoinTargetResolution.AuthorError(
                "intermediate-hop `@condition` method '" + methodRef.className() + "."
                + methodRef.methodName() + "' has fewer than two parameters; the parser cannot "
                + "infer a target table for this hop. Change the method signature to "
                + "(srcTable, tgtTable) with concrete jOOQ table types, or rewrite the path to "
                + "use `{table:}` or `{key:}` for this hop.");
        }
        var p1 = params.get(1);
        String typeName = p1.typeName();
        // Wildcard `Table<?>` (the literal type-name jOOQ reflection yields) can't resolve a
        // specific target table; reject with the wildcard-specific message.
        if (typeName.contains("<?>") || typeName.equals("org.jooq.Table")) {
            return new ConditionJoinTargetResolution.AuthorError(
                "intermediate-hop `@condition` method '" + methodRef.className() + "."
                + methodRef.methodName() + "' has wildcard target parameter `Table<?>`; the "
                + "parser cannot infer the target table for this hop. Change the second "
                + "parameter to the concrete jOOQ table type, or rewrite the path to use "
                + "`{table:}` or `{key:}` for this hop.");
        }
        String rawTypeName = typeName.contains("<") ? typeName.substring(0, typeName.indexOf('<')) : typeName;
        try {
            Class<?> cls = Class.forName(rawTypeName, false, codegenLoader());
            var entry = catalog.findTableByClass(cls);
            if (entry.isPresent()) {
                return new ConditionJoinTargetResolution.Resolved(
                    entry.get().toTableRef(entry.get().table().getName()));
            }
        } catch (ClassNotFoundException ignored) {
            // Falls through to the AuthorError below — the class isn't on the codegen
            // classloader, so the parameter type cannot map to a catalog entry.
        }
        return new ConditionJoinTargetResolution.AuthorError(
            "intermediate-hop `@condition` method '" + methodRef.className() + "."
            + methodRef.methodName() + "' second parameter type '" + typeName + "' does not "
            + "resolve to a generated jOOQ table class. Change the second parameter to a "
            + "concrete jOOQ table type, or rewrite the path to use `{table:}` or `{key:}` "
            + "for this hop.");
    }

    /**
     * Check 2 (R379) for an {@link FkJoin#whereFilter()}: the filter method is emitted as
     * {@code filter(sourceAlias, targetAlias)} by {@code JoinPathEmitter.emitTwoArgMethodCall},
     * with source = the FK hop's {@code originTable} and target = its {@code targetTable}, both
     * already resolved by {@code synthesizeFkJoin}. A no-op when the hop carries no filter.
     */
    private void validateWhereFilterParamTables(FkJoin fkJoin, List<String> errors) {
        if (fkJoin.whereFilter() == null) return;
        validateConditionParamTables(fkJoin.whereFilter(),
            fkJoin.originTable().tableName(), fkJoin.targetTable().tableName(), errors);
    }

    /**
     * Check 2 (R379): a two-argument condition method the path emits is called positionally as
     * {@code method(sourceAlias, targetAlias)}. When the author <em>concretely</em> types a table
     * parameter (e.g. {@code aCondition(NotB src, C tgt)}), it must agree with the table the
     * emitter will hand it, or the generated source fails javac with an incompatible-types error
     * in a downstream consumer's build. This moves that failure to build time.
     *
     * <p>Parameter 0 is checked against {@code sourceSqlName}, parameter 1 against
     * {@code targetSqlName}. Wildcard {@code Table<?>} parameters (the idiomatic
     * {@code (Table<?>, Table<?>)} shape) are unverifiable and accepted — the same wildcard
     * predicate {@link #resolveConditionJoinTarget} uses. A concrete parameter type that does not
     * resolve to a catalog table (a non-{@code Table} parameter, or a class absent from the
     * codegen loader) is likewise skipped: this check validates a constraint the author opted into
     * by naming a concrete jOOQ table, it imposes no new signature requirement.
     *
     * <p>Reads the reflected {@link MethodRef} parameter types and the resolved source/target
     * tables already in scope at the call site; it never re-inspects the directive element nor
     * re-walks the path.
     */
    private void validateConditionParamTables(
            MethodRef method, String sourceSqlName, String targetSqlName, List<String> errors) {
        var params = method.params();
        checkConcreteParamTable(method, params, 0, sourceSqlName, "source", errors);
        checkConcreteParamTable(method, params, 1, targetSqlName, "target", errors);
    }

    /**
     * Validates one positional table parameter of a condition method against the table the emitter
     * will pass it. Skips when the position is out of range, the expected table is unknown, the
     * declared parameter is a wildcard {@code Table<?>}, or the concrete type does not resolve to a
     * catalog table. Reuses {@link #resolveConditionJoinTarget}'s {@code Class.forName} +
     * {@link JooqCatalog#findTableByClass} machinery and the wildcard predicate.
     */
    private void checkConcreteParamTable(MethodRef method, List<MethodRef.Param> params,
            int index, String expectedSqlName, String role, List<String> errors) {
        if (expectedSqlName == null || index >= params.size()) return;
        String typeName = params.get(index).typeName();
        // Wildcard `Table<?>` (the literal type-name jOOQ reflection yields) accepts any aliased
        // table; nothing is assertable. Same predicate as resolveConditionJoinTarget.
        if (typeName.contains("<?>") || typeName.equals("org.jooq.Table")) return;
        String rawTypeName = typeName.contains("<") ? typeName.substring(0, typeName.indexOf('<')) : typeName;
        Class<?> cls;
        try {
            cls = Class.forName(rawTypeName, false, codegenLoader());
        } catch (ClassNotFoundException notOnLoader) {
            // Not a class on the codegen loader, so it cannot map to a catalog table; not assertable.
            return;
        }
        var entry = catalog.findTableByClass(cls);
        if (entry.isEmpty()) return; // a concrete non-table parameter; nothing to assert.
        String declaredTable = entry.get().table().getName();
        if (!declaredTable.equalsIgnoreCase(expectedSqlName)) {
            errors.add("condition method '" + method.className() + "." + method.methodName()
                + "' parameter " + index + " is typed for table '" + declaredTable
                + "' but this hop's " + role + " table is '" + expectedSqlName
                + "'; the emitter passes the " + role + " alias positionally, so the concrete "
                + "parameter type must match (or use a wildcard `Table<?>`).");
        }
    }

    /**
     * Result of {@link #resolveConditionRef}: exactly one of {@code ref} and {@code error} is
     * non-null. {@code error} carries an actionable message (parser failure, unknown argMapping
     * source, reflection failure); the path-step caller surfaces it directly. The "no class /
     * no method" case maps to a generic resolution error.
     */
    private record ConditionResolution(MethodRef ref, String error) {}

    /**
     * Resolves an {@code ExternalCodeReference} condition map to a {@link MethodRef} via
     * {@link ServiceCatalog#reflectTableMethod}. Returns a {@link ConditionResolution} with
     * either {@code ref} or {@code error} populated.
     *
     * <p>The deprecated {@code name:} form is resolved via {@link RewriteContext#namedReferences()},
     * exactly as in {@link FieldBuilder#parseExternalRef}.
     *
     * <p>For path-step {@code @condition}, no GraphQL arguments are in scope, so the slot set
     * is empty and any non-empty {@code argMapping} fails through {@link
     * ArgBindingMap.Result.UnknownArgRef}. Parse-time errors from {@code argMapping} itself also
     * surface, with the path-step site context wrapped around the message.
     */
    private ConditionResolution resolveConditionRef(Map<String, Object> conditionMap) {
        String className = Optional.ofNullable(conditionMap.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(conditionMap.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null) {
            String name = Optional.ofNullable(conditionMap.get(ARG_NAME)).map(Object::toString).orElse(null);
            if (name != null) {
                className = ctx.namedReferences().get(name);
            }
        }
        if (className == null || methodName == null || svc == null) {
            return new ConditionResolution(null, null);
        }
        String rawArgMapping = Optional.ofNullable(conditionMap.get(ARG_ARG_MAPPING)).map(Object::toString).orElse(null);
        var parsed = ArgBindingMap.parseArgMapping(rawArgMapping);
        if (parsed instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new ConditionResolution(null,
                "path-step @condition: " + pe.message());
        }
        var segmentChains = ((ArgBindingMap.ParsedArgMapping.Ok) parsed).overrides();
        var bindingResult = ArgBindingMap.of(java.util.Map.<String, graphql.schema.GraphQLInputType>of(), segmentChains);
        if (bindingResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            return new ConditionResolution(null,
                "path-step @condition: no GraphQL arguments are in scope at a path-step @condition; "
                + u.message());
        }
        if (bindingResult instanceof ArgBindingMap.Result.PathRejected p) {
            return new ConditionResolution(null,
                "path-step @condition: " + p.message());
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var result = svc.reflectTableMethod(className, methodName, argBindings, Set.of(), null,
            ServiceCatalog.TableSlotPolicy.REQUIRED);
        return result.failed() ? new ConditionResolution(null, null) : new ConditionResolution(result.ref(), null);
    }

    private String extractConditionQualifiedName(Map<String, Object> conditionMap) {
        Object name = conditionMap.get(ARG_NAME);
        if (name != null) return name.toString();
        String cls    = Optional.ofNullable(conditionMap.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String method = Optional.ofNullable(conditionMap.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (cls != null && method != null) return "method '" + method + "' in class '" + cls + "'";
        if (cls != null) return "class '" + cls + "'";
        return "unknown";
    }

    // ===== @condition directive parsing (shared with TypeBuilder / FieldBuilder) =====

    /**
     * Parsed representation of a {@code @condition} directive applied to any
     * {@link GraphQLDirectiveContainer} (field, argument, or input-object field).
     *
     * <p>{@code argMapping} stores parsed segment chains keyed by Java target; single-segment
     * chains cover the single-name mapping case, multi-segment chains carry dot-path expressions
     * into nested input fields. The call site supplies the slot-type oracle when calling
     * {@link ArgBindingMap#of}.
     */
    record ConditionDirective(
        String className,
        String methodName,
        boolean override,
        List<String> contextArguments,
        Map<String, List<String>> argMapping,
        String argMappingError
    ) {}

    /**
     * Reads a {@code @condition} directive from a field, argument, or input-object-field
     * container. Returns {@code null} when the directive is absent or could not be parsed
     * (e.g. missing {@code className}/{@code method}).
     *
     * <p>Moved from {@code FieldBuilder} (was private) so both {@link FieldBuilder} and
     * {@link TypeBuilder} can reach it via {@code ctx}.
     */
    ConditionDirective readConditionDirective(GraphQLDirectiveContainer container) {
        var dir = container.getAppliedDirective(DIR_CONDITION);
        if (dir == null) return null;
        var condArg = dir.getArgument(ARG_CONDITION);
        if (condArg == null || condArg.getValue() == null) return null;
        Map<String, Object> ref = asMap(condArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null) {
            String refName = Optional.ofNullable(ref.get(ARG_NAME)).map(Object::toString).orElse(null);
            if (refName != null) className = ctx.namedReferences().get(refName);
        }
        if (className == null || methodName == null) return null;
        boolean override = argBoolean(container, DIR_CONDITION, ARG_OVERRIDE, false);
        List<String> ctxArgs = argStringList(container, DIR_CONDITION, ARG_CONTEXT_ARGUMENTS);
        String rawArgMapping = Optional.ofNullable(ref.get(ARG_ARG_MAPPING)).map(Object::toString).orElse(null);
        var parsed = ArgBindingMap.parseArgMapping(rawArgMapping);
        if (parsed instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new ConditionDirective(className, methodName, override, ctxArgs, Map.of(), pe.message());
        }
        var segmentChains = ((ArgBindingMap.ParsedArgMapping.Ok) parsed).overrides();
        return new ConditionDirective(className, methodName, override, ctxArgs, segmentChains, null);
    }

    /**
     * Builds an {@link ArgConditionRef} from a {@code @condition} directive on one
     * {@link GraphQLInputObjectField}. Reflects the condition method via
     * {@link ServiceCatalog#reflectTableMethod} with {@code inputFieldName} in {@code argNames}
     * and any declared {@code contextArguments}. Appends an error and returns
     * {@link Optional#empty()} on reflection failure — mirrors {@code FieldBuilder.buildArgCondition}.
     */
    Optional<ArgConditionRef> buildInputFieldCondition(
            GraphQLInputObjectField field, String inputFieldName, List<String> errors) {
        var cond = readConditionDirective(field);
        if (cond == null) return Optional.empty();
        if (cond.argMappingError() != null) {
            errors.add("input field '" + inputFieldName + "' @condition: " + cond.argMappingError());
            return Optional.empty();
        }
        var bindingResult = ArgBindingMap.of(java.util.Map.of(field.getName(), field.getType()), cond.argMapping());
        if (bindingResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            errors.add("input field '" + inputFieldName + "' @condition: " + u.message());
            return Optional.empty();
        }
        if (bindingResult instanceof ArgBindingMap.Result.PathRejected p) {
            errors.add("input field '" + inputFieldName + "' @condition: " + p.message());
            return Optional.empty();
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            argBindings, Set.copyOf(cond.contextArguments()), null,
            ServiceCatalog.TableSlotPolicy.REQUIRED,
            java.util.Map.of(field.getName(), field.getType()));
        if (result.failed()) {
            errors.add("input field '" + inputFieldName + "' @condition: " + result.rejection().message());
            return Optional.empty();
        }
        var methodRef = result.ref();
        return Optional.of(new ArgConditionRef(
            new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params()),
            cond.override()));
    }

    // ===== Input-field classifier (shared between TypeBuilder and FieldBuilder) =====

    /**
     * Classifies a single {@link GraphQLInputObjectField} against {@code resolvedTable}, producing
     * an {@link InputFieldResolution}: either a fully classified {@link InputField} variant
     * (possibly with a {@code condition}) or an unresolved result with a diagnostic message.
     *
     * <p>Extracted from {@code TypeBuilder.buildInputField} so both {@link TypeBuilder}
     * (type-build pass, {@code @table} inputs) and {@link FieldBuilder} (argument-classify pass,
     * plain inputs) share the same decision tree.
     *
     * <p>Condition reflection failures append to {@code errors} and leave the
     * {@code condition} field empty — the field still classifies as its structural variant.
     * Column-miss and path-resolution failures return {@link InputFieldResolution.Unresolved}.
     *
     * <p>{@code ctx} (R215) bundles the structural facts threaded through recursive descent that
     * a single field's local view cannot recover: {@link ClassifyContext#expandingTypes} guards
     * against circular plain-input nesting (callers start with {@link ClassifyContext#root()}),
     * and {@link ClassifyContext#enclosingOverride} threads the cascade flag for future-growth
     * axes. The classifier's variant decisions today do not branch on {@code enclosingOverride}
     * (column-miss uniformly lifts to {@link InputField.UnboundField}); the consumer's
     * cascade-aware switch reads the carrier plus its own call-site {@code enclosingOverride}.
     */
    InputFieldResolution classifyInputField(
            GraphQLInputObjectField field, String parentTypeName, TableRef resolvedTable,
            ClassifyContext ctx, List<String> errors) {
        var resolution = classifyInputFieldInternal(field, parentTypeName, resolvedTable, ctx, errors);
        // Trace-only: input fields are stored embedded in their parent type rather than in a
        // central map, so the registry doesn't own their persistence; this is the canonical
        // emission point.
        fieldRegistry.classifyInput(parentTypeName, field.getName(),
            field.getDefinition() != null ? field.getDefinition().getSourceLocation() : null,
            resolution);
        return resolution;
    }

    private InputFieldResolution classifyInputFieldInternal(
            GraphQLInputObjectField field, String parentTypeName, TableRef resolvedTable,
            ClassifyContext ctx, List<String> errors) {
        String name = field.getName();
        if (field.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return new InputFieldResolution.Unresolved(name, null,
                "@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema.");
        }
        if (field.hasAppliedDirective(DIR_LOOKUP_KEY)) {
            return new InputFieldResolution.Unresolved(name, null,
                "@lookupKey on a mutation input field is no longer supported (R144); "
                + "remove it (the field is a filter by default; the UPDATE SET/WHERE "
                + "partition is derived from the catalog by the walker). On Query-side "
                + "@table input args, move @lookupKey to the surrounding ARGUMENT_DEFINITION "
                + "instead.");
        }
        GraphQLType type = field.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
        boolean hasFieldDir = field.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDir
            ? argString(field, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        // ID! / [ID!] with @nodeId(typeName: T), optionally pinned by @reference(path: [{key: K}]):
        // same-table → ColumnField / CompositeColumnField (filter by the parent's own key columns);
        // FK-target.DirectFk → ColumnReferenceField / CompositeColumnReferenceField with joinPath;
        // FK-target.TranslatedFk / Rejected → Unresolved. Both arities share the same resolver and
        // the same switch shape; the only delta is the list flag baked into the carrier. The
        // resolver also owns typeName inference for bare @nodeId, so the singular and list branches
        // see a single sealed result rather than re-deriving the typeName independently.
        if ("ID".equals(typeName) && field.hasAppliedDirective(DIR_NODE_ID)) {
            var resolved = nodeIdLeafResolver.resolve(field, name, resolvedTable);
            return inputFieldFromNodeIdResolved(
                resolved, parentTypeName, field, name, typeName, nonNull, list,
                resolvedTable, errors);
        }
        if (field.hasAppliedDirective(DIR_REFERENCE)) {
            var path = parsePath(field, name, resolvedTable.tableName(), null);
            if (path.hasError()) return new InputFieldResolution.Unresolved(name, null, path.errorMessage());
            return svc.resolveColumnForReference(columnName, path.elements(), resolvedTable.tableName())
                .<InputFieldResolution>map(col -> {
                    Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
                    // Plain (non-@nodeId) @reference: the predicate fires against the resolved
                    // column directly. liftedSourceColumns carries that single column so the
                    // emitter has one slot to read for both nodeId and non-nodeId carriers.
                    // selfReference=false: the self-FK fact (R354's all-SET routing) is decided only
                    // at the @nodeId discrimination site; a bare @reference is not a self-FK carrier.
                    return new InputFieldResolution.Resolved(new InputField.ColumnReferenceField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        col, path.elements(), List.of(col), false, cond,
                        new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()));
                })
                .orElseGet(() -> new InputFieldResolution.Unresolved(name, columnName,
                    "no column '" + columnName + "' reachable via @reference path"));
        }
        // Nesting: field type is a plain input object (no @table).
        var baseType = GraphQLTypeUtil.unwrapAll(type);
        if (baseType instanceof GraphQLInputObjectType nestedInputType
                && !nestedInputType.hasAppliedDirective(DIR_TABLE)) {
            if (ctx.isExpanding(typeName)) {
                return new InputFieldResolution.Unresolved(name, null,
                    "circular input type reference detected while expanding '" + typeName + "'");
            }
            // R215: read the field's own @condition override flag (cheap, no errors side effects)
            // so the recursive descent threads the cascade through nested fields. The classifier
            // does not branch on enclosingOverride for variant decisions, but the consumer's
            // cascade-aware switch reads it.
            var nestCondDirective = readConditionDirective(field);
            boolean nestOverride = nestCondDirective != null && nestCondDirective.override();
            var nestedCtx = ctx.expanding(typeName)
                .withOverride(ctx.enclosingOverride() || nestOverride);
            var failures = new ArrayList<InputFieldResolution.Unresolved>();
            var resolvedFields = new ArrayList<InputField>();
            for (var nested : nestedInputType.getFieldDefinitions()) {
                var res = classifyInputField(nested, typeName, resolvedTable, nestedCtx, errors);
                switch (res) {
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
                    .map(c -> candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName())))
                    .orElse("");
                return new InputFieldResolution.Unresolved(name, null,
                    "nested input type '" + typeName + "' has unresolvable fields: " + reasons + hint);
            }
            Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
            return new InputFieldResolution.Resolved(new InputField.NestingField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                List.copyOf(resolvedFields), cond));
        }
        String tableName = resolvedTable.tableName();
        // Synthesis shim: ID! or [ID!] on a @table input whose column name (from @field(name:) or
        // the GraphQL field name) hits the qualifier map for the resolved table AND the target
        // table has KjerneJooqGenerator node metadata. Runs before column lookup so
        // @field(name: "X_ID") fields are intercepted before the case-insensitive column match
        // would shadow the qualifier reverse-map. The shim routes to the same column-shaped
        // carriers as the canonical [ID!] @nodeId(typeName: T) branch above. Retirement plan:
        // roadmap/retire-synthesis-shims.md.
        if ("ID".equals(typeName)
                && !field.hasAppliedDirective(DIR_NODE_ID)
                && !field.hasAppliedDirective(DIR_REFERENCE)) {
            String shimFkName = catalog.buildQualifierMap(tableName).get(columnName.toLowerCase());
            if (shimFkName != null) {
                String qualifier = catalog.qualifierForFk(tableName, shimFkName)
                    .orElseThrow(() -> new IllegalStateException(
                        "qualifierForFk returned empty for FK '" + shimFkName + "' on table '"
                        + tableName + "' — should be unreachable"));
                Optional<String> targetTableOpt = catalog.findForeignKey(shimFkName)
                    .map(fk -> fk.getKey().getTable().getName());
                Optional<String> targetTypeOpt = targetTableOpt.flatMap(this::findGraphQLTypeForTable);
                // Gate: target table carries __NODE_TYPE_ID. Same KjerneJooqGenerator-project
                // sentinel the scalar bare-ID NodeId shim uses; if the target isn't a node type the
                // canonical replacement we'd emit (@nodeId(typeName:)) wouldn't typecheck either.
                var shimTargetMeta = catalog.nodeIdMetadata(targetTableOpt.orElse(""));
                if (shimTargetMeta.isPresent() && targetTypeOpt.isPresent()) {
                    boolean fkAmbiguous = catalog
                        .findUniqueFkToTable(tableName, targetTableOpt.get())
                        .isEmpty();
                    String canonical = fkAmbiguous
                        ? "@nodeId(typeName: \"" + targetTypeOpt.get() + "\")"
                          + " @reference(path: [{key: \"" + shimFkName + "\"}])"
                        : "@nodeId(typeName: \"" + targetTypeOpt.get() + "\")";
                    ID_REF_SHIM_LOGGER.warn(
                        "input field '{}.{}' synthesizes a NodeId reference from qualifier '{}'"
                        + " (FK '{}'); replace the legacy form with {} to drop the synthesis shim."
                        + " The shim will be removed in a future release;"
                        + " see roadmap/retire-id-reference-synthesis-shim.md",
                        parentTypeName, name, qualifier, shimFkName, canonical);
                    Optional<ArgConditionRef> shimRefCond = buildInputFieldCondition(field, name, errors);
                    var shimFkOpt = catalog.findForeignKey(shimFkName);
                    if (shimFkOpt.isEmpty()) {
                        return new InputFieldResolution.Unresolved(name, null,
                            "synthesis shim FK '" + shimFkName + "' not found in catalog");
                    }
                    // Synthesis shim is for input-side NodeId refs; the parent (input field's
                    // backing table) holds the FK by the shim's invariant, so selfRefFkOnSource
                    // is fixed at true.
                    var shimFkResolution = synthesizeFkJoin(shimFkOpt.get(), tableName, name, 0, null, /*selfRefFkOnSource=*/true);
                    var shimTargetResolution = catalog.findTable(targetTableOpt.get());
                    if (!(shimFkResolution instanceof FkJoinResolution.Resolved shimFkResolved)) {
                        return switch (shimFkResolution) {
                            case FkJoinResolution.UnknownTable u -> new InputFieldResolution.Unresolved(name, null,
                                unknownTableRejection(u.failure(), u.requestedName()).message());
                            case FkJoinResolution.UnknownForeignKey uf -> new InputFieldResolution.Unresolved(name, null,
                                unknownForeignKeyRejection(uf.fkName()).message());
                            case FkJoinResolution.Resolved r -> throw new IllegalStateException("unreachable");
                        };
                    }
                    if (!(shimTargetResolution instanceof JooqCatalog.TableResolution.Resolved shimTargetResolved)) {
                        return new InputFieldResolution.Unresolved(name, null,
                            unknownTableRejection(shimTargetResolution, targetTableOpt.get()).message());
                    }
                    List<JoinStep> shimJoinPath = List.of(shimFkResolved.fkJoin());
                    // Single-hop FK shim path: the lifted tuple is the first hop's source-side
                    // columns by construction (length-1 path; lift predicate is vacuous).
                    return buildInputNodeIdReference(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        shimTargetResolved.entry().toTableRef(targetTableOpt.get()),
                        targetTypeOpt.get(), targetTableOpt.get(),
                        shimTargetMeta.get().typeId(), shimTargetMeta.get().keyColumns(),
                        shimJoinPath, shimFkResolved.fkJoin().sourceSideColumns(), shimRefCond);
                }
            }
        }
        var colEntry = catalog.findColumn(tableName, columnName);
        if (colEntry.isPresent()) {
            var e = colEntry.get();
            Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
            // R215 §5: @condition(override: true) on a field collapses to UnboundField regardless
            // of whether the column resolves. The column is unused by construction (the explicit
            // condition method owns the predicate entirely), so recording it on a ColumnField is
            // dead storage. UnboundField is the canonical structural answer; the consumer's switch
            // becomes one-axis exhaustive over enclosingOverride × variant.
            if (cond.isPresent() && cond.get().override()) {
                return new InputFieldResolution.Resolved(new InputField.UnboundField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    cond, columnName));
            }
            return new InputFieldResolution.Resolved(new InputField.ColumnField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()), cond,
                new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()));
        }
        // NodeId synthesis shim: scalar ID field with no @nodeId directive whose backing table
        // carries node-identity metadata (__NODE_TYPE_ID / __NODE_KEY_COLUMNS constants emitted
        // by KjerneJooqGenerator). Fires a per-site deprecation diagnostic; the canonical form is
        // to declare @nodeId explicitly, which routes through the bare-@nodeId branch above. See
        // roadmap/retire-synthesis-shims.md.
        if ("ID".equals(typeName) && !list && !field.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta = catalog.nodeIdMetadata(tableName);
            if (nodeIdMeta.isPresent()) {
                NODE_ID_SHIM_LOGGER.warn("input field '{}.{}' synthesizes a NodeId-decoded column"
                    + " without '@nodeId'; declare the directive explicitly. The synthesis shim"
                    + " will be removed in a future release."
                    + " See roadmap/retire-synthesis-shims.md",
                    parentTypeName, name);
                // Arity-1 lands on InputField.ColumnField with extraction =
                // NodeIdDecodeKeys.SkipMismatchedElement; arity > 1 lands on
                // InputField.CompositeColumnField with the same extraction (narrowed at the type
                // level). HelperRef.Decode is resolved off the matching NodeType.
                var keyColumns = nodeIdMeta.get().keyColumns();
                var decodeMethod = resolveDecodeHelperForTable(tableName, nodeIdMeta.get().typeId(), keyColumns);
                if (decodeMethod == null) {
                    return new InputFieldResolution.Unresolved(name, null,
                        "@nodeId synthesis shim on input field '" + name + "': unable to resolve"
                        + " the NodeType backing table '" + tableName + "' (zero or multiple"
                        + " GraphQL types map to it). Declare @nodeId(typeName: T) explicitly.");
                }
                Optional<ArgConditionRef> shimCond = buildInputFieldCondition(field, name, errors);
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement(decodeMethod);
                if (keyColumns.size() == 1) {
                    return new InputFieldResolution.Resolved(new InputField.ColumnField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        keyColumns.get(0), shimCond, extraction));
                }
                return new InputFieldResolution.Resolved(new InputField.CompositeColumnField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    keyColumns, shimCond, extraction));
            }
        }
        // R215: column-miss lifts to InputField.UnboundField uniformly. The classifier emits the
        // structural variant once; the validator catches @condition(override:false) shapes at the
        // directive's location, and the consumer applies the cascade (admit when enclosingOverride
        // is true, reject at the field's source location otherwise).
        //
        // When the field carries @condition (any override flag), build it once so the carrier
        // records the explicit predicate. A failing condition build still emits UnboundField with
        // condition.empty() so the consumer-side path stays uniform; the condition error rides on
        // the errors list independent of variant choice.
        var conditionDirective = readConditionDirective(field);
        Optional<ArgConditionRef> unboundCond = Optional.empty();
        if (conditionDirective != null) {
            unboundCond = buildInputFieldCondition(field, name, errors);
        }
        return new InputFieldResolution.Resolved(new InputField.UnboundField(
            parentTypeName, name, locationOf(field), typeName, nonNull, list,
            unboundCond, columnName));
    }

    /**
     * Returns the GraphQL type name for the given SQL table name, or empty when zero or multiple
     * {@code @table}-annotated object types claim that table name (case-insensitive). Used by the
     * id-reference synthesis shim to map the FK's target table back to a GraphQL type name; the
     * shim then builds an {@link InputField.ColumnReferenceField} or
     * {@link InputField.CompositeColumnReferenceField} carrying the resolved type's
     * {@link no.sikt.graphitron.rewrite.GraphitronType.NodeType} key columns.
     */
    private Optional<String> findGraphQLTypeForTable(String sqlTableName) {
        var candidates = findGraphQLTypesForTable(sqlTableName);
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /**
     * Single switch shape over {@link NodeIdLeafResolver.Resolved} consumed by both arity branches
     * of the input-field {@code @nodeId} classifier ({@code ID!} and {@code [ID!]}). The
     * {@code list} flag flows through to the carrier; everything else is arity-independent. The
     * resolver also owns typeName inference, the schema/catalog lookup, and the lift validation,
     * so this helper is the only consumer of {@code Resolved} on the input-field side and the
     * singular and list branches stay in lockstep with {@link FieldBuilder#classifyArgument} by
     * construction.
     *
     * <p>Failure mode for the decode helper is fixed at
     * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch} (R378): an
     * authored input-field {@code @nodeId} filter leaf throws a {@code GraphitronClientException}
     * on a malformed or wrong-type encoded id rather than silently dropping it to "no row matches".
     * This matches the argument-level filter leaves in {@link FieldBuilder#classifyArgument}; the
     * legacy synthesis-shim arms (not reachable from here) keep {@code SkipMismatchedElement}.
     */
    private InputFieldResolution inputFieldFromNodeIdResolved(
            NodeIdLeafResolver.Resolved resolved, String parentTypeName,
            GraphQLInputObjectField field, String name, String typeName,
            boolean nonNull, boolean list, TableRef resolvedTable, List<String> errors) {
        switch (resolved) {
            case NodeIdLeafResolver.Resolved.Rejected r -> {
                return new InputFieldResolution.Unresolved(name, null, r.message());
            }
            case NodeIdLeafResolver.Resolved.SameTable st -> {
                Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
                // R378: authored input-field @nodeId filter throws on malformed/wrong-type ids.
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch(st.decodeMethod());
                if (st.keyColumns().size() == 1) {
                    return new InputFieldResolution.Resolved(new InputField.ColumnField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        st.keyColumns().get(0), cond, extraction));
                }
                return new InputFieldResolution.Resolved(new InputField.CompositeColumnField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    st.keyColumns(), cond, extraction));
            }
            case NodeIdLeafResolver.Resolved.FkTarget.DirectFk direct -> {
                Optional<ArgConditionRef> cond = buildInputFieldCondition(field, name, errors);
                // R378: authored input-field FK-target @nodeId filter throws on malformed/wrong-type ids.
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch(direct.decodeMethod());
                if (direct.keyColumns().size() == 1) {
                    return new InputFieldResolution.Resolved(new InputField.ColumnReferenceField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        direct.keyColumns().get(0), direct.joinPath(),
                        direct.liftedSourceColumns(), direct.selfReference(), cond, extraction));
                }
                return new InputFieldResolution.Resolved(new InputField.CompositeColumnReferenceField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    direct.keyColumns(), direct.joinPath(),
                    direct.liftedSourceColumns(), direct.selfReference(), cond, extraction));
            }
            case NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk translated -> {
                return new InputFieldResolution.Unresolved(name, null,
                    FieldBuilder.translatedFkRejectionReason(translated.refTypeName(), resolvedTable.tableName()));
            }
        }
    }

    /**
     * Builds the column-shaped carrier for {@code @nodeId(typeName: T)} input fields referencing
     * another table. Consumed only by the {@code id-reference} synthesis shim (a legacy
     * {@code ID!} column without {@code @nodeId} that maps via the qualifier-reverse-map to an
     * FK target table). The canonical {@code @nodeId}-decorated path routes through
     * {@link #inputFieldFromNodeIdResolved} via {@link NodeIdLeafResolver}; this helper exists
     * because the shim produces its key columns / join path / typeId from the qualifier map
     * rather than from a resolver call, so it cannot share the resolver's intake shape.
     *
     * <p>Routes by {@code targetKeyColumns.size()}: arity-1 to a single-column
     * {@link InputField.ColumnReferenceField} with extraction =
     * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement}; arity &gt; 1
     * to {@link InputField.CompositeColumnReferenceField} narrowed to the same extraction arm.
     */
    private InputFieldResolution buildInputNodeIdReference(
            String parentTypeName, String name, graphql.language.SourceLocation location,
            String typeName, boolean nonNull, boolean list, TableRef parentTable,
            String refTypeName, String targetTableName, String targetTypeId,
            List<ColumnRef> targetKeyColumns, List<JoinStep> joinPath,
            List<ColumnRef> liftedSourceColumns,
            Optional<ArgConditionRef> cond) {
        if (targetKeyColumns.isEmpty()) {
            return new InputFieldResolution.Unresolved(name, null,
                "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
                + "' which has no resolvable key columns (no NodeId metadata, no @node(keyColumns:),"
                + " and no primary key) — declare key columns on the target type or surface the"
                + " metadata via KjerneJooqGenerator");
        }
        var decodeMethod = resolveDecodeHelperForTable(targetTableName, targetTypeId, targetKeyColumns);
        if (decodeMethod == null) {
            return new InputFieldResolution.Unresolved(name, null,
                "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
                + "' which has no GraphQL type backing and no resolvable decode helper"
                + " — declare a @table-annotated object type for the target or surface the metadata"
                + " via KjerneJooqGenerator");
        }
        var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement(decodeMethod);
        // selfReference=false: this shim resolves the id-reference qualifier-reverse-map to a
        // cross-table FK target; the self-FK case routes through the @nodeId resolver, never here.
        if (targetKeyColumns.size() == 1) {
            return new InputFieldResolution.Resolved(new InputField.ColumnReferenceField(parentTypeName, name, location,
                typeName, nonNull, list, targetKeyColumns.get(0), joinPath, liftedSourceColumns, false, cond,
                extraction));
        }
        return new InputFieldResolution.Resolved(new InputField.CompositeColumnReferenceField(parentTypeName, name, location,
            typeName, nonNull, list, targetKeyColumns, joinPath, liftedSourceColumns, false, cond, extraction));
    }

    /**
     * Resolves the {@code decode<TypeName>} {@link no.sikt.graphitron.rewrite.model.HelperRef.Decode}
     * for the NodeType backing the given SQL table, or {@code null} when no usable mapping exists.
     * Used by the {@code @nodeId} synthesis shim and other input-side classifier paths that need
     * the per-Node decode helper but only have the SQL table name in scope.
     *
     * <p>Resolves through the {@code @node}-only {@link NodeIndex} by-table view, which is exactly
     * the right domain: it sees only {@code @node} types, not the nesting-projection {@code @table}
     * types that share the same rows. {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType#decodeMethod()}
     * is keyed on the GraphQL type name, matching {@code NodeIdEncoderClassGenerator}'s emitted
     * {@code decode<TypeName>} helper. Three outcomes:
     *
     * <ul>
     *   <li>Exactly one {@code @node} backs the table: return its {@code decodeMethod()}.</li>
     *   <li>Two or more {@code @node} types back it and the call site did not disambiguate with
     *       {@code @nodeId(typeName:)}: return {@code null} so the caller emits a validate-time
     *       rejection rather than a {@code decode<typeId>} call the encoder never generates.</li>
     *   <li>No {@code @node} backs it (orphan-input / synthesis-shim case): fall back to the
     *       metadata's {@code typeId} as the helper suffix. Only reachable through the synthesis
     *       shim, on a retirement track (see
     *       roadmap/retire-synthesis-shims.md).</li>
     * </ul>
     *
     * <p>Pre-R377 this routed through {@code findGraphQLTypeForTable}, an all-{@code @table} index,
     * which counted the nesting-projection types and so returned empty (ambiguous) for a table
     * backed by a {@code @node} plus a projection type. That sent decode resolution to the typeId
     * fallback, which agrees with the encoder only when {@code typeId} equals the type name; a
     * customized numeric {@code @node(typeId:)} then emitted a {@code decode<typeId>} call javac
     * could not resolve.
     */
    no.sikt.graphitron.rewrite.model.HelperRef.Decode resolveDecodeHelperForTable(
            String sqlTableName,
            String fallbackTypeId,
            java.util.List<no.sikt.graphitron.rewrite.model.ColumnRef> keyColumns) {
        // The NodeIndex's by-table view sees only @node types (not the nesting-projection @table
        // types that share the rows), so it is the authoritative decode source. decodeMethod() is
        // keyed on the GraphQL type name, matching NodeIdEncoderClassGenerator's emitted helper —
        // unlike the typeId-suffixed fallback below, which agrees with the encoder only when typeId
        // equals the type name.
        var nodesForTable = nodes.forTable(sqlTableName);
        if (nodesForTable.size() == 1) {
            return nodesForTable.get(0).decodeMethod();
        }
        if (!nodesForTable.isEmpty()) {
            // Two or more @node types back this table and the call site did not disambiguate with
            // @nodeId(typeName:). There is no implicit decode helper; return null so the caller emits
            // a validate-time rejection rather than a decode<typeId> call the encoder never generates.
            return null;
        }
        // No @node backs this table (orphan-input / synthesis-shim case: an `input Foo @table(...)`
        // with catalog NodeId metadata but no @node SDL type). Fall back to the metadata's typeId as
        // the helper suffix; only reachable through the synthesis shim, on a retirement track (see
        // roadmap/retire-synthesis-shims.md).
        if (fallbackTypeId == null || fallbackTypeId.isBlank()) return null;
        var encoderClass = no.sikt.graphitron.javapoet.ClassName.get(
            ctx.outputPackage() + ".util",
            no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator.CLASS_NAME);
        return new no.sikt.graphitron.rewrite.model.HelperRef.Decode(
            encoderClass, "decode" + fallbackTypeId, keyColumns, fallbackTypeId);
    }

    /**
     * Resolved NodeType key metadata for a {@code @nodeId(typeName:)} target: the wire-format
     * {@code typeId} plus the key {@link ColumnRef} list, or an {@code error} message when none of
     * the three resolution sources (catalog metadata, a post-first-pass {@link NodeType} in
     * {@code types}, or {@code @node} on the SDL with PK columns from the catalog) produce a usable
     * pair. Shared shape: both {@link NodeIdLeafResolver#resolve} and
     * {@link #resolveNodeIdRecordDecode} read their key columns through {@link #resolveTargetKeys},
     * so the {@code @node(keyColumns:)} fallback lives in exactly one place.
     */
    record TargetKeys(String typeId, List<ColumnRef> keyColumns, String error) {}

    /**
     * Resolves the target table's NodeType metadata: prefers catalog metadata, falls back to a
     * post-first-pass {@link NodeType} in {@code types}, then to {@code @node} on the SDL with PK
     * columns from the catalog. Returns an error message when none of those produce a usable
     * {@code typeId} + {@code keyColumns} pair. Lifted from {@code NodeIdLeafResolver} so the
     * input-bean jOOQ-record path ({@link #resolveNodeIdRecordDecode}) shares the same fallback.
     */
    TargetKeys resolveTargetKeys(GraphQLObjectType targetObj, String refTypeName,
                                 String targetTableName) {
        var meta = catalog.nodeIdMetadata(targetTableName);
        if (meta.isPresent()) {
            return new TargetKeys(meta.get().typeId(), meta.get().keyColumns(), null);
        }
        // R317 slice 4 — node lookup through the pure NodeIndex (a fixed point built before the walk),
        // not types.get: the node may not be registered yet during the walk.
        var ntOpt = nodes.forName(refTypeName);
        if (ntOpt.isPresent()) {
            return new TargetKeys(ntOpt.get().typeId(), ntOpt.get().nodeKeyColumns(), null);
        }
        if (targetObj.hasAppliedDirective(DIR_NODE)) {
            String typeId = argString(targetObj, DIR_NODE, ARG_TYPE_ID).orElse(refTypeName);
            var pkCols = catalog.findPkColumns(targetTableName).stream()
                .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()))
                .toList();
            if (pkCols.isEmpty()) {
                return new TargetKeys(null, null,
                    "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
                    + "' which has @node but no resolvable key columns (no catalog metadata, "
                    + "no @node(keyColumns:), no primary key)");
            }
            return new TargetKeys(typeId, pkCols, null);
        }
        return new TargetKeys(null, null,
            "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTableName
            + "' which is not a @node type (no NodeId catalog metadata, no @node directive)"
            + " — annotate the target type with @node or surface the metadata via KjerneJooqGenerator");
    }

    /**
     * Outcome of resolving the NodeId-decode materialization data for a jOOQ-record-typed
     * {@code @service} input-bean member field carrying {@code @nodeId(typeName:)}. {@link Resolved}
     * carries everything the {@code decode<Record>} helper materialises a {@link org.jooq.TableRecord}
     * from: the generated {@code NodeIdEncoder} {@code encoderClass} (to call {@code decodeValues}),
     * the wire-format {@code typeId} (its first argument), the target's key columns (the per-column
     * {@code set} loop), and the resolved {@link no.sikt.graphitron.rewrite.model.TableRef} (the
     * record class, the {@code Tables} constants class, and the table field name needed to write
     * {@code Tables.<T>.<col>}). {@link Rejected} carries a fully formatted reason ready for a
     * {@code Rejection}.
     *
     * <p>No {@code decode<TypeName>} method name is resolved: the materialization calls
     * {@code decodeValues(typeId, nodeId)}, never {@code decode<Type>}, so there is no suffix to
     * derive and the {@code resolveDecodeHelperForTable} typeName-vs-table trap does not apply here.
     */
    sealed interface NodeIdRecordDecode {
        record Resolved(no.sikt.graphitron.javapoet.ClassName encoderClass, String typeId,
                        List<ColumnRef> keyColumns,
                        no.sikt.graphitron.rewrite.model.TableRef table) implements NodeIdRecordDecode {}
        record Rejected(String message) implements NodeIdRecordDecode {}
    }

    /**
     * Resolves the NodeId-decode materialization data for a jOOQ-record input-bean member from the
     * author's {@code @nodeId(typeName:)}: the wire-format {@code typeId} and key columns come from
     * the table backing {@code typeName} via the shared {@link #resolveTargetKeys} (the single
     * {@code @node(keyColumns:)} fallback site), and the {@link no.sikt.graphitron.rewrite.model.TableRef}
     * comes from the jOOQ catalog entry for that table (record class + {@code Tables} constants).
     * Works for any key arity (single-column or composite); the caller (the input-bean resolver)
     * decides scalar-vs-list from the member's Java shape.
     */
    NodeIdRecordDecode resolveNodeIdRecordDecode(String typeName) {
        var rawGqlType = schema.getType(typeName);
        if (rawGqlType == null) {
            return new NodeIdRecordDecode.Rejected(
                "@nodeId(typeName: '" + typeName + "') type does not exist in the schema");
        }
        if (!(rawGqlType instanceof GraphQLObjectType targetObj)
                || !targetObj.hasAppliedDirective(DIR_TABLE)) {
            return new NodeIdRecordDecode.Rejected(
                "@nodeId(typeName: '" + typeName + "') type is not @table-annotated");
        }
        String targetTableName = argString(targetObj, DIR_TABLE, ARG_NAME)
            .orElse(typeName.toLowerCase());
        var keys = resolveTargetKeys(targetObj, typeName, targetTableName);
        if (keys.error() != null) {
            return new NodeIdRecordDecode.Rejected(keys.error());
        }
        var tableEntry = catalog.findTable(targetTableName).asEntry();
        if (tableEntry.isEmpty()) {
            return new NodeIdRecordDecode.Rejected(
                "@nodeId(typeName: '" + typeName + "') targets table '" + targetTableName
                + "' which is not in the jOOQ catalog");
        }
        var encoderClass = no.sikt.graphitron.javapoet.ClassName.get(
            ctx.outputPackage() + ".util",
            no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator.CLASS_NAME);
        return new NodeIdRecordDecode.Resolved(encoderClass, keys.typeId(), keys.keyColumns(),
            tableEntry.get().toTableRef(targetTableName));
    }

    /**
     * Outcome of resolving the FK child columns <em>on a record</em> that a cross-table
     * {@code @nodeId} reference loads its decoded values into (R315). {@link Resolved} carries the
     * target columns aligned to node-key (decode) order; {@link Rejected} carries a formatted reason.
     */
    sealed interface RecordFkTargets {
        record Resolved(List<ColumnRef> targetColumns) implements RecordFkTargets {}
        record Rejected(String message) implements RecordFkTargets {}
    }

    /**
     * Resolves the FK child columns on {@code recordTable} that a cross-table FK-reference
     * {@code @nodeId} populates (R315): the decoded node-key values load into the foreign key's child
     * columns on the record. This is legacy {@code NodeIdReferenceHelpers.mapKeyColumnsThroughForeignKey}
     * expressed in the rewrite's model.
     *
     * <p>FK selection: an explicit {@code @reference(key:)} ({@code explicitFkKey}) names the FK
     * verbatim via {@link JooqCatalog#findForeignKey}; otherwise the FK is <em>deduced</em> as the
     * single foreign key whose source side is {@code recordTable} and which references
     * {@code nodeTableSqlName} (the directional {@code findUniqueFkToTable} deduction, materialised to
     * the FK object). Zero or multiple such FKs reject through {@link #fkCountMessage}, asking the
     * author to add {@code @reference(key:)}.
     *
     * <p>Column reconciliation is <em>by column identity, not position</em> (D3): the decoded values
     * arrive in node-key order ({@code nodeKeyColumns}), while {@link #resolveFkSlots} returns the
     * pairs in the FK's own declaration order. For each node key column, the slot whose parent
     * ({@code targetSide}) column equals it contributes its child ({@code sourceSide}) column, so the
     * returned {@code targetColumns} align with the decode order. A positional zip would mis-assign
     * every value on a reordered FK (the {@code reordered_fk_child} fixture). A node key column not
     * covered by the chosen FK rejects.
     */
    RecordFkTargets resolveRecordFkTargetColumns(TableRef recordTable, String nodeTableSqlName,
            List<ColumnRef> nodeKeyColumns, Optional<String> explicitFkKey) {
        ForeignKey<?, ?> fk;
        if (explicitFkKey.isPresent()) {
            var fkOpt = catalog.findForeignKey(explicitFkKey.get());
            if (fkOpt.isEmpty()) {
                return new RecordFkTargets.Rejected("@reference(key: \"" + explicitFkKey.get()
                    + "\") could not be resolved in the jOOQ catalog"
                    + candidateHint(explicitFkKey.get(), catalog.allForeignKeySqlNames()));
            }
            fk = fkOpt.get();
        } else {
            var directional = catalog.findForeignKeysBetweenTables(recordTable.tableName(), nodeTableSqlName)
                .stream()
                .filter(k -> catalog.foreignKeyOnSource(k, recordTable.tableName(), /*selfRefHint=*/true))
                .toList();
            if (directional.size() != 1) {
                return new RecordFkTargets.Rejected(
                    fkCountMessage(recordTable.tableName(), nodeTableSqlName, directional, /*directiveAbsent=*/false));
            }
            fk = directional.get(0);
        }
        // Orient the FK against the record table as source: slot.sourceSide is the FK child column on
        // the record, slot.targetSide is the referenced (node-key) column. selfRefFkOnSource is live
        // since R328: when the FK is a self-FK (record table == node table, e.g. CAMPUS's
        // CAMPUS_EIER_CAMPUS_FK), the table-name comparison cannot decide orientation, so the
        // selfRefFkOnSource=true hint places the FK on the record (source) side and the decoded
        // node-key values land on the self-FK's child columns rather than the record's own PK.
        var slots = resolveFkSlots(fk, catalog.foreignKeyOnSource(fk, recordTable.tableName(), /*selfRefHint=*/true));
        var targetColumns = new ArrayList<ColumnRef>(nodeKeyColumns.size());
        for (var nodeKeyCol : nodeKeyColumns) {
            var match = slots.stream()
                .filter(s -> s.targetSide().sqlName().equalsIgnoreCase(nodeKeyCol.sqlName()))
                .findFirst();
            if (match.isEmpty()) {
                String referenced = slots.stream().map(s -> s.targetSide().sqlName())
                    .collect(Collectors.joining(", "));
                return new RecordFkTargets.Rejected("node key column '" + nodeKeyCol.sqlName()
                    + "' is not covered by foreign key '" + fk.getName() + "' (referenced columns: "
                    + referenced + ") — the NodeType's key is not fully mapped by this FK");
            }
            targetColumns.add(match.get().sourceSide());
        }
        return new RecordFkTargets.Resolved(List.copyOf(targetColumns));
    }

    /**
     * Returns every {@code @table}-annotated object type whose table name matches
     * {@code sqlTableName} (case-insensitive), in schema declaration order. Multiple object
     * types may legitimately share a table; callers that need a unique mapping handle the
     * ambiguous-and-empty cases themselves (see bare-{@code @nodeId} typeName inference).
     */
    List<String> findGraphQLTypesForTable(String sqlTableName) {
        return typeNamesByTableKey.getOrDefault(sqlTableName.toLowerCase(), List.of());
    }

    private static Map<String, List<String>> buildTypeNamesByTableKey(GraphQLSchema schema) {
        if (schema == null) return Map.of();
        var building = new HashMap<String, List<String>>();
        for (var t : schema.getAllTypesAsList()) {
            if (!(t instanceof GraphQLObjectType o) || !o.hasAppliedDirective(DIR_TABLE)) continue;
            var key = argString(o, DIR_TABLE, ARG_NAME).orElse(o.getName()).toLowerCase();
            building.computeIfAbsent(key, k -> new ArrayList<>()).add(o.getName());
        }
        return building.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }
}
