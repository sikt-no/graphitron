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
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.ParamSource;
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
 * not-yet-visited sibling's (every cross-type read is registry-free).
 */
class BuildContext {

    /**
     * The field name the Relay {@code Node} interface declares. A coordinate carrying this name
     * whose target is a node type is naming that node's own global ID: a node type has exactly one
     * field satisfying the interface, so the name identifies it. Read at all three coordinates the
     * implicit reading is available at (output field, input field, argument), which is why it lives
     * here rather than privately in one of them.
     */
    static final String NODE_INTERFACE_ID_FIELD = "id";

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
    static final String DIR_REFERENCE_FOR       = "referenceFor";
    static final String DIR_ERROR               = "error";
    static final String DIR_ROUTINE             = "routine";
    // Trigger-fact gathers own these directives' reads; each name has one home on its visitor.
    static final String DIR_DEFAULT_ORDER       = no.sikt.graphitron.facts.OrderByFactVisitor.DIR_DEFAULT_ORDER;
    static final String DIR_SPLIT_QUERY         = no.sikt.graphitron.facts.DeliveryFactVisitor.DIR_SPLIT_QUERY;
    static final String DIR_SERVICE             = no.sikt.graphitron.facts.ServiceFactVisitor.DIR_SERVICE;
    static final String DIR_EXTERNAL_FIELD      = "externalField";
    static final String DIR_LOOKUP_KEY          = no.sikt.graphitron.facts.LookupFactVisitor.DIR_LOOKUP_KEY;
    static final String DIR_TENANT_FAN_OUT      = no.sikt.graphitron.facts.DeliveryFactVisitor.DIR_TENANT_FAN_OUT;
    static final String DIR_ORDER_BY            = no.sikt.graphitron.facts.OrderByFactVisitor.DIR_ORDER_BY;
    static final String DIR_CONDITION           = no.sikt.graphitron.facts.ConditionFactVisitor.DIR_CONDITION;
    static final String DIR_MUTATION            = no.sikt.graphitron.facts.WriteFactVisitor.DIR_MUTATION;
    static final String DIR_DISCRIMINATOR       = "discriminator";
    // The pagination fact's gather owns this directive's read; the name has one home there.
    static final String DIR_AS_CONNECTION       = no.sikt.graphitron.facts.PaginationFactVisitor.DIR_AS_CONNECTION;
    static final String DIR_AS_FACET            = "asFacet";
    static final String DIR_SOURCE_ROW          = "sourceRow";
    static final String DIR_PIVOT               = "pivot";

    // ===== Argument names =====

    static final String ARG_CONTEXT_ARGUMENTS  = "contextArguments";
    static final String ARG_RECORD             = "record";
    static final String ARG_SERVICE_REF        = "service";
    static final String ARG_EXTERNAL_FIELD_REF = "reference";
    static final String ARG_METHOD             = "method";
    static final String ARG_ARGMAPPING        = "argMapping";
    static final String ARG_COLUMN_MAPPING     = "columnMapping";
    static final String ARG_VALUE              = "value";
    static final String ARG_NAME               = "name";
    static final String ARG_ON                 = "on";
    static final String ARG_VOCABULARY         = "vocabulary";
    static final String ARG_TYPE_ID            = "typeId";
    static final String ARG_KEY_COLUMNS        = "keyColumns";
    static final String ARG_TYPE_NAME          = "typeName";
    static final String ARG_TYPE               = "type";
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
    static final String ARG_DEFAULT_FIRST_VALUE = no.sikt.graphitron.facts.PaginationFactVisitor.ARG_DEFAULT_FIRST_VALUE;
    static final String ARG_CONNECTION_NAME     = "connectionName";
    static final String ARG_OVERRIDE            = no.sikt.graphitron.facts.ConditionFactVisitor.ARG_OVERRIDE;
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
     * via {@link TypeRegistry#get} / {@link TypeRegistry#entries}. The backing map is private,
     * so every write site goes through one of the four named operations.
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
     * Fixed-point reverse index over the schema's {@code @node} types
     * ({@link NodeIndex}), keyed by backing table and by type name. Populated once by
     * {@link TypeBuilder#buildClassificationIndices}. Field classification resolves every node-id
     * encoder through this index, never the type registry, keeping the single classification
     * walk registry-free. {@link NodeIndex#EMPTY} for
     * tests that build a registry without running the classification walk.
     */
    NodeIndex nodes = NodeIndex.EMPTY;
    /**
     * Pure, typename-keyed fixed-point index over the schema's table-backed types
     * ({@link TableIndex}). Populated once by {@link TypeBuilder#buildClassificationIndices}. Field
     * classification resolves the table-backed fact at its return/element/data-field edges through
     * this index, never the type registry, keeping the single classification walk registry-free.
     * {@link TableIndex#EMPTY} for tests that build a registry without
     * running the classification walk.
     */
    TableIndex tables = TableIndex.EMPTY;
    /**
     * Pure, typename-keyed fixed-point index over the schema's {@code @error} types
     * ({@link ErrorIndex}). Populated once by {@link TypeBuilder#buildClassificationIndices}. The
     * error-channel union-member scan resolves the error-member fact through this index, never
     * the type registry. {@link ErrorIndex#EMPTY} for tests that build a
     * registry without running the classification walk.
     */
    ErrorIndex errors = ErrorIndex.EMPTY;
    /**
     * Fixed-point index, participant type name &rarr; (field name &rarr; the
     * participant's {@link ParticipantRef.TableBound.CrossTableField}). Populated once by
     * {@link TypeBuilder#buildClassificationIndices} from the {@code @table}+{@code @discriminate}
     * interface scan and the participants' {@code @reference} SDL.
     * {@code FieldBuilder.lookupParticipantCrossTableField} is the read path.
     */
    Map<String, Map<String, ParticipantRef.TableBound.CrossTableField>> crossTableFieldsByParticipant = Map.of();
    /**
     * Fixed-point index, single-table-participant type name &rarr; (its own declared field name
     * &rarr; that coordinate's {@link no.sikt.graphitron.rewrite.model.AliasOwner}). Populated
     * once by {@link TypeBuilder#buildClassificationIndices} off the same
     * {@code @table}+{@code @discriminate} interface scan as
     * {@link #crossTableFieldsByParticipant}, so the alias-namespace verdict and the
     * {@link ParticipantRef.TableBound} / {@link ParticipantRef.JoinedTableBound} fork are one
     * classification rather than two. Only {@link ParticipantRef.TableBound} participants appear:
     * a joined-table participant's own select list never merges with a sibling's, so it keeps the
     * bare namespace. A field the interface declares is owned by the interface (the
     * lexicographically first declaring one when the type participates in several), a field only
     * the participant declares by the participant type. Absent entry means
     * {@link no.sikt.graphitron.rewrite.model.AliasOwner#shared()};
     * {@code FieldBuilder.aliasOwnerOf} is the read path. Empty for tests that build a registry
     * without running the classification walk.
     */
    Map<String, Map<String, no.sikt.graphitron.rewrite.model.AliasOwner>> aliasOwnerByParticipant = Map.of();
    /**
     * Fixed-point scalar verdicts, SDL scalar name &rarr; {@code classifyScalarType}'s verdict
     * (a {@link GraphitronType.ScalarType}, or an {@link GraphitronType.UnclassifiedType} for a
     * rejected declaration). Populated once by {@link TypeBuilder#buildClassificationIndices} over
     * all declared scalars, registry-free, so the wire-coercion predicate
     * ({@code WireCoercionResolver.checkScalar}) and the service slot-type mapping read the scalar
     * axis mid-walk without observing walk order. Empty for tests that build a registry without
     * running the classification walk.
     */
    Map<String, GraphitronType> scalarVerdicts = Map.of();
    /**
     * Set by {@link GraphitronSchemaBuilder} immediately after constructing {@link ServiceCatalog}.
     * Used by {@link #resolveConditionRef} for condition-join method reflection.
     */
    ServiceCatalog svc;

    /**
     * Set by {@link GraphitronSchemaBuilder} immediately after constructing the
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
     * The verdict of a type referenced from a coordinate under classification, safe to call
     * mid-walk. Reading {@link #types} during the walk observes walk order (a referenced leaf or
     * composite may be a not-yet-visited child), so this recomputes registry-free through
     * {@link TypeBuilder#lookAheadVerdict}, the same seam the return-type and DML-element
     * resolvers use, under the same name. Unit-tier callers that wire no {@link #typeBuilder}
     * (e.g. a {@code new BuildContext(null, null, ...)} reflection-test harness) fall back to the
     * registry view, which for them is pre-populated or deliberately empty; every production path
     * wires the builder before classification starts.
     */
    GraphitronType lookAheadVerdict(String typeName) {
        if (typeBuilder != null) {
            return typeBuilder.lookAheadVerdict(typeName);
        }
        return types == null ? null : types.get(typeName);
    }

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
     * Build-time validation diagnostics accumulated instead of demoting a classified verdict to
     * {@code UnclassifiedType} / {@code UnclassifiedField}. The global soundness reductions
     * (node-typeId uniqueness, case-fold collisions, the dangling-reference backstop, the
     * federation {@code @key} checks, and the multi-producer {@code DomainReturnType} agreement)
     * register a {@link ValidationError} here rather than overwriting the registry, so a verdict
     * read after the walk equals the verdict classification produced.
     * {@code GraphitronSchemaBuilder} hands the list to {@link GraphitronSchema}; the validator
     * drains it into its {@link ValidationError} stream.
     *
     * <p>Classify-time minting is equally intended, and is how a fan-in of per-field failures
     * reports one located fact per failure instead of one joined sentence at the consuming
     * coordinate. The channel is append-only and never read back: {@link #addDiagnostic} is
     * idempotent by value, so a fact minted by two consumers of the same input type collapses at
     * the mint rather than at some reader's drain.
     */
    private final Set<ValidationError> diagnostics = new LinkedHashSet<>();
    private final NodeIdLeafResolver nodeIdLeafResolver;
    /**
     * The catalog-wide tenant-scope classification, computed once at construction from the
     * configured {@code <tenantColumn>} element. {@link TenantScopes.None} for single-tenant
     * builds and for tests that construct a {@code BuildContext} without a catalog. Field
     * classification reads per-table scope through it; {@code GraphitronSchemaBuilder} threads
     * it onto the {@link GraphitronSchema} for the validator and the emitters.
     */
    final no.sikt.graphitron.rewrite.model.TenantScopes tenantScopes;
    /**
     * The gathered fact relations ({@link no.sikt.graphitron.facts.GatheredFacts}), produced by
     * the shared fact traversal over this context's pre-rewrite assembled schema before any
     * classification read. Classification reads resolved views over these relations instead of
     * re-reading the SDL surface each fact owns; empty for tests that construct a context
     * without a schema.
     */
    final no.sikt.graphitron.facts.GatheredFacts facts;

    /**
     * The build's single "is this object type a node?" predicate, shared by the classifier's
     * promotion gate and the pre-classification consumers that used to read {@code @node} off SDL
     * (reachability seeding, the arrival fold). One instance per build so the catalog's per-table
     * metadata cache is warmed once.
     */
    final NodeDeclaration nodeDeclaration;

    BuildContext(GraphQLSchema schema, JooqCatalog catalog, RewriteContext ctx) {
        // schema and catalog stay nullable for tests that focus on plumbing the other half; ctx
        // is required because every classifier the BuildContext fans into reads at least one of
        // its fields (codegenLoader, jooqPackage, classpathRoots). Test sites that don't care
        // about ctx still construct a deterministic stub via RewriteContext's 6-arg overload.
        this.schema = schema;
        this.catalog = catalog;
        this.ctx = java.util.Objects.requireNonNull(ctx, "ctx");
        this.nodeIdLeafResolver = new NodeIdLeafResolver(this);
        this.tenantScopes = catalog == null
            ? no.sikt.graphitron.rewrite.model.TenantScopes.None.INSTANCE
            : TenantScopeClassifier.classify(catalog, ctx.tenantColumn());
        this.nodeDeclaration = new NodeDeclaration(catalog);
        this.facts = schema == null
            ? no.sikt.graphitron.facts.GatheredFacts.empty()
            : no.sikt.graphitron.facts.GatheredFacts.gather(schema,
                (s, v) -> SchemaReachability.walk(s, nodeDeclaration, v));
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

    /**
     * The nameability rule over {@link RewriteContext#classpathRoots()}, built on first use and
     * shared by every author-written-name site in the run so each probed jar is listed once.
     * Inert (every name nameable) when the context carries no classpath roots, which is every
     * unit-tier caller; see {@link ClasspathNameability}.
     */
    ClasspathNameability nameability() {
        if (nameability == null) {
            nameability = new ClasspathNameability(ctx.classpathRoots());
        }
        return nameability;
    }

    private ClasspathNameability nameability;

    void addWarning(BuildWarning warning) {
        warnings.add(warning);
    }

    List<BuildWarning> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * Records a build-time validation diagnostic, in place of demoting a classified verdict; see
     * {@link #diagnostics}. Idempotent by value: input fields resolve once per consuming field, so
     * one input type used by five mutations mints the same fact five times, and the fact is the same
     * fact exactly when the {@link ValidationError} (coordinate, typed rejection, location) is
     * equal. Callers therefore never need to check the channel before minting.
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
            case GraphQLObjectType t      -> locationOf(t);
            case GraphQLInterfaceType t   -> locationOf(t);
            case GraphQLUnionType t       -> locationOf(t);
            case GraphQLInputObjectType t -> locationOf(t);
            case graphql.schema.GraphQLScalarType t -> locationOf(t);
            default                       -> null;
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
     * The name of the type {@code type} ultimately refers to, list and non-null wrappers peeled off,
     * or {@code null} when the base is unnamed.
     *
     * <p>Sibling of {@link #baseTypeName(GraphQLFieldDefinition)} for the programmatically-built
     * forms rather than assembled-schema ones: those name their children with
     * {@link graphql.schema.GraphQLTypeReference}, which is a {@link GraphQLNamedType} but not a
     * {@code GraphQLUnmodifiedType}, so {@code GraphQLTypeUtil.unwrapAll} throws a
     * {@code ClassCastException} on its final cast. Unwrapping one layer at a time avoids that cast
     * and treats a reference and a resolved type alike, which is the point: a sweep over a minted
     * form wants the name, and whether the form carries the instance or a forward reference to it is
     * an accident of how the form was built.
     */
    static String referencedTypeName(GraphQLType type) {
        GraphQLType current = type;
        while (GraphQLTypeUtil.isWrapped(current)) {
            current = GraphQLTypeUtil.unwrapOne(current);
        }
        return current instanceof GraphQLNamedType named ? named.getName() : null;
    }

    /**
     * Converts a type name and wrapper into the correct {@link ReturnTypeRef} variant by
     * consulting the populated {@link #types} map.
     *
     * <p>Single-record DML payloads are not short-circuited here. Carrier-shaped
     * SDL Objects resolve through the {@code target instanceof ResultType} arm below and return
     * a {@link ReturnTypeRef.ResultReturnType} the mutation classifier reads structurally.
     */
    ReturnTypeRef resolveReturnType(String targetTypeName, FieldWrapper wrapper) {
        // Resolve the target's verdict registry-free (look-ahead), not types.get: under
        // the single classify-and-emit walk a field's output target is a not-yet-visited child, so a
        // registry read would miss it. A NestingType / ConnectionType / carrier (none of the four
        // arms below) falls through to ScalarReturnType.
        GraphitronType target = typeBuilder.lookAheadVerdict(targetTypeName);
        if (target instanceof TableBackedType tbt)
            return new ReturnTypeRef.TableBoundReturnType(targetTypeName, tbt.table(), wrapper);
        if (target instanceof InterfaceType || target instanceof UnionType)
            return new ReturnTypeRef.PolymorphicReturnType(targetTypeName, wrapper);
        if (target instanceof ResultType rt)
            // The resolved table rides along: it is the fact that decides whether a producer over
            // this return hands down a typed jOOQ table record, and only JooqTableRecordType has
            // one on offer. Re-deriving it at each leaf is not possible uniformly (a batched
            // child's reflected return peels to java.util.Map), so it is carried.
            return new ReturnTypeRef.ResultReturnType(targetTypeName, wrapper, rt.fqClassName(),
                rt instanceof GraphitronType.JooqTableRecordType jtr ? jtr.table() : null);
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
            return new FieldWrapper.Connection(outerNullable, PaginationResolver.defaultPageSize(facts.pagination(), fieldDef));
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
     * Sealed result of a payload's structural carrier-shape scan, used by the
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
     * The single classify-time verdict over an {@code @service} carrier's shape triple:
     * (carrier field wrapper, {@code @service} producer return shape, payload data-field wrapper).
     * In the style of {@link DmlPayloadScan}, but its {@link Reject} arm carries a typed
     * {@link no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError} (with the disagreeing arrival
     * axes and a stable LSP code) rather than a composed reason string.
     *
     * <p>Computed at the {@code @service} payload seat by {@code FieldBuilder.scanServiceCarrierShape},
     * which folds the carrier arrival (read once from the carrier field's SDL wrapper), the producer
     * arrival (the typed fact decided at the reflection boundary,
     * {@link TypeBuilder#serviceCarrierProducerArrival}), and the data-field arrival (the canonical
     * {@link #scanStructuralServiceCarrierPayload} shape). The verdict is the single authority on
     * list-carrier admission.
     *
     * <ul>
     *   <li>{@link Coherent}: a single carrier ({@code Payload}), or a list carrier
     *       ({@code [Payload]}) whose producer returns a collection and whose {@code @table}-element
     *       data field is single. It carries {@link Coherent#producerArrival() producerArrival},
     *       the cardinality the SDL shape requires the {@code @service} producer to return, so the
     *       downstream return-type match ({@code FieldBuilder.checkServiceReturnMatchesPayload}) reads
     *       that one fact instead of re-deriving it from the carrier / data-field wrappers.</li>
     *   <li>{@link Reject}: an incoherent list carrier; carries the typed
     *       {@link no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError}.</li>
     *   <li>{@link NotApplicable}: not a producer-backed carrier (the seat falls through to its
     *       existing non-carrier classification).</li>
     * </ul>
     */
    public sealed interface ServiceCarrierShape {
        /**
         * @param producerArrival the arrival the SDL shape requires the {@code @service}
         *   producer's return to have ({@link no.sikt.graphitron.rewrite.model.Arity#MANY}
         *   for a list carrier {@code [Payload]}, or a single carrier whose data field is itself a
         *   list; {@link no.sikt.graphitron.rewrite.model.Arity#ONE} otherwise). Decided
         *   once at the verdict and carried, so no downstream consumer re-derives carrier arrival from
         *   the wrapper.
         */
        record Coherent(no.sikt.graphitron.rewrite.model.Arity producerArrival) implements ServiceCarrierShape {}
        record Reject(no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError error) implements ServiceCarrierShape {}
        record NotApplicable() implements ServiceCarrierShape {}
    }

    /**
     * The answer to "would this payload classify as a DML carrier were it not for a
     * forbidden directive on its data field". Returned by {@link #diagnoseForbiddenCarrierDirective}
     * so the {@code @mutation} return-type diagnostic can name the offending field and directive
     * (the {@code @}-prefixed directive name).
     */
    public record ForbiddenCarrierDirective(String dataFieldName, String directiveName) {}

    /**
     * Structural detection of a DML payload's carrier shape. Walks the payload
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
        DIR_DEFAULT_ORDER, DIR_ORDER_BY, DIR_MULTITABLE_REFERENCE);

    // The @service-carrier scan tolerates @splitQuery on the data field. The carrier's
    // data-field emit already resolves through a PK-keyed follow-up SELECT off the producer's
    // record (FetcherEmitter's Wrap.TableRecord arm), so @splitQuery adds nothing there, and
    // dropping the whole carrier over a redundant directive would leave an invalid assembled
    // schema (dangling payload typeRef). The classifier fires a redundancy advisory instead
    // (FieldBuilder's warnIfSplitQueryOnRecordParent family). DML carriers keep the strict set.
    private static final java.util.Set<String> FORBIDDEN_SERVICE_CARRIER_DATA_FIELD_DIRECTIVES =
        FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES.stream()
            .filter(d -> !d.equals(DIR_SPLIT_QUERY))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * The carrier family the structural scan is run for. A named axis (not a flag set)
     * because the families differ on two coupled policies: the forbidden-directive set on the
     * data field and the ID-element admission. DML (DELETE) carriers reject the
     * list-of-nullable {@code [ID]} and Connection wrappers (every element of a successful
     * DELETE response is the encoded PK of an actually-deleted row, so the slot cannot be
     * null); {@code @service} carriers admit {@code [ID]} (the data field projects whatever
     * record list the service returned, and the opptak schemas declare the weaker {@code [ID]}
     * contract) while still rejecting Connection; {@code @routine} carriers keep the strict DML
     * forbidden set and refuse the ID element outright, at any wrapper — the ID-element permit
     * exists for the DELETE PK echo, and a routine write has no PK-echo shape at all. A new
     * family extends the enum and gets exhaustiveness prompts at both policy sites.
     */
    private enum CarrierFamily {
        DML(FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES),
        SERVICE(FORBIDDEN_SERVICE_CARRIER_DATA_FIELD_DIRECTIVES),
        ROUTINE(FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES);

        final java.util.Set<String> forbiddenDataFieldDirectives;
        CarrierFamily(java.util.Set<String> forbiddenDataFieldDirectives) {
            this.forbiddenDataFieldDirectives = forbiddenDataFieldDirectives;
        }
    }

    /**
     * Whether {@link #scanStructuralPayload} consults the family's forbidden-directive set on
     * the data field. {@code ENFORCE} is the only policy the public scan methods use.
     * {@code IGNORE} is consulted
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
     * The {@code @service}-carrier variant of {@link #scanStructuralDmlPayload}. Identical
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
     * The {@code @routine}-carrier variant of {@link #scanStructuralDmlPayload}: the return
     * shape of a hop-less {@code @routine} Mutation write. Identical structural walk under the
     * strict DML forbidden-directive set; the family's own policy is the outright ID-element
     * refusal (see {@link CarrierFamily}). Consulted by the {@code @routine} carrier fork in
     * {@code FieldBuilder.classifyMutationField}, the {@code RoutineEmitted} grounding in
     * {@code RecordBindingResolver}, and {@code TypeBuilder.carrierBinding}.
     */
    public DmlPayloadScan scanStructuralRoutineCarrierPayload(String payloadSdlName) {
        return scanStructuralPayload(payloadSdlName, CarrierFamily.ROUTINE);
    }

    /**
     * Outcome of {@link #deriveRoutineCarrierPairs}: the name-matched pairs keying a routine
     * carrier's capture, or the typed failure the carrier classification lands on the mutation
     * field. Its own rejection rather than {@code synthesizeNameMatchedJoin}'s because that
     * message's {@code condition:} fix clause is false here — a condition join has no key tuple
     * to capture — so the one fix that works (expose the target's key column from the routine)
     * is the only one stated.
     */
    public sealed interface RoutineCarrierKeying {
        record Pairs(List<JoinSlot.FkSlot> pairs) implements RoutineCarrierKeying {
            public Pairs {
                pairs = List.copyOf(pairs);
            }
        }
        record Unmatched(String message) implements RoutineCarrierKeying {}
    }

    /**
     * The routine carrier's key derivation: one pure function over two catalog facts, the
     * routine's result table and the data-field element table's primary key, producing the
     * name-matched pairs (source side on the routine result, target side the element table's
     * PK) or its typed failure. The single derivation site — grounded once into
     * {@link no.sikt.graphitron.rewrite.model.ProducerBinding.RoutineEmitted} by
     * {@code RecordBindingResolver}, with {@code FieldBuilder.classifyMutationField} re-invoking
     * it only to surface the failure message when no binding grounded.
     */
    public static RoutineCarrierKeying deriveRoutineCarrierPairs(
            TableRef routineResultTable, TableRef targetTable) {
        if (targetTable.primaryKeyColumns().isEmpty()) {
            return new RoutineCarrierKeying.Unmatched(
                "cannot key the payload data field's re-read from '"
                + routineResultTable.tableName() + "' (a routine result, which carries no FK "
                + "metadata) to '" + targetTable.tableName() + "' — the target has no primary "
                + "key to name-match");
        }
        var pairs = new ArrayList<JoinSlot.FkSlot>(targetTable.primaryKeyColumns().size());
        for (ColumnRef keyCol : targetTable.primaryKeyColumns()) {
            var sourceCol = routineResultTable.allColumns().stream()
                .filter(c -> c.sqlName().equalsIgnoreCase(keyCol.sqlName()))
                .findFirst();
            if (sourceCol.isEmpty()) {
                return new RoutineCarrierKeying.Unmatched(
                    "cannot key the payload data field's re-read from '"
                    + routineResultTable.tableName() + "' to '" + targetTable.tableName()
                    + "' by name-match — the target's primary key column '" + keyCol.sqlName()
                    + "' is not exposed by name on '" + routineResultTable.tableName() + "'"
                    + candidateHint(keyCol.sqlName(),
                        routineResultTable.allColumns().stream().map(ColumnRef::sqlName).toList())
                    + "; expose the key column from the routine");
            }
            pairs.add(new JoinSlot.FkSlot(sourceCol.get(), keyCol));
        }
        return new RoutineCarrierKeying.Pairs(pairs);
    }

    /**
     * Whether {@code payloadTypeName} is the (unwrapped) return type of a root {@code @service} field
     * on Query or Mutation. Read straight off the assembled schema, so it is independent of
     * field-classification order. The single producer of this fact, shared by
     * {@code FieldBuilder.isRootServiceProducedPayload} (which selects the payload-side errors
     * {@code WrapperArm} transport) and {@code TypeBuilder.carrierBinding} (which gates the
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
     * The would-admit-but-for-the-directive probe: would {@code payloadSdlName} classify
     * as a DML carrier were it not for a forbidden directive on its data field, and if so, which
     * directive (on which field) blocked it?
     *
     * <p>Re-runs the structural DML scan with the forbidden-directive check disabled
     * ({@link ForbiddenDirectivePolicy#IGNORE}). If that pass {@code Admit}s, the payload is a
     * structurally valid DML carrier in every respect except the forbidden directive; the offending
     * directive is read off the admitted data field (the first
     * {@link #FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES} entry the field carries) and returned,
     * {@code @}-prefixed.
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
                // Channel-level rules: rule 7 (handler cardinality) and rule 8 (duplicate
                // match criteria). Test fixtures pin the wording, so the diagnostic family is
                // preserved through the structural scan.
                String handlerCardinality = FieldBuilder.checkChannelLevelHandlerRules(errorTypes);
                if (handlerCardinality != null) {
                    return new DmlPayloadScan.Reject(
                        "errors-shaped carrier field '" + f.getName() + "': " + handlerCardinality);
                }
                String duplicateMatchCriteria = FieldBuilder.checkDuplicateMatchCriteria(errorTypes);
                if (duplicateMatchCriteria != null) {
                    return new DmlPayloadScan.Reject(
                        "errors-shaped carrier field '" + f.getName() + "': " + duplicateMatchCriteria);
                }
                continue;
            }
            // Parse-time check on @field(name:). UnknownSigil ($-prefixed values that
            // aren't recognized literals like $source) reject ahead of the element-shape
            // dispatch with the canonical FieldSourceSigil.unknownSigilMessage wording.
            var fieldNameRef = FieldSourceSigil.parseArgFieldNameRef(f, DIR_FIELD, ARG_NAME);
            if (fieldNameRef instanceof FieldSourceSigil.ParseResult.UnknownSigil unknown) {
                return new DmlPayloadScan.Reject(FieldSourceSigil.unknownSigilMessage(unknown.raw()));
            }
            // Forbidden-directives check. The listed directives signal a different fetcher
            // contract than a payload carrier's data-field path, so their presence routes the
            // type away from the DML-payload mold. @field is intentionally NOT on this list.
            // Pure-metadata directives (@deprecated, custom directives without
            // execution semantics) pass through. Skipped under IGNORE so
            // diagnoseForbiddenCarrierDirective can establish would-admit-but-for-the-directive.
            if (policy == ForbiddenDirectivePolicy.ENFORCE) {
                for (String forbidden : family.forbiddenDataFieldDirectives) {
                    if (f.hasAppliedDirective(forbidden)) {
                        return new DmlPayloadScan.NotApplicable();
                    }
                }
            }
            String elementTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(f.getType())).getName();
            // Registry-free look-ahead at the payload data field's element type, not
            // types.get: this scan runs during field classification (and via carrierTableBinding), when
            // the element composite may not be registered yet. A recognized element is a @table or
            // record-bound type (classifyType non-null), so the look-ahead resolves without recursing
            // back through carrierTableBinding.
            var elementType = typeBuilder.lookAheadVerdict(elementTypeName);
            DmlElementKind kind;
            if (elementType instanceof GraphitronType.TableBackedType tbt) {
                kind = new DmlElementKind.Table(tbt.table(), elementTypeName);
            } else if (elementType instanceof GraphitronType.ResultType rt && rt.fqClassName() != null) {
                kind = new DmlElementKind.RecordElement(f.getName());
            } else if ("ID".equals(elementTypeName)) {
                // ID-element rules, per carrier family (see CarrierFamily);
                // test fixtures pin these diagnostic wordings. ROUTINE refuses the element
                // outright, at any wrapper: the permit exists for the DELETE PK echo, and a
                // routine write has no PK-echo shape at all.
                if (family == CarrierFamily.ROUTINE) {
                    return new DmlPayloadScan.Reject(
                        "carrier field '" + f.getName() + "' has element type 'ID'; the "
                        + "ID-element data field is the DELETE PK-echo permit, and a routine "
                        + "write has no PK-echo shape, so a @routine carrier admits no "
                        + "ID-element data field — use a @table-element data field");
                }
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
                        case ROUTINE -> throw new IllegalStateException(
                            "unreachable: the ROUTINE family refuses the ID element outright above");
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
     * polymorphic-of-all-{@code @error} list with the required nullable-list shape). Returns
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
            // Error membership through the pure ErrorIndex (a fixed point built before
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
                ? catalog.fkJavaConstantName(fk).orElse(fk.getName())
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
     * so the LSP layer can render the verdict without re-parsing
     * {@link #errorMessage()}. It is {@link TerminalTargetVerdict.Mismatch} when the terminal hop
     * lands on the wrong table, {@link TerminalTargetVerdict.Match} when it lands correctly, and
     * {@link TerminalTargetVerdict.NotApplicable} when the check did not run (no table-backed
     * start, no return table, empty path, or an earlier error short-circuited parsing).
     *
     * <p>The verdict is independent of {@link #errorMessage()}: a {@code Mismatch} is carried on an
     * otherwise non-error {@code ParsedPath} (the resolved elements are present). The inline / split
     * output callers (the projection renderer's {@code $project(terminalAlias)} descent,
     * wired through {@code FieldBuilder}) convert {@code Mismatch} into an
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} via
     * {@link TerminalTargetVerdict.Mismatch#diagnostic()}; callers with their own terminal-target
     * invariant keep their existing checks and ignore the verdict.
     */
    record ParsedPath(List<JoinStep> elements, String errorMessage,
            TerminalTargetVerdict terminalTargetVerdict) {
        boolean hasError() { return errorMessage != null; }
    }

    /**
     * Typed outcome of {@link #parsePath}'s terminal-hop landing-table check (Check 1): does
     * the {@code @reference} path's terminal hop land on the field return type's {@code @table}?
     * The renderer's multiset arm feeds the terminal hop's alias to a
     * {@code $project} overload typed for the return table, so a terminal hop that lands elsewhere
     * compiles to generated Java that javac rejects with an incompatible-types error in a
     * downstream consumer's build. This verdict moves that failure to build time.
     *
     * <p>Exposed as a sealed projection rather than only an {@code errorMessage} string so the
     * LSP layer can surface the terminal-target diagnostic at edit time, consuming the verdict via
     * the catalog snapshot rather than re-deriving it. {@link Mismatch} carries
     * exactly the names {@link Mismatch#diagnostic()} prints, so the rendered string and the
     * structured projection cannot drift.
     */
    public sealed interface TerminalTargetVerdict {

        /** The terminal hop lands on the return table (or is a condition join, whose target
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
                    + "' (the terminal alias is fed to a $project overload typed for the return table).";
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
        return tableNameForTypeName(typeName).flatMap(catalog::findRecordClass);
    }

    /**
     * The SQL table name a GraphQL type binds through its {@code @table} directive, defaulting to
     * the lower-cased type name where the directive names none. Empty when the type is not in the
     * schema, is not an object type, or carries no {@code @table} at all.
     *
     * <p>The name only, deliberately: callers that want the resolved {@link TableRef} route it
     * through {@link #resolveTable}, and callers that want the node types over it route it through
     * {@link #inferNodeTypeOverTable}, neither of which needs the catalog to have answered first.
     */
    Optional<String> tableNameForTypeName(String typeName) {
        if (typeName == null) return Optional.empty();
        var raw = schema.getType(typeName);
        if (!(raw instanceof GraphQLObjectType obj) || !obj.hasAppliedDirective(DIR_TABLE)) {
            return Optional.empty();
        }
        return Optional.of(argString(obj, DIR_TABLE, ARG_NAME).orElse(typeName.toLowerCase()));
    }

    /** Either the node type a bare {@code @nodeId} inferred, or why the inference could not pick one. */
    record InferredNodeType(String typeName, String error) {}

    /**
     * The node type a bare {@code @nodeId} names, inferred from the table the slot resolves
     * against. Resolved over node types rather than over every {@code @table}-annotated object
     * type: bare {@code @nodeId} means "node id, target inherited", so the question is "which
     * <em>node</em> backs this table", and answering the wider question lets a nesting-projection
     * type sharing the same rows count as a candidate.
     *
     * <p>One rule with two absences, both permanent rather than a shim's: no node over the table
     * and several over it are each a message naming {@code typeName:} as the fix. The rule is the
     * fact model's {@code TARGET_TABLE_NODE_TYPE} basis, and the callers differ only in how they
     * arrive at the table: {@link NodeIdLeafResolver} from the leaf's containing table, and
     * {@link ServiceCatalog} from the slot's own scope, which is the consuming field's return table
     * or, where that binds nothing, the table {@code @mutation(table:)} names.
     */
    InferredNodeType inferNodeTypeOverTable(String tableName) {
        var candidates = nodes.forTable(tableName).stream()
            .map(NodeType::name)
            .sorted()
            .toList();
        if (candidates.isEmpty()) {
            return new InferredNodeType(null,
                "@nodeId without typeName: cannot infer node type — no node type"
                + " maps to table '" + tableName + "'."
                + " Add typeName: explicitly.");
        }
        if (candidates.size() > 1) {
            return new InferredNodeType(null,
                "@nodeId without typeName: is ambiguous — multiple node types map to table '"
                + tableName + "': " + String.join(", ", candidates)
                + ". Specify typeName: explicitly.");
        }
        return new InferredNodeType(candidates.getFirst(), null);
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
     * catalog's FK names so a typo in {@code @reference(key: "...")} reaches the schema author with
     * a fix-it suggestion rather than just a "not in catalog" string.
     *
     * <p><b>Namespace.</b> {@link JooqCatalog#findForeignKey} resolves a key in either the SQL
     * constraint namespace or the jOOQ Java-constant {@code TABLE__CONSTRAINT} namespace, so the
     * candidate hint mirrors whichever the author used (detected by the {@code __} separator in
     * {@code fkName}); a suggestion never reads as a different namespace than the one they typed.
     * This matches {@link #fkCandidateNames} on the path-element hint surface. The candidate set
     * here is the whole catalog, not scoped to the structurally relevant FKs (no source table is
     * threaded through these call sites).
     */
    Rejection unknownForeignKeyRejection(String fkName) {
        boolean constantNamespace = fkName.contains("__");
        return Rejection.unknownForeignKey(
            "foreign key '" + fkName + "' could not be resolved in the jOOQ catalog",
            fkName,
            constantNamespace ? catalog.allForeignKeyConstantNames() : catalog.allForeignKeySqlNames());
    }

    /**
     * Builds the {@link Rejection} for a {@code @reference(key:)} name that matches an FK constraint
     * name present in more than one schema. Symmetric to {@link #unknownTableRejection}'s
     * ambiguity arm: an {@link Rejection.AuthorError.Structural} rule violation, not a "did you
     * mean" candidate lookup (the author spelled a real name; what they need is to scope it, not a
     * typo fix). Names the colliding schemas and the schema-qualified forms so the fix is actionable.
     *
     * <p>Reached only from the author-facing name-lookup sites (the {@code {key:}} path element and
     * the explicit {@code @reference(key:)} record-FK site) when the source table did not scope the
     * collision away. {@link #synthesizeFkJoin} never surfaces this:
     * the FK there is resolved by class identity, so ambiguity is impossible past the
     * name-lookup boundary.
     */
    Rejection ambiguousForeignKeyRejection(String fkName, List<String> schemas) {
        String qualifiedHints = schemas.stream()
            .map(s -> "'" + s + "." + fkName + "'")
            .collect(Collectors.joining(", "));
        return Rejection.structural(
            "foreign key '" + fkName + "' is ambiguous: the constraint name is defined in schemas "
            + schemas + "; scope the source @table(name:) so the FK resolves within one schema, or"
            + " schema-qualify the key itself (qualified forms: " + qualifiedHints + ")");
    }

    /**
     * The connection invariant both author-facing {@code @reference(key:)} sites share: a resolved
     * FK must have an endpoint on the table the author is standing on. A {@code key:} value
     * can schema-qualify to an FK in a <em>different</em> schema than the source (see
     * {@link JooqCatalog#findForeignKey(String, String, String)}), so "the FK resolves but does not
     * touch this table" is reachable at both the {@code {key:}} path element and the explicit
     * record-FK site; the check lives here as the single enforcer.
     *
     * <p>Returns the "does not connect" rejection message when {@code fk} misses
     * {@code sourceSqlName}; empty when it connects, or when {@code sourceSqlName} is {@code null}
     * (source not table-backed, where connectivity cannot be asserted).
     */
    private Optional<String> foreignKeyConnectionRejection(ForeignKey<?, ?> fk, String sourceSqlName) {
        if (sourceSqlName == null || catalog.foreignKeyTouchesTable(fk, sourceSqlName)) {
            return Optional.empty();
        }
        return Optional.of("key '" + fk.getName() + "' does not connect to table '" + sourceSqlName + "'"
            + candidateHint(sourceSqlName,
                List.of(fk.getTable().getName(), fk.getKey().getTable().getName())));
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
     * FK-derived hop from the catalog when exactly one foreign key connects {@code startSqlTableName}
     * to {@code targetSqlTableName}.
     *
     * <p>Inference fires only when all three of: {@code startSqlTableName != null},
     * {@code targetSqlTableName != null}, and the two names differ (case-insensitive) — the same
     * constraints as {@link JooqCatalog#findForeignKeysBetweenTables}. Zero or multiple FKs produce
     * a {@code ParsedPath} with a non-null {@code errorMessage} asking the author to write an
     * explicit {@code @reference} directive.
     *
     * <p>Returns {@code ParsedPath(List.of(), null)} when both inference preconditions are
     * unsatisfied (typical for {@code ColumnBackedReferenceField}, service reconnect, and same-table
     * {@code @externalField} sites).
     *
     * <p>{@code fieldName} is the GraphQL field name and is used to compute per-step aliases
     * ({@code fieldName + "_" + stepIndex}). {@code startSqlTableName} is the SQL table name at
     * the start of the path (the parent type's table), or {@code null} when the source is not
     * table-backed — in which case FK direction is inferred as forward and connectivity is not
     * validated. {@code targetSqlTableName} is the SQL name of the return type's table when known,
     * or {@code null} to disable implicit-path inference at this site.
     *
     * <p>This 4-argument overload passes a {@code null} {@code returnTableRef}, so its terminal
     * verdict is always {@link TerminalTargetVerdict.NotApplicable}. It is for sites that either
     * pass a null target (no terminal-target invariant to check) or do not carry the resolved
     * return {@link TableRef}; sites that hold the ref call the {@code returnTableRef}-carrying
     * overloads so {@link #computeTerminalTargetVerdict} can compare identity.
     */
    ParsedPath parsePath(GraphQLDirectiveContainer container, String fieldName,
            String startSqlTableName, String targetSqlTableName) {
        return parsePath(container, fieldName, startSqlTableName, targetSqlTableName, /*returnTableRef=*/null, /*isList=*/false);
    }

    /**
     * Variant of {@link #parsePath} that carries the resolved return-type {@link TableRef} for the
     * terminal-target verdict, without the list-cardinality signal. The {@code returnTableRef}
     * is name/identity's second orthogonal projection of the return table: {@code targetSqlTableName}
     * stays the input to the name-based plumbing (empty-path FK inference, condition-join terminal
     * build), while the ref is consumed only by {@link #computeTerminalTargetVerdict}, which compares
     * jOOQ table-class identity so a schema-qualified {@code @table} echo matches jOOQ's unqualified
     * canonical name. Callers pass the ref they already hold one frame up rather than re-resolving.
     */
    ParsedPath parsePath(GraphQLDirectiveContainer container, String fieldName,
            String startSqlTableName, String targetSqlTableName, TableRef returnTableRef) {
        return parsePath(container, fieldName, startSqlTableName, targetSqlTableName, returnTableRef, /*isList=*/false);
    }

    /**
     * Variant of {@link #parsePath} that accepts both the resolved return-type {@link TableRef}
     * (see the {@code returnTableRef} overload above) and the field's list-cardinality, used
     * to disambiguate self-referential FK direction at synthesis time. For {@code category.parent}
     * the parent (source) holds the FK; for {@code category.children} the child (target) holds it,
     * even though both navigate the same FK constraint. Cardinality is the only reliable signal
     * here — the table-name comparison cannot resolve self-ref direction.
     */
    ParsedPath parsePath(GraphQLDirectiveContainer container, String fieldName,
            String startSqlTableName, String targetSqlTableName, TableRef returnTableRef, boolean isList) {
        // @reference is repeatable, and repeated field-level applications compose one
        // chain — their path elements concatenate in authored order over a single running
        // source, so a multi-application chain is just a longer path to every downstream
        // consumer. Argument / input-field positions reject repeated applications upstream,
        // and the root-head rule rejects an @reference-first root chain, so concatenation
        // here is the field-level chain rule and nothing else.
        var applications = container.getAppliedDirectives(DIR_REFERENCE);
        var elements = new ArrayList<Object>();
        for (var application : applications) {
            var pathArg = application.getArgument(ARG_PATH);
            Object pathValue = pathArg != null ? pathArg.getValue() : null;
            List<?> appElements = pathValue instanceof List<?> l ? l
                : pathValue != null ? List.of(pathValue) : List.of();
            if (applications.size() > 1 && appElements.stream().noneMatch(v -> v instanceof Map<?, ?>)) {
                // Element-less inference resolves the FK between the field's endpoints; inside a
                // multi-application chain an application has no endpoints of its own, so each
                // must state its hops (same rule as parseChainSegment's routine-chain segments).
                return new ParsedPath(List.of(),
                    "an @reference application composing a table chain must carry at least one "
                    + "path element — hops in a multi-application chain are stated explicitly",
                    new TerminalTargetVerdict.NotApplicable());
            }
            elements.addAll(appElements);
        }

        var resolvedElements = new ArrayList<JoinStep>();
        var errors = new ArrayList<String>();
        resolvePathElements(elements, fieldName, startSqlTableName, targetSqlTableName, isList,
            /*stepIndexBase=*/0, /*endsChain=*/true, resolvedElements, errors);

        if (!errors.isEmpty()) {
            return new ParsedPath(List.of(), String.join("; ", errors), new TerminalTargetVerdict.NotApplicable());
        }
        if (resolvedElements.isEmpty()
                && startSqlTableName != null
                && targetSqlTableName != null
                && !startSqlTableName.equalsIgnoreCase(targetSqlTableName)) {
            // The same catalog gate the {table:} element branch applies, hoisted onto the
            // element-less arm: a routine result declares no foreign keys, so the FK inference
            // below can only ever fail on one, and it fails with advice (an intermediate table,
            // a condition: predicate) that names the wrong problem. The name-matched hop the
            // explicit form resolves to takes only the target table name from the directive, and
            // the field's return type already carries that, so on this source there is nothing
            // left for the author to write.
            if (catalog.isTableValuedFunction(startSqlTableName)) {
                var nameMatched = new ArrayList<JoinStep>();
                var nameMatchErrors = new ArrayList<String>();
                synthesizeNameMatchedJoin(targetSqlTableName, startSqlTableName, fieldName,
                    /*stepIndex=*/0, /*whereFilter=*/null, nameMatched, nameMatchErrors);
                if (!nameMatchErrors.isEmpty()) {
                    return new ParsedPath(List.of(), String.join("; ", nameMatchErrors),
                        new TerminalTargetVerdict.NotApplicable());
                }
                resolvedElements.addAll(nameMatched);
            } else {
                var fks = catalog.findForeignKeysBetweenTables(startSqlTableName, targetSqlTableName);
                if (fks.size() == 1) {
                    var stepResolution = synthesizeFkJoin(fks.get(0), startSqlTableName, fieldName, 0, null, /*selfRefFkOnSource=*/!isList);
                    switch (stepResolution) {
                        case FkJoinResolution.Resolved r -> resolvedElements.add(r.hop());
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
        }
        // Check 1: compute the terminal-target verdict — does the terminal hop land on the
        // return type's @table? The terminal target is already resolved on the last JoinStep (the
        // loop advances currentSource through HasTargetTable.targetTable()); this reuses the
        // resolved value rather than re-deriving the hop kind from the directive element. Gated on a
        // non-empty path plus a non-null start AND return table (see computeTerminalTargetVerdict).
        //
        // The verdict is threaded onto ParsedPath as a typed projection rather than forced into
        // errorMessage here: parsePath is shared by callers that have their own terminal-target
        // invariant (e.g. a directive path's "last hop lands on …" check) and callers whose
        // emit shape does not feed the terminal alias to a $project overload at all. Only the inline
        // / split output projection (the renderer's multiset arm) carries the $project(terminalAlias)
        // invariant this check protects, so its two FieldBuilder callers (TableBoundReturnType,
        // TableInterfaceType) consume the verdict and reject on Mismatch. The LSP layer reads the
        // same projection. This keeps the predicate single-sourced and scoped to where it applies.
        var terminalVerdict = computeTerminalTargetVerdict(resolvedElements, fieldName, startSqlTableName, targetSqlTableName, returnTableRef);
        return new ParsedPath(List.copyOf(resolvedElements), null, terminalVerdict);
    }

    /**
     * Resolves an <em>explicit</em>, already-extracted list of {@code @reference}-grammar path
     * elements ({@code {table:}}/{@code {key:}}/{@code {condition:}} maps) for a stated
     * {@code (startSqlTableName, targetSqlTableName)} pair, returning the same {@link ParsedPath}
     * shape as {@link #parsePath} — resolved {@link JoinStep}s, an error message, and the
     * terminal-target verdict against {@code returnTableRef}.
     *
     * <p>This is the {@code @referenceFor} entry point: unlike {@link #parsePath} it does
     * <em>not</em> read {@code @reference} applications off a container (the caller supplies the
     * element list from one {@code @referenceFor} application's {@code path:} argument), and it does
     * <em>not</em> apply empty-path FK auto-discovery — a {@code @referenceFor} application states a
     * complete path, so an element-less list is an author error rather than an inference trigger.
     * The element-resolution walk ({@link #resolvePathElements}) and terminal-target verdict
     * ({@link #computeTerminalTargetVerdict}) are shared with {@link #parsePath} unchanged, so a
     * self-referential {@code {key:}} on a same-table participant resolves through the same
     * synthesis (with {@code selfRefFkOnSource = !isList}) that auto-discovery would use.
     */
    ParsedPath parseExplicitPath(List<?> elements, String fieldName, String startSqlTableName,
            String targetSqlTableName, TableRef returnTableRef, boolean isList) {
        if (elements.stream().noneMatch(v -> v instanceof Map<?, ?>)) {
            return new ParsedPath(List.of(),
                "path must carry at least one reference element ({table:}, {key:}, or {condition:})",
                new TerminalTargetVerdict.NotApplicable());
        }
        var resolvedElements = new ArrayList<JoinStep>();
        var errors = new ArrayList<String>();
        resolvePathElements(elements, fieldName, startSqlTableName, targetSqlTableName, isList,
            /*stepIndexBase=*/0, /*endsChain=*/true, resolvedElements, errors);
        if (!errors.isEmpty()) {
            return new ParsedPath(List.of(), String.join("; ", errors), new TerminalTargetVerdict.NotApplicable());
        }
        var terminalVerdict = computeTerminalTargetVerdict(resolvedElements, fieldName, startSqlTableName, targetSqlTableName, returnTableRef);
        return new ParsedPath(List.copyOf(resolvedElements), null, terminalVerdict);
    }

    /**
     * The element-walk core shared by {@link #parsePath} and {@link #parseChainSegment}: resolves
     * each map-shaped path element through {@link #parsePathElement}, advancing the current
     * source table through the resolved hop's {@link JoinStep.HasTargetTable#targetTable()}
     * (every hop carries one; LiftedHop is never produced by {@code @reference} path
     * parsing). Steps are aliased {@code fieldName + "_" + stepIndex} with {@code stepIndex}
     * running across the whole chain — {@code stepIndexBase} carries the offset when the caller
     * walks a chain segment-by-segment (0 when the element list is the whole chain).
     *
     * <p>{@code endsChain} narrows which element may read a declared target (the field's return
     * {@code @table}, which a {@code {condition:}}-only element prefers over reflecting on its
     * method signature) to the element that truly ends the field's chain: a segment followed by
     * further chain nodes passes {@code false} so none of its elements read the return table.
     */
    private void resolvePathElements(List<?> elements, String fieldName, String startSqlTableName,
            String targetSqlTableName, boolean isList, int stepIndexBase, boolean endsChain,
            List<JoinStep> resolvedElements, List<String> errors) {
        String currentSource = startSqlTableName;
        int stepIndex = stepIndexBase;
        int localIndex = 0;
        int totalElements = (int) elements.stream().filter(v -> v instanceof Map<?, ?>).count();
        for (var v : elements) {
            if (v instanceof Map<?, ?>) {
                boolean isTerminal = endsChain && (localIndex == totalElements - 1);
                parsePathElement(asMap(v), currentSource, fieldName, stepIndex,
                    resolvedElements, errors, isList, isTerminal, targetSqlTableName);
                stepIndex++;
                localIndex++;
                if (!resolvedElements.isEmpty()) {
                    var last = resolvedElements.getLast();
                    currentSource = last instanceof JoinStep.HasTargetTable ht
                        ? ht.targetTable().tableName() : null;
                }
            }
        }
    }

    /**
     * Result of {@link #parseChainSegment}: the segment's resolved hops, or a joined error
     * message. No terminal verdict — the caller walking a routine chain owns the terminus rule
     * over the <em>whole</em> chain (the last node may be a routine, which no segment parse
     * sees).
     */
    record ChainSegment(List<JoinStep> hops, String errorMessage) {
        boolean hasError() { return errorMessage != null; }
    }

    /**
     * Parses one {@code @reference} application inside a routine chain into its resolved
     * hops. The chain walker in {@code FieldBuilder} ({@code walkRoutineChain}, shared by the
     * root and child chain classifiers) calls this per application with the running source
     * table and a running {@code stepIndexBase} (chain-wide {@code fieldName + "_" + N}
     * aliasing); a segment whose running source is a routine result gets the FK-less
     * name-matched keying ({@link #synthesizeNameMatchedJoin}, gated inside
     * {@link #parsePathElement} on the catalog's table-valued-function fact). {@code endsChain}
     * is true only for the segment whose last element ends the whole chain (false when a
     * routine node or another segment follows), which gates the terminal {@code {condition:}}
     * target-from-return-table resolution in {@link #resolvePathElements}.
     *
     * <p>An application with no path elements is an error, exactly as in {@link #parsePath}'s
     * multi-application arm: element-less inference resolves the FK between the field's
     * endpoints, and inside a chain an application has no endpoints of its own.
     */
    ChainSegment parseChainSegment(graphql.schema.GraphQLAppliedDirective refApplication,
            String fieldName, String startSqlTableName, String targetSqlTableName,
            boolean isList, int stepIndexBase, boolean endsChain) {
        var pathArg = refApplication.getArgument(ARG_PATH);
        Object pathValue = pathArg != null ? pathArg.getValue() : null;
        List<?> elements = pathValue instanceof List<?> l ? l
            : pathValue != null ? List.of(pathValue) : List.of();
        if (elements.stream().noneMatch(v -> v instanceof Map<?, ?>)) {
            return new ChainSegment(List.of(),
                "an @reference application composing a routine chain must carry at least one "
                + "path element — hops out of a routine result cannot be inferred");
        }
        var resolvedElements = new ArrayList<JoinStep>();
        var errors = new ArrayList<String>();
        resolvePathElements(elements, fieldName, startSqlTableName, targetSqlTableName, isList,
            stepIndexBase, endsChain, resolvedElements, errors);
        if (!errors.isEmpty()) {
            return new ChainSegment(List.of(), String.join("; ", errors));
        }
        return new ChainSegment(List.copyOf(resolvedElements), null);
    }

    /**
     * Computes the Check 1 verdict: does the {@code @reference} path's terminal hop land on
     * the field return type's {@code @table}? Reads the already-resolved terminal
     * {@link JoinStep.HasTargetTable#targetTable()} rather than re-deriving the hop kind from the
     * directive element.
     *
     * <ul>
     *   <li>FK-derived {@link JoinStep.Hop} — {@link TerminalTargetVerdict.Mismatch} when the resolved
     *       target table is not the same jOOQ table as {@code returnTableRef} (class-identity
     *       compare via {@link TableRef#denotesSameTableAs}), else {@link TerminalTargetVerdict.Match}.
     *       The resolved {@code targetTable} encodes
     *       "where this hop lands" for both terminal {@code {table:}} and terminal {@code {key:}}
     *       elements, so this single comparison subsumes both author forms.</li>
     *   <li>Condition-join {@link JoinStep.Hop} — {@link TerminalTargetVerdict.Match} by
     *       construction: this gate returns {@link TerminalTargetVerdict.NotApplicable} unless
     *       {@code returnTableRef} is non-null, and every call site passing one passes that same
     *       table's name as the declared target, which {@code resolveConditionJoinTarget} prefers.
     *       So wherever this arm compares, the declared target is what built the hop and the
     *       comparison is tautological. The hop's method parameters are validated by Check 2,
     *       not here.</li>
     *   <li>{@link JoinStep.LiftedHop} — unreachable; {@code @reference} path parsing never
     *       produces a {@code LiftedHop} (single-hop terminal only, from the {@code @sourceRow}
     *       leaf-PK arm, which passes a null start and so never reaches this gate).</li>
     * </ul>
     *
     * <p>Returns {@link TerminalTargetVerdict.NotApplicable} when {@code startSqlTableName} or
     * {@code returnTableRef} is null, or the path is empty: the check fires only for the
     * inline/split output projection off a table-backed parent. The {@code @sourceRow} composition
     * (null start) and input-field sites (null return table) are excluded — their paths are not the
     * {@code $project(terminalAlias)} projection this invariant protects.
     *
     * <p>The FK-derived arm compares the terminal hop's target against {@code returnTableRef}
     * by jOOQ table-class identity ({@link TableRef#denotesSameTableAs}), not the verbatim
     * {@code returnSqlTableName} echo. Both sides are catalog-constructed, so a schema-qualified
     * return {@code @table} (e.g. {@code multischema_a.widget}) matches the hop's unqualified
     * canonical name instead of spuriously reporting {@code Mismatch}. {@code returnSqlTableName}
     * stays the author's verbatim echo and is used only to render the {@code Mismatch} message.
     */
    private TerminalTargetVerdict computeTerminalTargetVerdict(
            List<JoinStep> resolvedElements, String fieldName,
            String startSqlTableName, String returnSqlTableName, TableRef returnTableRef) {
        if (startSqlTableName == null || returnTableRef == null || resolvedElements.isEmpty()) {
            return new TerminalTargetVerdict.NotApplicable();
        }
        JoinStep terminal = resolvedElements.getLast();
        return switch (terminal) {
            case JoinStep.Hop hop -> switch (hop.on()) {
                case On.ColumnPairs ignored -> hop.targetTable().denotesSameTableAs(returnTableRef)
                    ? new TerminalTargetVerdict.Match()
                    : new TerminalTargetVerdict.Mismatch(fieldName, hop.targetTable().tableName(), returnSqlTableName);
                // Match by construction: wherever this gate compares at all, the declared target
                // was available and resolveConditionJoinTarget prefers it, so it built this hop.
                case On.Predicate ignored -> new TerminalTargetVerdict.Match();
                // @reference path parsing never mints a lateral routine hop; routine
                // chains are landed by FieldBuilder's chain interception, whose terminus
                // invariant lives in FieldBuilder.routineChainVerdict.
                case On.Lateral ignored -> throw new IllegalStateException(
                    "On.Lateral is never produced by @reference path parsing; routine-node "
                    + "terminus checking lives in FieldBuilder.routineChainVerdict, not this gate.");
            };
        };
    }

    /**
     * Builds an FK-derived {@link JoinStep.Hop} for a foreign key that connects {@code sourceSqlName} to some
     * other table, or surfaces the catalog-failure shape when one of the resolution inputs
     * (endpoint table, FK constraint name) is missing. Traversal direction is inferred from which
     * side of the FK the source name touches (case-insensitive). The step alias follows the
     * explicit-path convention, {@code fieldName + "_" + stepIndex}, so inferred and explicit
     * position-0 steps produce record-equivalent hop values for the same shape.
     *
     * <p>{@code sourceSqlName} must be non-null; callers gate inference on that precondition.
     * {@code whereFilter} is {@code null} for pure inference; the {@code {table:}} and
     * {@code {key:}} branches in {@link #parsePathElement} may pass a resolved
     * {@link JoinConditionRef} when the element carries a {@code condition:} sub-argument.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@link FkJoinResolution.Resolved} when both endpoint tables and the FK
     *       resolve through the catalog;</li>
     *   <li>{@link FkJoinResolution.UnknownTable} (defensive-only, always {@code NotInCatalog})
     *       when an endpoint's jOOQ class is not in the catalog, a catalog-vs-FK mismatch. Both
     *       endpoints are resolved by class identity off the FK, so this never fires on bare-name
     *       ambiguity; author-facing source-membership is validated upstream (the
     *       {@link JooqCatalog#foreignKeyTouchesTable} check in {@link #parsePathElement}, and by
     *       construction on the shim / {@code NodeIdLeafResolver} routes);</li>
     *   <li>{@link FkJoinResolution.UnknownForeignKey} when the FK instance is absent from its
     *       holder schema's {@code Keys} class (also defensive).</li>
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
            int stepIndex, JoinConditionRef whereFilter, boolean selfRefFkOnSource) {
        boolean fkOnSource = catalog.foreignKeyOnSource(f, sourceSqlName, selfRefFkOnSource);
        // Both endpoints are resolved by jOOQ class identity off the FK object, never by bare
        // SQL name. The FK already pins the exact target and origin Table classes, so two schemas
        // sharing a bare table name cannot yield Ambiguous here, and the orientation (fkOnSource,
        // itself identity-based) picks which endpoint is the target.
        var targetJooq = fkOnSource ? f.getKey().getTable() : f.getTable();
        var originJooq = fkOnSource ? f.getTable() : f.getKey().getTable();

        var fkResolution = catalog.findForeignKeyRef(f);
        if (!(fkResolution instanceof JooqCatalog.ForeignKeyResolution.Resolved fkResolved)) {
            return new FkJoinResolution.UnknownForeignKey(f.getName());
        }

        var targetEntry = catalog.findTableByClass(targetJooq.getClass());
        if (targetEntry.isEmpty()) {
            return new FkJoinResolution.UnknownTable(targetJooq.getName(), new JooqCatalog.TableResolution.NotInCatalog());
        }
        var originEntry = catalog.findTableByClass(originJooq.getClass());
        if (originEntry.isEmpty()) {
            return new FkJoinResolution.UnknownTable(sourceSqlName, new JooqCatalog.TableResolution.NotInCatalog());
        }

        // Target requested name is the endpoint's own jOOQ name; origin keeps sourceSqlName so the
        // case-preserved directive form still flows into error messages.
        TableRef targetTable = targetEntry.get().toTableRef(targetJooq.getName());
        TableRef originTable = originEntry.get().toTableRef(sourceSqlName);
        List<JoinSlot.FkSlot> slots = resolveFkSlots(f, fkOnSource);
        String alias = fieldName + "_" + stepIndex;
        return new FkJoinResolution.Resolved(new JoinStep.Hop(
            new TableExpr.Catalog(targetTable),
            new On.ColumnPairs(new On.Keying.ForeignKey(fkResolved.ref()), slots),
            originTable, whereFilter, alias));
    }

    /**
     * The FK-orientation-and-pairing core, shared by {@link #synthesizeFkJoin} (the join path) and the
     * record-population FK resolver ({@link #resolveRecordFkTargetColumns}). Given a catalog
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
     * NOT pair positionally with {@code getFields()}. Zipping the two non-parallel lists silently
     * mis-pairs slots: {@code Field<X>.eq(Field<Y>)} compile errors in generated JOIN ON
     * predicates when the FK column types are heterogeneous, and values decoded into the wrong
     * record columns. See {@code SynthesizeFkJoinReorderedKeysTest}.
     *
     * @param fkOnSource the FK orientation, decided once by the caller via
     *     {@link JooqCatalog#foreignKeyOnSource}: {@code true} when the source table is the FK-child
     *     (referencing) side, {@code false} when it is the referenced-key side. The orientation
     *     predicate lives only in {@code JooqCatalog.foreignKeyOnSource}; this method never
     *     recomputes it from raw endpoint names, so a schema-qualified source cannot mis-orient
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
     * Builds the name-matched-key hop out of an FK-less node (a routine result table):
     * the keying rule for nodes without FK metadata. The target's primary key columns are
     * matched by SQL name (case-insensitive) against the previous node's columns — the
     * result-columns-expose-key-columns-by-name build check — and each match becomes a
     * {@link JoinSlot.FkSlot} (source side on the previous node, target side on the target's
     * key), keyed as {@link On.Keying.NameMatchedKey}. Errors accumulate in {@code errors}
     * exactly like the sibling {@code parsePathElement} branches:
     *
     * <ul>
     *   <li>target or source unresolved — the ordinary unknown-table diagnostics;</li>
     *   <li>target has no primary key — nothing to name-match; the fix is a
     *       {@code condition:} element;</li>
     *   <li>a target key column absent by name from the previous node — candidate hint over the
     *       previous node's columns, plus the two fixes (expose the column from the routine, or
     *       join on a {@code condition:}).</li>
     * </ul>
     *
     * <p>Matching is PK-only; {@code condition:} is the escape hatch.
     */
    private void synthesizeNameMatchedJoin(String targetTableName, String currentSourceSqlName,
            String fieldName, int stepIndex, JoinConditionRef whereFilter,
            List<JoinStep> out, List<String> errors) {
        var targetResolution = catalog.findTable(targetTableName);
        if (!(targetResolution instanceof JooqCatalog.TableResolution.Resolved targetResolved)) {
            errors.add(unknownTableRejection(targetResolution, targetTableName).message());
            return;
        }
        var sourceResolution = catalog.findTable(currentSourceSqlName);
        if (!(sourceResolution instanceof JooqCatalog.TableResolution.Resolved sourceResolved)) {
            errors.add(unknownTableRejection(sourceResolution, currentSourceSqlName).message());
            return;
        }
        TableRef targetTable = targetResolved.entry().toTableRef(targetTableName);
        TableRef sourceTable = sourceResolved.entry().toTableRef(currentSourceSqlName);
        var targetPk = targetResolved.entry().table().getPrimaryKey();
        if (targetPk == null || targetTable.primaryKeyColumns().isEmpty()) {
            errors.add("cannot key the hop from '" + currentSourceSqlName + "' (a routine result, "
                + "which carries no FK metadata) to '" + targetTableName + "' — the target has no "
                + "primary key to name-match; join on an explicit predicate via a 'condition:' element");
            return;
        }
        var slots = new ArrayList<JoinSlot.FkSlot>(targetTable.primaryKeyColumns().size());
        for (ColumnRef keyCol : targetTable.primaryKeyColumns()) {
            var sourceCol = sourceTable.allColumns().stream()
                .filter(c -> c.sqlName().equalsIgnoreCase(keyCol.sqlName()))
                .findFirst();
            if (sourceCol.isEmpty()) {
                errors.add("cannot key the hop from '" + currentSourceSqlName + "' to '"
                    + targetTableName + "' by name-match — the target's primary key column '"
                    + keyCol.sqlName() + "' is not exposed by name on '" + currentSourceSqlName + "'"
                    + candidateHint(keyCol.sqlName(),
                        sourceTable.allColumns().stream().map(ColumnRef::sqlName).toList())
                    + "; expose the key column from the routine, or join on an explicit predicate "
                    + "via a 'condition:' element");
                return;
            }
            slots.add(new JoinSlot.FkSlot(sourceCol.get(), keyCol));
        }
        out.add(new JoinStep.Hop(
            new TableExpr.Catalog(targetTable),
            new On.ColumnPairs(new On.Keying.NameMatchedKey(targetPk.getName()), slots),
            sourceTable, whereFilter, fieldName + "_" + stepIndex));
    }

    /**
     * Sub-taxonomy of outcomes for {@link #synthesizeFkJoin}: a typed switch over the
     * catalog-failure shapes
     * an FK-join construction can hit. Diagnostic builders read the carried failure data
     * directly: {@link UnknownTable#failure} routes through {@link #unknownTableRejection};
     * {@link UnknownForeignKey#fkName} routes through {@link #unknownForeignKeyRejection}.
     */
    public sealed interface FkJoinResolution {
        /**
         * Both endpoint tables and the FK name resolved; the FK-derived {@link JoinStep.Hop}
         * (an {@link On.ColumnPairs} join) is ready. The compact constructor enforces the
         * FK-derived shape so consumers read {@link #pairs()} without re-asserting it.
         */
        record Resolved(JoinStep.Hop hop) implements FkJoinResolution {
            public Resolved {
                if (!(hop.on() instanceof On.ColumnPairs)) {
                    throw new IllegalArgumentException(
                        "FkJoinResolution.Resolved carries an FK-derived hop; its on must be "
                        + "On.ColumnPairs, got " + hop.on().getClass().getSimpleName());
                }
            }

            /** The FK-derived join pairs the compact constructor guarantees. */
            public On.ColumnPairs pairs() {
                return (On.ColumnPairs) hop.on();
            }
        }

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
         * Project to {@link Optional}{@code <JoinStep.Hop>} for callers that ignore the failure
         * sub-taxonomy. Diagnostic-bearing callers must switch on the variant instead.
         */
        default Optional<JoinStep.Hop> asHop() {
            return this instanceof Resolved r ? Optional.of(r.hop()) : Optional.empty();
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
            // Split an optional leading schema qualifier ("multischema_a.note_event_fk") off the
            // author value; a malformed (stray-dot) value falls back to unqualified, degrading to the
            // same NotInCatalog rejection it produced before the grammar existed.
            var qfk = JooqCatalog.parseQualifiedForeignKeyName(keyName.get())
                .orElseGet(() -> new JooqCatalog.QualifiedForeignKeyName(Optional.empty(), keyName.get()));
            // Scope the FK-name lookup by the qualifier (hard, stated intent) and the current source
            // (soft, derived context) so a constraint name colliding across schemas resolves to this
            // position's table; a genuine unresolved collision surfaces as a typed Ambiguous
            // rejection, not a silent hit.
            var fkLookup = catalog.findForeignKey(qfk.name(), currentSourceSqlName, qfk.schema().orElse(null));
            if (fkLookup instanceof JooqCatalog.ForeignKeyLookup.NotInCatalog) {
                errors.add("key '" + keyName.get() + "' could not be resolved in the jOOQ catalog"
                    + candidateHint(keyName.get(), fkCandidateNames(currentSourceSqlName, keyName.get())));
                return;
            }
            if (fkLookup instanceof JooqCatalog.ForeignKeyLookup.Ambiguous amb) {
                errors.add(ambiguousForeignKeyRejection(keyName.get(), amb.schemas()).message());
                return;
            }
            var f = ((JooqCatalog.ForeignKeyLookup.Resolved) fkLookup).fk();
            String fkSideTable  = f.getTable().getName();
            var connectionRejection = foreignKeyConnectionRejection(f, currentSourceSqlName);
            if (connectionRejection.isPresent()) {
                errors.add(connectionRejection.get());
                return;
            }
            String effectiveSourceSqlName = currentSourceSqlName != null ? currentSourceSqlName : fkSideTable;
            JoinConditionRef whereFilter = null;
            if (hasCondition) {
                Map<String, Object> condMap = asMap(conditionRaw);
                switch (resolveConditionRef(condMap)) {
                    case ConditionResolution.Failed fail -> { errors.add(fail.message()); return; }
                    case ConditionResolution.Unresolved u -> {
                        errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
                        return;
                    }
                    case ConditionResolution.Resolved r -> whereFilter = new JoinConditionRef(r.ref());
                }
            }
            var keyResolution = synthesizeFkJoin(f, effectiveSourceSqlName, fieldName, stepIndex, whereFilter, /*selfRefFkOnSource=*/!isList);
            switch (keyResolution) {
                case FkJoinResolution.Resolved r -> {
                    out.add(r.hop());
                    validateWhereFilterParamTables(r.hop(), errors);
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
            JoinConditionRef whereFilter = null;
            if (hasCondition) {
                Map<String, Object> condMap = asMap(conditionRaw);
                switch (resolveConditionRef(condMap)) {
                    case ConditionResolution.Failed fail -> { errors.add(fail.message()); return; }
                    case ConditionResolution.Unresolved u -> {
                        errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
                        return;
                    }
                    case ConditionResolution.Resolved r -> whereFilter = new JoinConditionRef(r.ref());
                }
            }
            // A hop out of a routine result keys by the name-matched target key — the
            // result table carries no FK metadata, so the FK-count machinery below can never
            // resolve it. The gate is a catalog fact of the current source node, not caller
            // plumbing; ordinary tables never take this branch.
            if (catalog.isTableValuedFunction(currentSourceSqlName)) {
                synthesizeNameMatchedJoin(tableName.get(), currentSourceSqlName, fieldName,
                    stepIndex, whereFilter, out, errors);
                return;
            }
            var fks = catalog.findForeignKeysBetweenTables(currentSourceSqlName, tableName.get());
            if (fks.size() != 1) {
                errors.add(fkCountMessage(currentSourceSqlName, tableName.get(), fks, /*directiveAbsent=*/false));
                return;
            }
            var tableStepResolution = synthesizeFkJoin(fks.get(0), currentSourceSqlName, fieldName, stepIndex, whereFilter, /*selfRefFkOnSource=*/!isList);
            switch (tableStepResolution) {
                case FkJoinResolution.Resolved r -> {
                    out.add(r.hop());
                    validateWhereFilterParamTables(r.hop(), errors);
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
            switch (resolveConditionRef(condMap)) {
                case ConditionResolution.Failed fail -> errors.add(fail.message());
                case ConditionResolution.Unresolved u ->
                    errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
                case ConditionResolution.Resolved cr -> {
                    // The positional rule stays here, at the call site: `declaredTarget` is *this
                    // hop's* declared target, and only a chain-ending element has one. Handing it
                    // to every element uniformly would resolve an intermediate condition hop to
                    // the carrier field's return table instead of reflecting on the method's
                    // second parameter.
                    var targetResolution = resolveConditionJoinTarget(cr.ref(),
                        isTerminal ? declaredTargetRef(terminalTargetSqlName) : null);
                    switch (targetResolution) {
                        case ConditionJoinTargetResolution.Resolved r -> {
                            // originTable is kept mechanically on every hop (pre-resolved over
                            // re-derived); null when the source is not table-backed.
                            TableRef conditionOrigin = null;
                            if (currentSourceSqlName != null
                                && catalog.findTable(currentSourceSqlName)
                                    instanceof JooqCatalog.TableResolution.Resolved originResolved) {
                                conditionOrigin = originResolved.entry().toTableRef(currentSourceSqlName);
                            }
                            out.add(new JoinStep.Hop(
                                new TableExpr.Catalog(r.target()),
                                new On.Predicate(new JoinConditionRef(cr.ref())),
                                conditionOrigin, null, alias));
                            // Check 2: the ON-clause method is called method(sourceAlias, targetAlias).
                            // Source is the table entering this hop; target is the resolved
                            // condition-join target, whichever source resolved it. Thread the
                            // resolved TableRefs: conditionOrigin is null when the source is not
                            // table-backed (the existing skip), r.target() is already a TableRef.
                            validateConditionParamTables(cr.ref(), conditionOrigin, r.target(), errors);
                        }
                        case ConditionJoinTargetResolution.AuthorError e -> errors.add(e.message());
                    }
                }
            }
            return;
        }
        errors.add("path element has neither 'key', 'table', nor 'condition'");
    }

    /**
     * Result of {@link #resolveConditionJoinTarget}: either a fully-resolved {@link TableRef} or
     * an actionable {@code AUTHOR_ERROR} message. {@link Resolved} feeds the
     * {@link TableExpr.Catalog} compact constructor's non-null contract directly;
     * {@link AuthorError} routes through the
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
     * field given its already-built joinPath. {@code OnFkSlots} for a filter-less FK first hop
     * (an {@code On.ColumnPairs} {@link JoinStep.Hop});
     * {@code OnParentJoin} (the parent-anchor arm) when the first hop is a condition method
     * <em>or</em> carries a hop-0 {@code filter()}; {@code OnLateralArgs} for a lateral
     * routine head. The grain follows the arm via
     * {@link no.sikt.graphitron.rewrite.model.ParentCorrelation#parentKeyColumns()}, so this one
     * choice fixes both correlation topology and batch grain.
     *
     * <p>Returns an empty {@link ParentCorrelationResolution.Resolved} (wrapping {@code null})
     * for an empty joinPath — the standalone-lookup shape, where the carrier needs no parent
     * correlation. The carrier-side invariant pinned by
     * {@link no.sikt.graphitron.rewrite.model.ParentCorrelation#checkCarrierInvariant} reads
     * this case as "empty joinPath → null correlation".
     *
     * <p>The parent-anchor arm requires a non-null {@code parentTable} to anchor the parent row
     * the condition method or hop-0 filter reads. Pass it when the carrier sits on a table-backed
     * parent; for class-backed parents (record / service shapes) pass {@code null} and the helper
     * routes a condition-join or filter-carrying first hop to
     * {@link ParentCorrelationResolution.AuthorError} rather than fabricating a parent anchor that
     * does not exist.
     */
    ParentCorrelationResolution buildParentCorrelation(
            List<JoinStep> joinPath, TableRef parentTable) {
        if (joinPath.isEmpty()) {
            return new ParentCorrelationResolution.Resolved(null);
        }
        // Exhaustive over the step-0 join identity (every @reference-parsed step is a Hop;
        // the pre-keyed lifted shape never reaches here — its carriers hold an empty joinPath
        // plus ParentCorrelation.OnLiftedSlots directly). A filter-less FK head mirrors to
        // OnFkSlots and a lateral head to OnLateralArgs; a condition-join head OR any hop-0 filter
        // lands the parent-anchor arm (OnParentJoin), because a hop-0 filter reads the parent row
        // and so needs both the parent-PK grain and a parent alias to bind its source parameter.
        // A new On arm is a compile error here rather than a runtime throw in a constructor.
        return switch (joinPath.get(0)) {
            case JoinStep.Hop hop -> switch (hop.on()) {
                case On.ColumnPairs ignored -> {
                    if (hop.filter() == null) {
                        yield new ParentCorrelationResolution.Resolved(
                            new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnFkSlots(hop));
                    }
                    // A hop-0 filter reads the parent row, so it lands the parent-anchor arm
                    // regardless of the FK keying. A record / service parent has no catalog table
                    // to anchor the filter's source parameter — reject with the escape hatch.
                    if (parentTable == null) {
                        yield new ParentCorrelationResolution.AuthorError(
                            "hop-0 `condition:` filter on a `@reference` path whose parent row is "
                            + "not a catalog table: the filter's source parameter has no parent "
                            + "`@table` alias to bind. Move the `condition:` to a later hop, express "
                            + "it on the terminal `@condition` surface, or give the carrier field's "
                            + "parent type an `@table(name: …)` binding.");
                    }
                    yield new ParentCorrelationResolution.Resolved(
                        new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnParentJoin(
                            hop, parentTable));
                }
                case On.Predicate ignored -> {
                    if (parentTable == null) {
                        yield new ParentCorrelationResolution.AuthorError(
                            "condition-only first hop on `@reference` path with no parent `@table` "
                            + "binding: the parser cannot anchor the condition method's first argument. "
                            + "Add `@table(name: …)` to the carrier field's parent type, or rewrite the "
                            + "path to use `{table:}` or `{key:}` for the first hop.");
                    }
                    yield new ParentCorrelationResolution.Resolved(
                        new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnParentJoin(
                            hop, parentTable));
                }
                // A lateral routine node at step 0 correlates through its call arguments
                // (the SourceColumn bindings render the parent columns inside the call), so the
                // step-0 WHERE contributes nothing.
                case On.Lateral ignored -> new ParentCorrelationResolution.Resolved(
                    new no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLateralArgs(hop));
            };
        };
    }

    /**
     * The declared target a chain-ending path element may read: the carrier field's return-type
     * {@code @table} name resolved to a {@link TableRef}, or {@code null} when the site has no
     * such binding (every filter site) or the name is not in the catalog. Nullable rather than
     * {@link Optional} because it feeds {@link #resolveConditionJoinTarget}'s nullable parameter,
     * whose absent case is a resolution route rather than a failure.
     */
    private TableRef declaredTargetRef(String targetSqlTableName) {
        if (targetSqlTableName == null) return null;
        return catalog.findTable(targetSqlTableName).asEntry()
            .map(e -> e.toTableRef(targetSqlTableName))
            .orElse(null);
    }

    /**
     * Resolves the target table for a {@code {condition:}}-only path element. One rule, keyed on
     * the source available rather than on the hop's position: prefer {@code declaredTarget} (the
     * carrier field's return-type {@code @table} binding, which only a chain-ending element has,
     * so the caller passes {@code null} for every other element), otherwise reflect on the
     * condition method's second parameter type via {@link JooqCatalog#findTableByClass}. The
     * same authored element therefore resolves the same way wherever it is read from: a filter
     * site, which never carries a declared target, always resolves through the method signature.
     * Unresolvable cases surface as {@link ConditionJoinTargetResolution.AuthorError};
     * {@link TableExpr.Catalog}'s non-null table guard (behind {@link JoinStep.Hop}'s non-null
     * target check) is the structural safety net for pre-resolution.
     *
     * <p>The preference direction is load-bearing rather than arbitrary. Preferring the declared
     * target is what keeps a concrete second parameter that disagrees with it a
     * {@link #validateConditionParamTables} finding, an author-facing type mismatch on a hop that
     * did resolve, instead of collapsing into a resolution failure.
     *
     * <p>Wildcard parameter types ({@code Table<?>}) resolve nothing, so they are tolerated only
     * where a declared target answers the question; reflection requires a concrete generated jOOQ
     * table class.
     *
     * <p>The slot's catalog answer is read off {@link ParamSource.Table#slot()}, decided once in
     * {@code ServiceCatalog} at reflection time. Where the {@code @condition} names an admitted set
     * of same-named declarations, the slot's {@link ParamSource.Table.TableSlot.Bound} arm carries
     * one {@link TableRef} per declaration and this rung demands they agree. That reduction is
     * graphitron's and not javac's on purpose: the hop target names the joined table in the emitted
     * {@code EXISTS}, decided here at classification time, so there is no consumer call site to
     * defer the choice to. Agreement is demanded exactly where javac cannot dispatch. A set that
     * intends a different join target per branch is asking for per-branch join topology, which is a
     * join-shape feature rather than overload admission, and it rejects through the same path.
     */
    private ConditionJoinTargetResolution resolveConditionJoinTarget(
            MethodRef methodRef, TableRef declaredTarget) {
        if (declaredTarget != null) {
            return new ConditionJoinTargetResolution.Resolved(declaredTarget);
        }
        // The one sentence every rejection below shares: what the parser is reading, and why.
        String source = "no return-type `@table` binding is available for this hop, so the target "
            + "is read from the method's second parameter, which must be a concrete generated "
            + "jOOQ table class";
        var params = methodRef.params();
        if (params.size() < 2) {
            return new ConditionJoinTargetResolution.AuthorError(
                "`@condition` method '" + methodRef.className() + "." + methodRef.methodName()
                + "' has fewer than two parameters; " + source + ". Change the method signature "
                + "to (srcTable, tgtTable) with concrete jOOQ table types, or rewrite the path "
                + "to use `{table:}` or `{key:}` for this hop.");
        }
        var p1 = params.get(1);
        if (!(p1.source() instanceof ParamSource.Table table)) {
            // Not a table slot at all (a scalar second parameter): nothing in the catalog answers
            // for it, which is the same no-resolution the concrete non-table case has always been.
            return unresolvedConditionTarget(methodRef, p1.typeName(), source);
        }
        return switch (table.slot()) {
            case ParamSource.Table.TableSlot.Wildcard ignored ->
                new ConditionJoinTargetResolution.AuthorError(
                    "`@condition` method '" + methodRef.className() + "." + methodRef.methodName()
                    + "' has wildcard target parameter `Table<?>`; " + source + ". Change the second "
                    + "parameter to the concrete jOOQ table type, or rewrite the path to use "
                    + "`{table:}` or `{key:}` for this hop.");
            case ParamSource.Table.TableSlot.Unresolved unresolved ->
                unresolvedConditionTarget(methodRef, unresolved.typeName(), source);
            case ParamSource.Table.TableSlot.Bound bound -> bound.agreedTable()
                .<ConditionJoinTargetResolution>map(ConditionJoinTargetResolution.Resolved::new)
                .orElseGet(() -> new ConditionJoinTargetResolution.AuthorError(
                    "`@condition` method '" + methodRef.className() + "." + methodRef.methodName()
                    + "' is declared " + bound.tables().size() + " times with second parameters"
                    + " naming different tables ("
                    + bound.tables().stream()
                        .map(ParamSource.Table.TableSlot.Bound.BoundTable::qualifiedName)
                        .collect(java.util.stream.Collectors.joining(", "))
                    + "); " + source + ", and the hop joins one table, so the declarations must"
                    + " agree on it. Give every declaration the same second parameter type, or"
                    + " rewrite the path to use `{table:}` or `{key:}` for this hop."));
        };
    }

    /**
     * The shared "second parameter names no generated table" refusal: the fall-through both a
     * concrete non-catalog table type and a non-table second parameter reach.
     */
    private static ConditionJoinTargetResolution unresolvedConditionTarget(
            MethodRef methodRef, String typeName, String source) {
        return new ConditionJoinTargetResolution.AuthorError(
            "`@condition` method '" + methodRef.className() + "." + methodRef.methodName()
            + "' second parameter type '" + typeName + "' does not resolve to a generated jOOQ "
            + "table class; " + source + ". Change the second parameter to a concrete jOOQ table "
            + "type, or rewrite the path to use `{table:}` or `{key:}` for this hop.");
    }

    /**
     * Check 2 for a {@link JoinStep.Hop#filter()}: the filter method is emitted as
     * {@code filter(sourceAlias, targetAlias)} by {@code JoinPathEmitter.emitTwoArgMethodCall},
     * with source = the hop's {@code originTable} and target = its {@code targetTable}, both
     * already resolved by {@code synthesizeFkJoin}. A no-op when the hop carries no filter.
     */
    private void validateWhereFilterParamTables(JoinStep.Hop hop, List<String> errors) {
        if (hop.filter() == null) return;
        validateConditionParamTables(hop.filter().method(),
            hop.originTable(), hop.targetTable(), errors);
    }

    /**
     * Check 2: a two-argument condition method the path emits is called positionally as
     * {@code method(sourceAlias, targetAlias)}. When the author <em>concretely</em> types a table
     * parameter (e.g. {@code aCondition(NotB src, C tgt)}), it must agree with the table the
     * emitter will hand it, or the generated source fails javac with an incompatible-types error
     * in a downstream consumer's build. This moves that failure to build time.
     *
     * <p>Parameter 0 is checked against the {@code source} table, parameter 1 against the
     * {@code target} table (both resolved {@link TableRef}s). Wildcard {@code Table<?>}
     * parameters (the idiomatic {@code (Table<?>, Table<?>)} shape) are unverifiable and accepted —
     * the same wildcard predicate {@link #resolveConditionJoinTarget} uses. A concrete parameter
     * type that does not resolve to a catalog table (a non-{@code Table} parameter, or a class
     * absent from the codegen loader) is likewise skipped: this check validates a constraint the
     * author opted into by naming a concrete jOOQ table, it imposes no new signature requirement.
     *
     * <p>Reads the slot facts the reflected {@link MethodRef} carries and the resolved source/target
     * tables already in scope at the call site; it never re-inspects the directive element nor
     * re-walks the path.
     */
    private void validateConditionParamTables(
            MethodRef method, TableRef source, TableRef target, List<String> errors) {
        var params = method.params();
        checkConcreteParamTable(method, params, 0, source, "source", errors);
        checkConcreteParamTable(method, params, 1, target, "target", errors);
    }

    /**
     * Validates one positional table parameter of a condition method against the table the emitter
     * will pass it. Skips when the position is out of range, the {@code expected} table is null
     * (unknown, e.g. a non-table-backed source), the position is not a table slot at all, the slot
     * is a wildcard {@code Table<?>}, or its concrete type resolves to no catalog table. Each of
     * those is the slot's own catalog answer, decided in {@code ServiceCatalog} at reflection time
     * (see {@link ParamSource.Table.TableSlot}) rather than re-decoded from a type-name string here.
     *
     * <p>Where the {@code @condition} names an admitted set of same-named declarations, the check is
     * per-anchor <em>applicability</em>: at least one declaration whose slot accepts this anchor, the
     * most-specific selection among the applicable ones being javac's at the emitted call site. A set
     * covering some anchors and not others is therefore this check's finding only where <em>no</em>
     * declaration covers the anchor.
     *
     * <p>The compare is jOOQ class identity via {@link TableRef#denotesSameTableAs}: both operands
     * are catalog-built refs, so schema-qualified {@code @table} echoes match their unqualified jOOQ
     * canonical names and same-named tables across schemas stay distinct.
     */
    private void checkConcreteParamTable(MethodRef method, List<MethodRef.Param> params,
            int index, TableRef expected, String role, List<String> errors) {
        if (expected == null || index >= params.size()) return;
        if (!(params.get(index).source() instanceof ParamSource.Table table)) return;
        // A wildcard slot accepts any aliased table and an unresolvable one maps to no catalog
        // entry; neither makes a claim this check could contradict. This check validates a
        // constraint the author opted into by naming a concrete jOOQ table.
        if (!(table.slot() instanceof ParamSource.Table.TableSlot.Bound bound)) return;
        if (bound.tableRefs().stream().anyMatch(t -> t.denotesSameTableAs(expected))) return;
        String declaredList = bound.tables().stream()
            .map(ParamSource.Table.TableSlot.Bound.BoundTable::qualifiedName)
            .collect(java.util.stream.Collectors.joining(", "));
        errors.add("condition method '" + method.className() + "." + method.methodName()
            + "' parameter " + index + " is typed for table "
            + (bound.tables().size() == 1 ? "'" + declaredList + "'" : "[" + declaredList + "]")
            + " but this hop's " + role + " table is '" + expected.tableName()
            + "'; the emitter passes the " + role + " alias positionally, so the concrete "
            + "parameter type must match (or use a wildcard `Table<?>`).");
    }

    /**
     * Result of {@link #resolveConditionRef}. Tri-state, compiler-enforced via an exhaustive
     * {@code switch} at each {@link #parsePathElement} caller.
     */
    private sealed interface ConditionResolution {
        /** The condition map resolved to a method. */
        record Resolved(MethodRef ref) implements ConditionResolution {}
        /** Resolution failed with an actionable {@code message} the caller surfaces directly. */
        record Failed(String message) implements ConditionResolution {}
        /** No class/method named and no reflective match; the caller emits its own generic message. */
        record Unresolved() implements ConditionResolution {}
    }

    /**
     * Resolves an {@code ExternalCodeReference} condition map to a {@link MethodRef} via
     * {@link ServiceCatalog#reflectTableMethod}. Returns one of {@link ConditionResolution}'s
     * three arms: {@code Resolved}, {@code Failed}, or {@code Unresolved}.
     *
     * <p>For path-step {@code @condition}, no GraphQL arguments are in scope, so the slot set
     * is empty and any non-empty {@code argMapping} fails through {@link
     * ArgBindingMap.Result.UnknownArgRef}. Parse-time errors from {@code argMapping} itself also
     * surface, with the path-step site context wrapped around the message.
     */
    private ConditionResolution resolveConditionRef(Map<String, Object> conditionMap) {
        String className = Optional.ofNullable(conditionMap.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(conditionMap.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null || methodName == null || svc == null) {
            return new ConditionResolution.Unresolved();
        }
        String rawArgMapping = Optional.ofNullable(conditionMap.get(ARG_ARGMAPPING)).map(Object::toString).orElse(null);
        var parsed = ArgBindingMap.parseArgMapping(rawArgMapping, ArgMappingSigil.Site.CONDITION);
        if (parsed instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new ConditionResolution.Failed(
                "path-step @condition: " + pe.message());
        }
        var segmentChains = ((ArgBindingMap.ParsedArgMapping.Ok) parsed).overrides();
        var bindingResult = ArgBindingMap.of(
            java.util.Map.<String, graphql.schema.GraphQLInputType>of(), segmentChains);
        // The two arms are kept apart here rather than read through Result.Failure: the slot map
        // is empty, so the shared message renders the available-argument list as [], and the
        // clause below is the only prose that says why.
        if (bindingResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            return new ConditionResolution.Failed(
                "path-step @condition: no GraphQL arguments are in scope at a path-step @condition; "
                + u.message());
        }
        if (bindingResult instanceof ArgBindingMap.Result.PathRejected p) {
            return new ConditionResolution.Failed(
                "path-step @condition: " + p.message());
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var result = svc.reflectTableMethod(className, methodName, argBindings, Set.of());
        return result.failed() ? new ConditionResolution.Unresolved() : new ConditionResolution.Resolved(result.ref());
    }

    private String extractConditionQualifiedName(Map<String, Object> conditionMap) {
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
     * <p>Shared by {@link FieldBuilder} and {@link TypeBuilder} via {@code ctx}.
     */
    ConditionDirective readConditionDirective(GraphQLDirectiveContainer container) {
        var dir = container.getAppliedDirective(DIR_CONDITION);
        if (dir == null) return null;
        var condArg = dir.getArgument(ARG_CONDITION);
        if (condArg == null || condArg.getValue() == null) return null;
        Map<String, Object> ref = asMap(condArg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null || methodName == null) return null;
        boolean override = argBoolean(container, DIR_CONDITION, ARG_OVERRIDE, false);
        List<String> ctxArgs = argStringList(container, DIR_CONDITION, ARG_CONTEXT_ARGUMENTS);
        String rawArgMapping = Optional.ofNullable(ref.get(ARG_ARGMAPPING)).map(Object::toString).orElse(null);
        var parsed = ArgBindingMap.parseArgMapping(rawArgMapping, ArgMappingSigil.Site.CONDITION);
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
     * and any declared {@code contextArguments}. Appends an {@link InputFieldConditionFailure} and
     * returns {@link Optional#empty()} on reflection failure — mirrors
     * {@code FieldBuilder.buildArgCondition}.
     */
    Optional<ArgConditionRef> buildInputFieldCondition(
            GraphQLInputObjectField field, String parentTypeName, String inputFieldName,
            List<InputFieldConditionFailure> failures) {
        var cond = readConditionDirective(field);
        if (cond == null) return Optional.empty();
        if (cond.argMappingError() != null) {
            // ConditionDirective.argMappingError is prose; wrap it at this boundary.
            failures.add(conditionFailure(field, parentTypeName, inputFieldName,
                untypedUpstream(cond.argMappingError())));
            return Optional.empty();
        }
        var bindingResult = ArgBindingMap.of(java.util.Map.of(field.getName(), field.getType()),
            cond.argMapping());
        if (bindingResult instanceof ArgBindingMap.Result.Failure f) {
            // Unprefixed, like the reflect arm below: the "@condition on input field X" context is
            // rendered by InputFieldConditionFailure#message, not at this site.
            failures.add(conditionFailure(field, parentTypeName, inputFieldName, untypedUpstream(f.message())));
            return Optional.empty();
        }
        var argBindings = ((ArgBindingMap.Result.Ok) bindingResult).map();
        var result = svc.reflectTableMethod(cond.className(), cond.methodName(),
            argBindings, Set.copyOf(cond.contextArguments()),
            java.util.Map.of(field.getName(), field.getType()));
        if (result.failed()) {
            // Kept unprefixed: the reflect arms are typed sub-seals whose prefixedWith is a
            // deliberate no-op, so the "@condition on input field X" context is the consumer's to
            // render (see InputFieldConditionFailure#message).
            failures.add(conditionFailure(field, parentTypeName, inputFieldName, result.rejection()));
            return Optional.empty();
        }
        var methodRef = result.ref();
        return Optional.of(new ArgConditionRef(
            new ConditionFilter(methodRef.className(), methodRef.methodName(), methodRef.params()),
            cond.override()));
    }

    /**
     * Mints one located diagnostic per input-field failure at the input field's own coordinate, and
     * returns how many were minted so the caller can state the consequence on the consuming
     * coordinate. This is what dissolves the fan-ins: five broken input fields used to render as
     * five names inside one string on the consuming field, reported at that field's location.
     *
     * <p>Nothing here carries the consuming coordinate, deliberately. Input fields resolve once per
     * consuming field, so one input type used by five mutations mints each fact five times; built
     * from the input field's own facts, those five are one value and collapse on
     * {@link #addDiagnostic}'s idempotence. Resolved against different tables the candidates differ,
     * the facts are genuinely different, and all of them survive.
     *
     * <p>{@code inputTypeName} is therefore the type declaring the failures this call mints, not the
     * type being consumed. The resolution failures are always this fold's own (the nesting branch
     * mints its nested ones under the nested type before returning a consequence), while the
     * condition accumulator spans the recursion and each entry names its own declaring type.
     */
    int mintInputFieldFailures(String inputTypeName,
            List<InputFieldResolution.Unresolved> failures,
            List<InputFieldConditionFailure> conditionFailures) {
        for (var u : failures) {
            mintInputFieldFailure(inputTypeName, u.fieldName(), u.location(), u.rejection());
        }
        for (var cf : conditionFailures) {
            // The declaring type, not this fold's: one accumulator spans the nesting recursion, so a
            // nested field's condition failure surfaces here carrying its own parent.
            mintInputFieldFailure(cf.parentTypeName(), cf.fieldName(), cf.location(), cf.rejection());
        }
        return failures.size() + conditionFailures.size();
    }

    private void mintInputFieldFailure(String inputTypeName, String fieldName,
            SourceLocation location, Rejection rejection) {
        String coordinate = inputTypeName + "." + fieldName;
        addDiagnostic(new ValidationError(coordinate,
            rejection.prefixedWith("Input field '" + coordinate + "': "), location));
    }

    private static InputFieldConditionFailure conditionFailure(
            GraphQLInputObjectField field, String parentTypeName, String inputFieldName,
            Rejection rejection) {
        return new InputFieldConditionFailure(parentTypeName, inputFieldName, locationOf(field), rejection);
    }

    /**
     * The single boundary wrap for input-field causes whose upstream still reports prose rather
     * than a {@link Rejection}: {@link #parsePath}'s {@code errorMessage}, {@link ConditionDirective}'s
     * {@code argMappingError}, and {@link ArgBindingMap}'s two prose-only result arms. Naming the wrap
     * keeps "this cause is not yet typed" in one identifiable place instead of leaving
     * {@link InputFieldResolution.Unresolved} polymorphic between prose and type; each call site
     * deletes when its upstream carrier is widened.
     */
    private static Rejection untypedUpstream(String prose) {
        return Rejection.structural(prose);
    }

    /**
     * Builds an {@link InputFieldResolution.Unresolved} at the failing field's own location. Every
     * producer in the input-field classifier goes through here, so no arm can reintroduce the
     * locationless carrier the fan-ins used to report at the consuming coordinate.
     */
    private static InputFieldResolution unresolved(
            GraphQLInputObjectField field, String name, Rejection rejection) {
        return new InputFieldResolution.Unresolved(name, locationOf(field), rejection);
    }

    // ===== Input-field classifier (shared between TypeBuilder and FieldBuilder) =====

    /**
     * Classifies a single {@link GraphQLInputObjectField} against {@code resolvedTable}, producing
     * an {@link InputFieldResolution}: either a fully classified {@link InputField} variant
     * (possibly with a {@code condition}) or an unresolved result with a diagnostic message.
     *
     * <p>Shared by {@link TypeBuilder} (type-build pass, {@code @table} inputs) and
     * {@link FieldBuilder} (argument-classify pass,
     * plain inputs): one decision tree for both.
     *
     * <p>Condition reflection failures append an {@link InputFieldConditionFailure} to
     * {@code conditionFailures} and leave the {@code condition} field empty — the field still
     * classifies as its structural variant. Column-miss and path-resolution failures return
     * {@link InputFieldResolution.Unresolved}.
     *
     * <p>{@code ctx} bundles the structural facts threaded through recursive descent that
     * a single field's local view cannot recover: {@link ClassifyContext#expandingTypes} guards
     * against circular plain-input nesting (callers start with {@link ClassifyContext#root()}),
     * and {@link ClassifyContext#enclosingOverride} threads the cascade flag.
     * The classifier's variant decisions do not branch on {@code enclosingOverride}
     * (column-miss uniformly lifts to {@link InputField.UnboundField}); the consumer's
     * cascade-aware switch reads the carrier plus its own call-site {@code enclosingOverride}.
     */
    InputFieldResolution classifyInputField(
            GraphQLInputObjectField field, String parentTypeName, TableRef resolvedTable,
            ClassifyContext ctx, List<InputFieldConditionFailure> conditionFailures) {
        var resolution = classifyInputFieldInternal(field, parentTypeName, resolvedTable, ctx, conditionFailures);
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
            ClassifyContext ctx, List<InputFieldConditionFailure> conditionFailures) {
        String name = field.getName();
        if (field.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return unresolved(field, name, Rejection.directiveConflict(List.of(DIR_NOT_GENERATED),
                "@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema."));
        }
        if (field.hasAppliedDirective(DIR_LOOKUP_KEY)) {
            return unresolved(field, name, Rejection.directiveConflict(List.of(DIR_LOOKUP_KEY),
                "@lookupKey on a mutation input field is no longer supported; "
                + "remove it (the field is a filter by default; the UPDATE SET/WHERE "
                + "partition is derived from the catalog by the walker). On Query-side "
                + "@table input args, move @lookupKey to the surrounding ARGUMENT_DEFINITION "
                + "instead."));
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
        // same-table → ColumnBackedField (filter by the parent's own key columns, arity 1..N);
        // FK-target.DirectFk → ColumnBackedReferenceField with joinPath;
        // FK-target.TranslatedFk / Rejected → Unresolved. The resolver also owns typeName
        // inference for bare @nodeId, so the singular and list branches see a single sealed
        // result rather than re-deriving the typeName independently.
        if ("ID".equals(typeName) && field.hasAppliedDirective(DIR_NODE_ID)) {
            // The ownership bit is read off the authored directive, not off the built
            // ArgConditionRef: a failing condition build empties the carrier by design, and what the
            // resolver is being told is what the author declared.
            var leafCondition = readConditionDirective(field);
            var resolved = nodeIdLeafResolver.resolve(field, name, resolvedTable,
                ctx.participant(), leafCondition != null && leafCondition.override());
            return inputFieldFromNodeIdResolved(
                resolved, parentTypeName, field, name, typeName, nonNull, list,
                resolvedTable, conditionFailures);
        }
        // @referenceFor is the per-participant path surface, and the decode rail is the only
        // consumer of one at this coordinate so far. The wording is on the axis rather than on the
        // directive: the plain-@reference input rail has the same expressibility gap and is the
        // natural place for the next arm, so nothing here should teach that only @nodeId fields may
        // carry @referenceFor.
        if (field.hasAppliedDirective(DIR_REFERENCE_FOR)) {
            return unresolved(field, name, Rejection.structural(
                "input field '" + parentTypeName + "." + name + "': @referenceFor states a"
                + " per-participant join path, and the only per-participant path an input field"
                + " resolves today is the one a @nodeId decode leaf walks. Add @nodeId(typeName:)"
                + " if this field carries an encoded node id, or state the path with @reference,"
                + " which applies uniformly."));
        }
        if (field.hasAppliedDirective(DIR_REFERENCE)) {
            // @reference is repeatable, so field-level applications compose the table
            // chain; order-composition has no meaning on an input field, so repetition here is
            // a conflict, not a chain.
            if (field.getAppliedDirectives(DIR_REFERENCE).size() > 1) {
                return unresolved(field, name, Rejection.structural(
                    "repeated @reference on an input field is not supported — ordered chain "
                    + "composition applies only to output field definitions; compose the chain "
                    + "on the field instead"));
            }
            var path = parsePath(field, name, resolvedTable.tableName(), null);
            // ParsedPath still reports prose; boundary-wrap it rather than widen that carrier here.
            if (path.hasError()) return unresolved(field, name, untypedUpstream(path.errorMessage()));
            return svc.resolveColumnForReference(columnName, path.elements(), resolvedTable)
                .<InputFieldResolution>map(col -> {
                    Optional<ArgConditionRef> cond = buildInputFieldCondition(field, parentTypeName, name, conditionFailures);
                    // Plain (non-@nodeId) @reference: the predicate fires against the resolved
                    // column. An empty path means the column is on the field's own table, so it
                    // binds Local; a non-empty path means `col` is the terminal column on a joined
                    // table, which is the carrier's own columns() and so binds Remote.
                    // selfReference=false: the self-FK fact (the all-SET routing) is decided only
                    // at the @nodeId discrimination site; a bare @reference is not a self-FK carrier.
                    return new InputFieldResolution.Resolved(new InputField.ColumnBackedReferenceField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        List.of(col), path.elements(),
                        path.elements().isEmpty()
                            ? new FilterBinding.Local(List.of(col))
                            : new FilterBinding.Remote(),
                        false, cond,
                        new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()));
                })
                .orElseGet(() -> unresolved(field, name, Rejection.unknownColumn(
                    "no column '" + columnName + "' reachable via @reference path",
                    columnName,
                    // The candidate space is the path's terminal table, which is where the author's
                    // column was looked for; the resolving table is only the walk's start.
                    catalog.columnJavaNamesOf(
                        svc.terminalTableForReference(path.elements(), resolvedTable).tableName()))));
        }
        // Nesting: field type is an input object. @table on it is deprecated and inert, so it does
        // not gate the descent; a nested @table grouping input flattens exactly as its
        // directiveless twin does rather than falling through to the column-lookup path below and
        // resolving as a column named after the nested type.
        var baseType = GraphQLTypeUtil.unwrapAll(type);
        if (baseType instanceof GraphQLInputObjectType nestedInputType) {
            if (ctx.isExpanding(typeName)) {
                return unresolved(field, name, Rejection.structural(
                    "circular input type reference detected while expanding '" + typeName + "'"));
            }
            // Read the field's own @condition override flag (cheap, no errors side effects)
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
                var res = classifyInputField(nested, typeName, resolvedTable, nestedCtx, conditionFailures);
                switch (res) {
                    case InputFieldResolution.Resolved r -> resolvedFields.add(r.field());
                    case InputFieldResolution.Unresolved u -> failures.add(u);
                }
            }
            if (!failures.isEmpty()) {
                // Each nested cause is minted at its own coordinate; this level states only the
                // consequence, so a nested defect no longer renders as quoted strings inside
                // quoted strings.
                int minted = mintInputFieldFailures(typeName, failures, List.of());
                return unresolved(field, name, Rejection.structural(
                    "nested input type '" + typeName + "' has " + minted
                    + " unresolvable field" + (minted == 1 ? "" : "s")));
            }
            Optional<ArgConditionRef> cond = buildInputFieldCondition(field, parentTypeName, name, conditionFailures);
            return new InputFieldResolution.Resolved(new InputField.NestingField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                List.copyOf(resolvedFields), cond));
        }
        String tableName = resolvedTable.tableName();
        // An input field naming its target's `Node.id` is that node id, implicitly — the same rule
        // the argument coordinate carries, at the coordinate that binds against a table rather than
        // a type. No return type name is threaded in here (this method's own `parentTypeName` is
        // the *input* type's name, and a mutation input has no node type in its SDL at all), so the
        // node is resolved through the by-table view: a singleton is the answer, and an ambiguous
        // table is a rejection naming the argument that settles it. This is not the table
        // reverse-lookup resolveDecodeHelperForType exists to avoid: that one discarded a type name
        // the call site was already holding, and here there is no name to discard, so the table is
        // the only source there has ever been.
        if ("ID".equals(typeName)
                && NODE_INTERFACE_ID_FIELD.equals(name)
                && !hasFieldDir
                && !field.hasAppliedDirective(DIR_NODE_ID)
                && !field.hasAppliedDirective(DIR_REFERENCE)) {
            var nodesOnTable = nodes.forTable(tableName);
            if (nodesOnTable.size() == 1) {
                NodeType node = nodesOnTable.get(0);
                // Ahead of the column lookup, so a real column of this name is a rejection rather
                // than a contest either reading wins. Identical answer at all three coordinates.
                var shadowed = catalog.findColumn(tableName, name);
                if (shadowed.isPresent()) {
                    return unresolved(field, name, rejectShadowedNodeId(
                        "input field '" + parentTypeName + "." + name + "'",
                        name, node, shadowed.get().sqlName()));
                }
                Optional<ArgConditionRef> nodeIdCond = buildInputFieldCondition(field, parentTypeName, name, conditionFailures);
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch(node.decodeMethod());
                return new InputFieldResolution.Resolved(new InputField.ColumnBackedField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    node.nodeKeyColumns(), nodeIdCond, extraction));
            }
            if (nodesOnTable.size() > 1) {
                return unresolved(field, name, Rejection.structural(
                    "input field '" + parentTypeName + "." + name + "' names the node id of table '"
                    + tableName + "', but that table backs multiple node types ("
                    + nodesOnTable.stream().map(NodeType::name).sorted().collect(java.util.stream.Collectors.joining(", "))
                    + "). Add `@nodeId(typeName: T)` to say which one."));
            }
            // No node backs the table: an `id` field here is an ordinary column, per the rule that
            // `ID` without `@nodeId` is a plain scalar. Falls through to the column lookup.
        }
        var colEntry = catalog.findColumn(tableName, columnName);
        if (colEntry.isPresent()) {
            var e = colEntry.get();
            Optional<ArgConditionRef> cond = buildInputFieldCondition(field, parentTypeName, name, conditionFailures);
            // @condition(override: true) means the explicit method owns the predicate entirely;
            // the resolved column is unused by construction, so the carrier is ConditionOwnedField
            // and the column is deliberately not recorded (both classification outcomes, column
            // resolved and column missing, mint the same carrier).
            if (cond.isPresent() && cond.get().override()) {
                return new InputFieldResolution.Resolved(new InputField.ConditionOwnedField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    cond.get()));
            }
            return new InputFieldResolution.Resolved(new InputField.ColumnBackedField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                List.of(new ColumnRef(e.sqlName(), e.javaName(), e.columnClass())), cond,
                new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()));
        }
        // Column miss. @condition(override: true) lifts to ConditionOwnedField (the method owns
        // the predicate; whether a column also resolved is not the carrier's fact). Otherwise the
        // field is genuinely unbound: with an authored @condition (override: false, required to
        // compose with an implicit predicate that has no column to bind) the malformed-shape fact
        // is minted here, keyed by this definition and the resolving table, unconditional on any
        // enclosing cascade; with no @condition the carrier is cascade-dependent and the
        // consumer's walk applies the use-keyed verdict.
        //
        // The malformed-shape mint reads the authored directive rather than the built
        // ArgConditionRef: a failing condition build empties the carrier's condition by design
        // (the reflection error rides the errors list), and the fact asserted is about the
        // authored shape.
        var conditionDirective = readConditionDirective(field);
        Optional<ArgConditionRef> unboundCond = Optional.empty();
        if (conditionDirective != null) {
            unboundCond = buildInputFieldCondition(field, parentTypeName, name, conditionFailures);
        }
        if (unboundCond.isPresent() && unboundCond.get().override()) {
            return new InputFieldResolution.Resolved(new InputField.ConditionOwnedField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                unboundCond.get()));
        }
        if (conditionDirective != null && !conditionDirective.override()) {
            // Typed as UnknownName so the attempted column and the Levenshtein candidates
            // survive for LSP fix-its, with the malformed-shape remedy in the summary.
            addDiagnostic(new ValidationError(
                parentTypeName + "." + name,
                Rejection.unknownColumn(
                    "Input field '" + parentTypeName + "." + name
                        + "': @condition(override: false) requires the implicit column predicate "
                        + "to compose, and no column resolves on table '" + tableName
                        + "'; either add a matching column, or set override: true so the "
                        + "condition method owns the WHERE predicate entirely",
                    columnName,
                    catalog.columnSqlNamesOf(tableName)),
                locationOf(field)));
        }
        return new InputFieldResolution.Resolved(new InputField.UnboundField(
            parentTypeName, name, locationOf(field), typeName, nonNull, list,
            unboundCond, columnName));
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
     * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch}: an
     * authored input-field {@code @nodeId} filter leaf throws a {@code GraphitronClientException}
     * on a malformed or wrong-type encoded id rather than silently dropping it to "no row matches".
     * This matches the argument-level filter leaves in {@link FieldBuilder#classifyArgument}, and
     * it is now the only failure mode the carrier admits.
     *
     * <p>An authored {@code @condition} on the leaf takes the same decode, installed on its bound
     * parameter through {@link ConditionResolver#installNodeIdDecode} before the arms run. The rule
     * is stated at the slot rather than per arm: on every one of them the value the method receives
     * is the decoded key, so the author-owned arm and the routed arms read the same kind of local
     * and a wire string never reaches developer code.
     */
    private InputFieldResolution inputFieldFromNodeIdResolved(
            NodeIdLeafResolver.Resolved resolved, String parentTypeName,
            GraphQLInputObjectField field, String name, String typeName,
            boolean nonNull, boolean list, TableRef resolvedTable,
            List<InputFieldConditionFailure> conditionFailures) {
        if (resolved instanceof NodeIdLeafResolver.Resolved.Rejected rejected) {
            return unresolved(field, name, rejected.rejection());
        }
        // Built once, ahead of the arms, because the decode installed on it is the same on every one
        // of them: a @nodeId slot's value is decoded before it leaves the generated glue, so an
        // authored parameter bound to this field receives the typed key rather than the wire string.
        Optional<ArgConditionRef> condition =
            buildInputFieldCondition(field, parentTypeName, name, conditionFailures);
        if (condition.isPresent()) {
            var install = ConditionResolver.installNodeIdDecode(condition.get().filter(),
                "input field '" + parentTypeName + "." + name + "'", name,
                FieldBuilder.decodeTargetOf(resolved), list);
            switch (install) {
                case ConditionResolver.DecodeInstall.Rejected r -> {
                    return unresolved(field, name, r.rejection());
                }
                case ConditionResolver.DecodeInstall.Ok ok -> condition =
                    Optional.of(new ArgConditionRef(ok.filter(), condition.get().override()));
            }
        }
        switch (resolved) {
            case NodeIdLeafResolver.Resolved.Rejected ignored -> throw new IllegalStateException(
                "unreachable: the rejected arm returns above, before the condition is built");
            case NodeIdLeafResolver.Resolved.AuthorOwnedPredicate ignored -> {
                // No route resolved and the leaf's own @condition(override: true) took the
                // predicate. Same carrier the column-miss arm mints for the same reason: the method
                // owns the whole WHERE contribution, so there is no implicit predicate for the
                // generator to bind and the carrier records no columns. The decode still happens, in
                // the glue, and rides the condition's own bound parameter. A condition build that
                // fails here leaves the field unresolved rather than silently dropping to an unbound
                // carrier the resolver has already ruled out.
                if (condition.isEmpty()) {
                    return unresolved(field, name, Rejection.structural(
                        "input field '" + parentTypeName + "." + name + "': @condition(override:"
                        + " true) owns this @nodeId leaf's predicate, but the condition method could"
                        + " not be resolved."));
                }
                return new InputFieldResolution.Resolved(new InputField.ConditionOwnedField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list, condition.get()));
            }
            case NodeIdLeafResolver.Resolved.SameTable st -> {
                // Authored input-field @nodeId filter throws on malformed/wrong-type ids.
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch(st.decodeMethod());
                return new InputFieldResolution.Resolved(new InputField.ColumnBackedField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    st.keyColumns(), condition, extraction));
            }
            case NodeIdLeafResolver.Resolved.FkTarget.DirectFk direct -> {
                // Authored input-field FK-target @nodeId filter throws on malformed/wrong-type ids.
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch(direct.decodeMethod());
                return new InputFieldResolution.Resolved(new InputField.ColumnBackedReferenceField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    direct.keyColumns(), direct.joinPath(),
                    new FilterBinding.Local(direct.liftedSourceColumns()),
                    direct.selfReference(), condition, extraction));
            }
            case NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk translated -> {
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch(translated.decodeMethod());
                // The FK targets columns other than the NodeType's key columns, so the decoded key
                // reaches the row only through the join: a Remote binding, lowered to the correlated
                // EXISTS the joined plain-@reference filter already uses. selfReference is false
                // because TranslatedFk records no self-FK fact and the read path needs none; the
                // slot's one reader is the UPDATE SET partition, which refuses a Remote binding
                // before reading it, so the value is unreachable rather than merely unused.
                return new InputFieldResolution.Resolved(new InputField.ColumnBackedReferenceField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    translated.keyColumns(), translated.joinPath(),
                    new FilterBinding.Remote(), false, condition, extraction));
            }
        }
    }

    /**
     * The one shadowing verdict, shared by every coordinate that can carry an implicit node-id
     * reading: output fields, input fields and arguments alike. A directive-less coordinate whose
     * name is the node's {@code id} over a table that also has a column of that name names two
     * different wire values, and the SDL does not choose between them, so the build refuses instead
     * of picking.
     *
     * <p>One method rather than three sibling messages on purpose. Shadowing is a single question,
     * and an author who learns the answer at one coordinate must not have to re-learn it at the
     * next; divergent wording would be the first step towards divergent semantics.
     *
     * <p>{@code coordinate} is the already-qualified label for the site ({@code field 'Baz.id'},
     * {@code input field 'DeleteBazInput.id'}), so the reader is told which of the three they are
     * looking at without the message changing shape. Pass it blank where the consuming site
     * already prefixes one, as the argument projection does; the label is then supplied once
     * rather than twice.
     *
     * <p>Prose rather than a structured repair: both remedies are directive insertions after the
     * coordinate's type, and graphql-java records a type node's start location but not its end, so
     * the insertion point is not derivable from source locations.
     */
    static Rejection rejectShadowedNodeId(String coordinate, String name, NodeType nodeType, String shadowedColumn) {
        String tableSqlName = nodeType.table().tableName();
        // Which half of the pair the author can actually act on differs with how the type became a
        // node: an inferred node points at the metadata, a declared one at the declaration.
        String because = nodeType.provenance().keyColumns() == no.sikt.graphitron.rewrite.model.NodeProvenance.Origin.METADATA
            ? "table '" + tableSqlName + "' has a column named '" + shadowedColumn
                + "' and also publishes node metadata"
            : "'" + nodeType.name() + "' is a node type over table '" + tableSqlName
                + "', which also has a column named '" + shadowedColumn + "'";
        return Rejection.structural(
            (coordinate.isBlank() ? "" : coordinate + ": ") + because
            + ", so '" + name + "' is ambiguous. Add `@nodeId` to select the node id, or"
            + " `@field(name: \"" + shadowedColumn + "\")` to select the raw column.");
    }

    /**
     * Resolves the {@code decode<TypeName>} helper for a call site that <em>names</em> its target
     * type, either through {@code @nodeId(typeName:)} or through an inference that already
     * produced a unique name. The name is the author's own answer to "which NodeType", so the
     * {@link NodeIndex} by-name view answers it directly; how many other node types happen to
     * share the backing table is irrelevant.
     *
     * <p>Empty when no NodeType carries that name. There is no table-keyed fallback any more: the
     * arm that reverse-mapped a table to a decode helper existed for the synthesis shims and for
     * the orphan case of a {@code @table}-only type over a metadata-carrying table, and with the
     * shims retired a directive-less {@code ID} is an ordinary scalar rather than an orphaned node
     * id. Coordinates that legitimately hold only a table resolve through {@link NodeIndex#forTable}
     * at their own site, where an ambiguous table is a rejection naming {@code typeName:} rather
     * than a silent pick.
     */
    Optional<no.sikt.graphitron.rewrite.model.HelperRef.Decode> resolveDecodeHelperForType(String refTypeName) {
        return nodes.forName(refTypeName)
            .map(no.sikt.graphitron.rewrite.model.GraphitronType.NodeType::decodeMethod);
    }

    /**
     * Resolved NodeType key metadata for a {@code @nodeId(typeName:)} target: the wire-format
     * {@code typeId} plus the key {@link ColumnRef} list, or an {@code error} message when none of
     * the three resolution sources (catalog metadata, the {@link NodeIndex} entry,
     * or {@code @node} on the SDL with PK columns from the catalog) produce a usable
     * pair. Shared shape: both {@link NodeIdLeafResolver#resolve} and
     * {@link #resolveNodeIdRecordDecode} read their key columns through {@link #resolveTargetKeys},
     * so the {@code @node(keyColumns:)} fallback lives in exactly one place.
     */
    record TargetKeys(String typeId, List<ColumnRef> keyColumns, String error) {}

    /**
     * Resolves the target table's NodeType metadata: prefers the {@link NodeIndex} entry, falls back
     * to catalog metadata, then to {@code @node} on the SDL with PK columns from the catalog. Returns
     * an error message when none of those produce a usable {@code typeId} + {@code keyColumns} pair.
     *
     * <p>The third arm is unreachable for a node whose {@code @node} was inferred, and stays a
     * {@code @node}-only read for that reason: inference requires catalog metadata, so the second arm
     * answers first whenever the index lookup misses. It survives for the shape it was written for, a
     * {@code @node} type over a table the catalog carries no metadata for.
     */
    TargetKeys resolveTargetKeys(GraphQLObjectType targetObj, String refTypeName,
                                 String targetTableName) {
        // Name-first. Every caller arrives holding an authoritative type name, and the NodeType
        // the index carries is the fully reconciled answer for that name: TypeBuilder already
        // folded @node against the table's KjerneJooqGenerator metadata, letting SDL win on both
        // axes (typeId outright, keyColumns on order). Reading the table's metadata first would
        // overwrite that reconciliation with a table fact, which is wrong twice over: two @node
        // types over one table each publish their own typeId, and a @node(keyColumns:) that pins a
        // different order than the metadata would project columns transposed against the order its
        // own decode helper returns values in.
        //
        // Lookup goes through the pure NodeIndex (a fixed point built before the walk), not
        // types.get: the node may not be registered yet during the walk.
        var ntOpt = nodes.forName(refTypeName);
        if (ntOpt.isPresent()) {
            return new TargetKeys(ntOpt.get().typeId(), ntOpt.get().nodeKeyColumns(), null);
        }
        // No NodeType under that name: the target is a @table-only type (or failed classification)
        // over a metadata-carrying table. The table fact is the only source left.
        var meta = catalog.nodeIdMetadata(targetTableName);
        if (meta.isPresent()) {
            return new TargetKeys(meta.get().typeId(), meta.get().keyColumns(), null);
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
     * derive and the typeName-vs-table resolution question does not arise here at all.
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
     * {@code @nodeId} reference loads its decoded values into. {@link Resolved} carries the
     * target columns aligned to node-key (decode) order; {@link Rejected} carries a formatted reason.
     */
    sealed interface RecordFkTargets {
        record Resolved(List<ColumnRef> targetColumns) implements RecordFkTargets {}
        record Rejected(String message) implements RecordFkTargets {}
    }

    /**
     * Resolves the FK child columns on {@code recordTable} that a cross-table FK-reference
     * {@code @nodeId} populates: the decoded node-key values load into the foreign key's child
     * columns on the record.
     *
     * <p>FK selection: an explicit {@code @reference(key:)} ({@code explicitFkKey}) names the FK
     * verbatim via {@link JooqCatalog#findForeignKey(String, String)} (scoped by the record table);
     * otherwise the FK is <em>deduced</em> as the
     * single foreign key whose source side is {@code recordTable} and which references
     * {@code nodeTableSqlName} (the same directional deduction
     * {@link JooqCatalog#findOutgoingFkToTable} performs, inlined here and materialised to the FK
     * object). Zero or multiple such FKs reject through {@link #fkCountMessage}, asking the
     * author to add {@code @reference(key:)}.
     *
     * <p>Column reconciliation is <em>by column identity, not position</em>: the decoded values
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
            // Split an optional schema qualifier off the author value, then scope by both the
            // qualifier (hard) and the record's own table (soft) so a colliding constraint name
            // resolves to this record's schema; an unresolved collision surfaces as a typed
            // Ambiguous rejection. A malformed (stray-dot) value falls back to unqualified.
            var qfk = JooqCatalog.parseQualifiedForeignKeyName(explicitFkKey.get())
                .orElseGet(() -> new JooqCatalog.QualifiedForeignKeyName(Optional.empty(), explicitFkKey.get()));
            var fkLookup = catalog.findForeignKey(qfk.name(), recordTable.tableName(), qfk.schema().orElse(null));
            if (fkLookup instanceof JooqCatalog.ForeignKeyLookup.NotInCatalog) {
                return new RecordFkTargets.Rejected("@reference(key: \"" + explicitFkKey.get()
                    + "\") could not be resolved in the jOOQ catalog"
                    + candidateHint(explicitFkKey.get(), catalog.allForeignKeySqlNames()));
            }
            if (fkLookup instanceof JooqCatalog.ForeignKeyLookup.Ambiguous amb) {
                return new RecordFkTargets.Rejected(
                    ambiguousForeignKeyRejection(explicitFkKey.get(), amb.schemas()).message());
            }
            fk = ((JooqCatalog.ForeignKeyLookup.Resolved) fkLookup).fk();
            // Same connection invariant as the {key:} path element, lifted here so the enforcer is
            // uniform: a schema-qualified key can now name an FK in a different schema than the
            // record's table, making "resolves but does not touch" reachable at this site too.
            var connectionRejection = foreignKeyConnectionRejection(fk, recordTable.tableName());
            if (connectionRejection.isPresent()) {
                return new RecordFkTargets.Rejected(connectionRejection.get());
            }
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
        // the record, slot.targetSide is the referenced (node-key) column. selfRefFkOnSource
        // matters when the FK is a self-FK (record table == node table, e.g. CAMPUS's
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

}
