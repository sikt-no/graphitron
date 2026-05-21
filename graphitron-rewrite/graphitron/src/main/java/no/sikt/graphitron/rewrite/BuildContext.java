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
import no.sikt.graphitron.rewrite.model.PkResolution;
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
 * <p>{@link #types} is {@code null} until {@link TypeBuilder#buildTypes()} completes its first
 * pass and sets it. All code that reads {@code types} is called only after that point.
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
    static final String DIR_DEFAULT_ORDER       = "defaultOrder";
    static final String DIR_SPLIT_QUERY         = "splitQuery";
    static final String DIR_SERVICE             = "service";
    static final String DIR_EXTERNAL_FIELD      = "externalField";
    static final String DIR_LOOKUP_KEY          = "lookupKey";
    static final String DIR_ORDER_BY            = "orderBy";
    static final String DIR_CONDITION           = "condition";
    static final String DIR_MUTATION            = "mutation";
    static final String DIR_VALUE               = "value";
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
     * Type-axis classification registry. {@link TypeBuilder#buildTypes()} populates it via
     * {@link TypeRegistry#classify}, {@link TypeRegistry#enrich}, {@link TypeRegistry#demote},
     * and {@link TypeRegistry#synthesize} during the first build pass; downstream code reads
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
     * Set by {@link GraphitronSchemaBuilder} immediately after constructing {@link ServiceCatalog}.
     * Used by {@link #resolveConditionRef} for condition-join method reflection.
     */
    ServiceCatalog svc;

    /**
     * SDL-level scalar applied-directive map, populated by {@link GraphitronSchemaBuilder} from
     * the {@link graphql.schema.idl.TypeDefinitionRegistry} *before* {@link TypeBuilder#buildTypes()}
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
        GraphitronType target = types.get(targetTypeName);
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
     * Outcome of the per-field DELETE carrier projection step over a {@code @table}-element data
     * field (R156). Builder-internal sealed type, used by {@link FieldBuilder} to construct the
     * per-field {@link ChildField.SingleRecordTableFieldFromReturning} carrier's projection list.
     *
     * <p>Two arms:
     * <ul>
     *   <li>{@link Admitted} — every element-type field classified into an admissible
     *       {@link PerFieldOutcome} arm; the projection list contains the
     *       per-field {@link PkResolution} carrier the emitter consumes.</li>
     *   <li>{@link Rejected} — at least one element-type field classified into a rejection arm
     *       ({@link PerFieldOutcome.NonPkNonNullable}, {@link PerFieldOutcome.ServiceField},
     *       or {@link PerFieldOutcome.UnsupportedField}); the reason names the offending
     *       fields and points authors at the ID-typed carrier shape as the recommended pattern.</li>
     * </ul>
     */
    sealed interface DeleteTableProjection {
        record Admitted(List<PkResolution> projection) implements DeleteTableProjection {}
        record Rejected(String reason) implements DeleteTableProjection {}
    }

    /**
     * R156 — DELETE carrier projection step. Walks the element SDL type's fields, classifies
     * each into a {@link PerFieldOutcome} arm, and either rejects the whole carrier (any
     * {@code NonPkNonNullable}, {@code ServiceField}, or {@code UnsupportedField} arm present)
     * or projects the surviving outcomes to a narrow {@link PkResolution} list that rides on
     * {@link ChildField.SingleRecordTableFieldFromReturning} to the emitter.
     *
     * <p>Single producer of {@code List<PkResolution>} by construction; consumers of the
     * projection list rely on the rejection rule (the
     * {@code mutation-delete-carrier.pk-resolution-projection-clean} load-bearing classifier
     * check) being a hard reject and not a silent drop. Relaxing the rejection would break the
     * emitter's exhaustive sealed switch over {@link PkResolution}'s two arms — the rejection
     * arms cannot reach the emitter by type.
     *
     * <p>Consumes already-classified {@link ChildField} values from {@link #fieldRegistry};
     * called from {@link FieldBuilder} during mutation classification, where every type's
     * fields are guaranteed to be classified ahead of the Mutation root's field-classification
     * pass.
     */
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "mutation-delete-carrier.pk-resolution-projection-clean",
        description = "BuildContext.classifyDeleteTableProjection rejects DELETE @table-element "
            + "projections on any PerFieldOutcome.NonPkNonNullable, ServiceField, or "
            + "UnsupportedField arm, naming the offending field(s) in the diagnostic, before "
            + "projecting to List<PkResolution>; consumers of the projection rely on the "
            + "rejection arms being a hard reject and not a silent drop. Relaxing the gate "
            + "breaks the emitter's exhaustive sealed switch over PkResolution's two arms.")
    DeleteTableProjection classifyDeleteTableProjection(String carrierTypeName, String elementTypeName, TableRef elementTable) {
        var raw = schema.getType(elementTypeName);
        if (!(raw instanceof GraphQLObjectType elementObj)) {
            return new DeleteTableProjection.Rejected(
                "DELETE carrier '" + carrierTypeName + "' element type '" + elementTypeName
                + "' is not a GraphQL Object type; only @table-backed Object types can be projected");
        }
        var pkColumns = elementTable.primaryKeyColumns();
        var pkSqlNames = pkColumns.stream().map(ColumnRef::sqlName).collect(Collectors.toSet());

        var outcomes = new ArrayList<PerFieldOutcome>();
        for (var fieldDef : elementObj.getFieldDefinitions()) {
            var coords = FieldCoordinates.coordinates(elementTypeName, fieldDef.getName());
            var classified = fieldRegistry.get(coords);
            outcomes.add(classifyElementFieldForDeleteProjection(fieldDef, classified, pkSqlNames));
        }

        var nonPkNonNull = outcomes.stream()
            .filter(o -> o instanceof PerFieldOutcome.NonPkNonNullable)
            .map(PerFieldOutcome::fieldName)
            .toList();
        var serviceFields = outcomes.stream()
            .filter(o -> o instanceof PerFieldOutcome.ServiceField)
            .map(PerFieldOutcome::fieldName)
            .toList();
        var unsupported = outcomes.stream()
            .filter(o -> o instanceof PerFieldOutcome.UnsupportedField)
            .map(o -> {
                var u = (PerFieldOutcome.UnsupportedField) o;
                return u.fieldName() + " (" + u.leafKind() + ")";
            })
            .toList();

        if (!nonPkNonNull.isEmpty() || !serviceFields.isEmpty() || !unsupported.isEmpty()) {
            var msg = new StringBuilder();
            msg.append("@mutation(typeName: DELETE) carrier '").append(carrierTypeName)
                .append("': data field element type '").append(elementTypeName).append("' has field(s):");
            if (!nonPkNonNull.isEmpty()) {
                msg.append(" - ").append(String.join(", ", nonPkNonNull))
                    .append(" resolving to non-primary-key columns and declared non-nullable "
                        + "(after DELETE only the table's primary key can carry data; either make "
                        + "them nullable or move them off the type);");
            }
            if (!serviceFields.isEmpty()) {
                msg.append(" - ").append(String.join(", ", serviceFields))
                    .append(" are @service-resolved (projecting a deleted SDL type through a "
                        + "@service field is not supported on DELETE carriers — the service "
                        + "would receive a PK-only Record at runtime and any non-PK source "
                        + "param would silently produce null);");
            }
            if (!unsupported.isEmpty()) {
                msg.append(" - ").append(String.join(", ", unsupported))
                    .append(" are not resolvable from the PK-only RETURNING record (no follow-up "
                        + "query runs against the deleted row);");
            }
            msg.append(" Prefer an ID-typed carrier field (type ID or [ID!] "
                + "with @nodeId either implicit by the input @table's @node registration or "
                + "explicit on the field), which echoes the deleted primary keys without "
                + "requiring a @table-element projection.");
            return new DeleteTableProjection.Rejected(msg.toString());
        }

        var projection = outcomes.stream()
            .map(o -> switch (o) {
                case PerFieldOutcome.PkRead p ->
                    (PkResolution) new PkResolution.PkRead(p.fieldName(), p.columns(), p.encode());
                case PerFieldOutcome.NonPkNullable n ->
                    (PkResolution) new PkResolution.NonPkNullable(n.fieldName());
                case PerFieldOutcome.NonPkNonNullable ignored ->
                    throw new AssertionError("rejected above");
                case PerFieldOutcome.ServiceField ignored ->
                    throw new AssertionError("rejected above");
                case PerFieldOutcome.UnsupportedField ignored ->
                    throw new AssertionError("rejected above");
            })
            .toList();
        return new DeleteTableProjection.Admitted(projection);
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

    public DmlPayloadScan scanStructuralDmlPayload(String payloadSdlName) {
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
            // execution semantics) pass through.
            for (String forbidden : FORBIDDEN_CARRIER_DATA_FIELD_DIRECTIVES) {
                if (f.hasAppliedDirective(forbidden)) {
                    return new DmlPayloadScan.NotApplicable();
                }
            }
            String elementTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(f.getType())).getName();
            var elementType = types.get(elementTypeName);
            DmlElementKind kind;
            if (elementType instanceof GraphitronType.TableBackedType tbt) {
                kind = new DmlElementKind.Table(tbt.table(), elementTypeName);
            } else if (elementType instanceof GraphitronType.ResultType rt && rt.fqClassName() != null) {
                kind = new DmlElementKind.RecordElement(f.getName());
            } else if ("ID".equals(elementTypeName)) {
                // R156 wrapper-shape rule: list-of-nullable ([ID]) and Connection wrappers
                // are rejected on payload-returning DELETE. Test fixtures pin these diagnostic
                // wordings; preserve the same family.
                var wrapper = buildWrapper(f);
                if (wrapper instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.List list && list.itemNullable()) {
                    return new DmlPayloadScan.Reject(
                        "single-record carrier field '" + f.getName() + "' has element type 'ID' "
                        + "with a list-of-nullable wrapper '[ID]'; payload-returning DELETE requires "
                        + "either singleton (ID / ID!) or list-of-non-null ([ID!] / [ID!]!), since "
                        + "every element of a successful DELETE response is the encoded PK of an "
                        + "actually-deleted row, so the slot cannot be null");
                }
                if (wrapper instanceof no.sikt.graphitron.rewrite.model.FieldWrapper.Connection) {
                    return new DmlPayloadScan.Reject(
                        "single-record carrier field '" + f.getName() + "' has element type 'ID' "
                        + "with a Connection wrapper; payload-returning DELETE requires either "
                        + "singleton (ID / ID!) or list-of-non-null ([ID!] / [ID!]!)");
                }
                kind = new DmlElementKind.IdElement();
            } else {
                return new DmlPayloadScan.Reject(
                    "carrier field '" + f.getName() + "' of type '" + elementTypeName
                    + "' is not a recognized DML payload data-field shape "
                    + "(expected @table-element, @record-element, or ID-element); "
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
     * Classify a single element-SDL-type field for the DELETE projection. Switches on the
     * already-classified {@link ChildField} from {@link #fieldRegistry}; the four
     * non-rejecting arms ({@code PkRead}, {@code NonPkNullable}) map to admissible projection
     * cases, the two rejection arms ({@code NonPkNonNullable}, {@code ServiceField}) capture
     * the offending shape, and {@code UnsupportedField} is the catch-all for any
     * {@link GraphitronField} leaf the projection cannot resolve from a PK-only RETURNING
     * record (table-bound child collections, computed fields, record-bound nested fields, etc.).
     */
    private PerFieldOutcome classifyElementFieldForDeleteProjection(
            GraphQLFieldDefinition fieldDef, GraphitronField classified, Set<String> pkSqlNames) {
        String fieldName = fieldDef.getName();
        boolean fieldNullable = !(fieldDef.getType() instanceof graphql.schema.GraphQLNonNull);
        if (classified == null) {
            // No classification — treat as unsupported. Could happen if the element type is a
            // PlainObjectType whose fields the schema walk left unclassified. Rejecting the
            // projection is honest: it cannot resolve a field the classifier never typed.
            return new PerFieldOutcome.UnsupportedField(fieldName, "unclassified");
        }
        return switch (classified) {
            case ChildField.ColumnField cf -> {
                boolean pkColumn = pkSqlNames.contains(cf.column().sqlName());
                if (pkColumn) {
                    Optional<HelperRef.Encode> encode = cf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys nek
                        ? Optional.of(nek.encodeMethod())
                        : Optional.empty();
                    yield new PerFieldOutcome.PkRead(fieldName, List.of(cf.column()), encode);
                }
                yield fieldNullable
                    ? new PerFieldOutcome.NonPkNullable(fieldName)
                    : new PerFieldOutcome.NonPkNonNullable(fieldName);
            }
            case ChildField.CompositeColumnField ccf -> {
                boolean allPk = ccf.columns().stream().map(ColumnRef::sqlName).allMatch(pkSqlNames::contains);
                if (allPk) {
                    yield new PerFieldOutcome.PkRead(fieldName, ccf.columns(),
                        Optional.of(ccf.compaction().encodeMethod()));
                }
                yield fieldNullable
                    ? new PerFieldOutcome.NonPkNullable(fieldName)
                    : new PerFieldOutcome.NonPkNonNullable(fieldName);
            }
            case ChildField.ColumnReferenceField crf ->
                // Terminal column lives on a joined target table; after DELETE no follow-up join
                // can run. The runtime fetcher reads aliased joined columns off the source Record,
                // which the DELETE carrier's synthesized PK-only Record cannot supply — even on
                // nullable SDL fields the per-field ColumnFetcher would throw at runtime. Reject
                // the carrier rather than silently breaking the SDL field at fetch time.
                new PerFieldOutcome.UnsupportedField(fieldName, "ColumnReferenceField (FK reference; resolving requires a follow-up join that DELETE cannot run)");
            case ChildField.CompositeColumnReferenceField ignored ->
                new PerFieldOutcome.UnsupportedField(fieldName, "CompositeColumnReferenceField (composite FK reference; resolving requires a follow-up join that DELETE cannot run)");
            case ChildField.ServiceTableField ignored -> new PerFieldOutcome.ServiceField(fieldName);
            case ChildField.ServiceRecordField ignored -> new PerFieldOutcome.ServiceField(fieldName);
            case GraphitronField.UnclassifiedField ignored ->
                new PerFieldOutcome.UnsupportedField(fieldName, "unclassified");
            default ->
                new PerFieldOutcome.UnsupportedField(fieldName, classified.getClass().getSimpleName());
        };
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
            if (!(types.get(memberName) instanceof GraphitronType.ErrorType et)) {
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
     * Carries the result of {@link #parsePath}: either a fully resolved list of path elements or
     * an error message. When {@code errorMessage()} is non-null the {@code elements()} list is
     * empty and the containing field must be classified as an unclassified variant.
     */
    record ParsedPath(List<JoinStep> elements, String errorMessage) {
        boolean hasError() { return errorMessage != null; }
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
     * {@link #unknownTableRejection}; surfaces a Levenshtein-ranked candidate hint over every
     * FK constraint name in the catalog so a typo in {@code @reference(key: "...")} (or in the
     * inferred FK name picked up by the {@code IdReference} synthesis shim) reaches the schema
     * author with a fix-it suggestion rather than just a "not in catalog" string.
     */
    Rejection unknownForeignKeyRejection(String fkName) {
        return Rejection.unknownForeignKey(
            "foreign key '" + fkName + "' could not be resolved in the jOOQ catalog",
            fkName, catalog.allForeignKeySqlNames());
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

        for (var v : elements) {
            if (v instanceof Map<?, ?>) {
                parsePathElement(asMap(v), currentSource, fieldName, stepIndex, resolvedElements, errors, isList);
                stepIndex++;
                if (!resolvedElements.isEmpty()) {
                    var last = resolvedElements.getLast();
                    currentSource = last instanceof FkJoin fk ? fk.targetTable().tableName() : null;
                }
            }
        }

        if (!errors.isEmpty()) {
            return new ParsedPath(List.of(), String.join("; ", errors));
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
                            unknownTableRejection(u.failure(), u.requestedName()).message());
                    }
                    case FkJoinResolution.UnknownForeignKey uf -> {
                        return new ParsedPath(List.of(),
                            unknownForeignKeyRejection(uf.fkName()).message());
                    }
                }
            } else {
                return new ParsedPath(List.of(),
                    fkCountMessage(startSqlTableName, targetSqlTableName, fks, /*directiveAbsent=*/true));
            }
        }
        return new ParsedPath(List.copyOf(resolvedElements), null);
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
     */
    /**
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
        boolean isSelfRef = fkSideTable.equalsIgnoreCase(keySideTable);
        boolean fkOnSource = isSelfRef
            ? selfRefFkOnSource
            : sourceSqlName.equalsIgnoreCase(fkSideTable);
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
        List<ColumnRef> fkSideCols  = resolveFkColumnRefs(f.getTable(), f.getFields());
        // The FK's *own* referenced-column list (third TableField[] arg passed to
        // Internal.createForeignKey at codegen time, surfaced as ForeignKey.getKeyFields()) is
        // parallel to getFields() by jOOQ's contract: position i of the referencing list pairs
        // with position i of the referenced list. ForeignKey.getKey().getFields() returns the
        // referenced UniqueKey's *own* declaration order, which for an FK whose referenced-column
        // ordering differs from the parent PK's declaration order (e.g. PRIMARY KEY (a, b, c)
        // referenced as REFERENCES parent (b, c, a)) does NOT pair positionally with getFields().
        // Zipping the two non-parallel lists produced silent mis-paired slots — observable as
        // Field<X>.eq(Field<Y>) compile errors in generated rows-method JOIN ON predicates when
        // the FK column types are heterogeneous. See SynthesizeFkJoinReorderedKeysTest.
        List<ColumnRef> keySideCols = resolveFkColumnRefs(f.getKey().getTable(), f.getKeyFields());
        // Synthesis-time orientation: bake the FK-direction decision into each slot pair so
        // emitter sites read direction-blind (target.<targetSide>.eq(source.<sourceSide>))
        // regardless of whether the FK lives on the source or the target table.
        List<JoinSlot.FkSlot> slots = new java.util.ArrayList<>(fkSideCols.size());
        for (int i = 0; i < fkSideCols.size(); i++) {
            ColumnRef fkCol  = fkSideCols.get(i);
            ColumnRef keyCol = keySideCols.get(i);
            slots.add(fkOnSource
                ? new JoinSlot.FkSlot(fkCol, keyCol)
                : new JoinSlot.FkSlot(keyCol, fkCol));
        }
        String alias = fieldName + "_" + stepIndex;
        return new FkJoinResolution.Resolved(new FkJoin(fkResolved.ref(), originTable,
            targetTable, slots, whereFilter, alias));
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
            String fieldName, int stepIndex, List<JoinStep> out, List<String> errors, boolean isList) {
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
                    + candidateHint(keyName.get(), catalog.allForeignKeySqlNames()));
                return;
            }
            var f = fk.get();
            String fkSideTable  = f.getTable().getName();
            String keySideTable = f.getKey().getTable().getName();
            if (currentSourceSqlName != null
                && !currentSourceSqlName.equalsIgnoreCase(fkSideTable)
                && !currentSourceSqlName.equalsIgnoreCase(keySideTable)) {
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
                case FkJoinResolution.Resolved r -> out.add(r.fkJoin());
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
                case FkJoinResolution.Resolved r -> out.add(r.fkJoin());
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
            } else if (res.ref() != null) {
                out.add(new ConditionJoin(res.ref(), alias));
            } else {
                errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
            }
            return;
        }
        errors.add("path element has neither 'key', 'table', nor 'condition'");
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
            ServiceCatalog.TableSlotPolicy.REQUIRED);
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
     * <p>{@code expandingTypes} guards against circular plain-input nesting; callers start
     * with an empty set.
     */
    InputFieldResolution classifyInputField(
            GraphQLInputObjectField field, String parentTypeName, TableRef resolvedTable,
            Set<String> expandingTypes, List<String> errors) {
        var resolution = classifyInputFieldInternal(field, parentTypeName, resolvedTable, expandingTypes, errors);
        // Trace-only: input fields are stored embedded in their parent type rather than in a
        // central map, so the registry doesn't own their persistence; this is the canonical
        // emission point.
        fieldRegistry.classifyInput(parentTypeName, field.getName(),
            field.getDefinition() != null ? field.getDefinition().getSourceLocation() : null,
            resolution);
        return resolution;
    }

    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "nodeid-fk.direct-fk-keys-match",
        reliesOn = "The @nodeId FK-target arms in inputFieldFromNodeIdResolved construct"
            + " InputField.ColumnReferenceField / CompositeColumnReferenceField only on"
            + " NodeIdLeafResolver.Resolved.FkTarget.DirectFk; the same-table arm constructs"
            + " InputField.{Column,CompositeColumn}Field instead, with no joinPath. Both arity"
            + " branches (ID! and [ID!]) share the same switch shape, so the variant choice"
            + " stays in lockstep with FieldBuilder.classifyArgument. The TranslatedFk arm"
            + " routes to InputFieldResolution.Unresolved with a deferred-emission hint so"
            + " emitter consumers (walkInputFieldConditions → implicit body params) never see"
            + " a JOIN-with-translation shape they cannot bind directly.")
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "nodeid-fk.identity-carrying-lift",
        reliesOn = "The @nodeId @reference path on a @table input field (either arity) routes"
            + " through NodeIdLeafResolver.resolve, which calls validateLift / liftSourceColumns"
            + " internally and either returns a Resolved.FkTarget.DirectFk carrying the lifted"
            + " tuple or a Resolved.Rejected with the lift-failure marker. Lift failures surface"
            + " as InputFieldResolution.Unresolved at the classifier boundary; no direct call to"
            + " the static helpers happens from this classifier any more.")
    private InputFieldResolution classifyInputFieldInternal(
            GraphQLInputObjectField field, String parentTypeName, TableRef resolvedTable,
            Set<String> expandingTypes, List<String> errors) {
        String name = field.getName();
        if (field.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return new InputFieldResolution.Unresolved(name, null,
                "@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema.");
        }
        if (field.hasAppliedDirective(DIR_LOOKUP_KEY)) {
            return new InputFieldResolution.Unresolved(name, null,
                "@lookupKey on a mutation input field is no longer supported (R144); "
                + "remove it (the field is a filter by default), or replace it with "
                + "@value on UPDATE value fields. On Query-side @table input args, "
                + "move @lookupKey to the surrounding ARGUMENT_DEFINITION instead.");
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
                    return new InputFieldResolution.Resolved(new InputField.ColumnReferenceField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        col, path.elements(), List.of(col), cond,
                        new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()));
                })
                .orElseGet(() -> new InputFieldResolution.Unresolved(name, columnName,
                    "no column '" + columnName + "' reachable via @reference path"));
        }
        // Nesting: field type is a plain input object (no @table).
        var baseType = GraphQLTypeUtil.unwrapAll(type);
        if (baseType instanceof GraphQLInputObjectType nestedInputType
                && !nestedInputType.hasAppliedDirective(DIR_TABLE)) {
            if (expandingTypes.contains(typeName)) {
                return new InputFieldResolution.Unresolved(name, null,
                    "circular input type reference detected while expanding '" + typeName + "'");
            }
            var newExpanding = new LinkedHashSet<>(expandingTypes);
            newExpanding.add(typeName);
            var failures = new ArrayList<InputFieldResolution.Unresolved>();
            var resolvedFields = new ArrayList<InputField>();
            for (var nested : nestedInputType.getFieldDefinitions()) {
                var res = classifyInputField(nested, typeName, resolvedTable, newExpanding, errors);
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
        // graphitron-rewrite/roadmap/retire-synthesis-shims.md.
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
                        + " see graphitron-rewrite/roadmap/retire-id-reference-synthesis-shim.md",
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
            return new InputFieldResolution.Resolved(new InputField.ColumnField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()), cond,
                new no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct()));
        }
        // NodeId synthesis shim: scalar ID field with no @nodeId directive whose backing table
        // carries node-identity metadata (__NODE_TYPE_ID / __NODE_KEY_COLUMNS constants emitted
        // by KjerneJooqGenerator). Fires a per-site deprecation diagnostic; the canonical form is
        // to declare @nodeId explicitly, which routes through the bare-@nodeId branch above. See
        // graphitron-rewrite/roadmap/retire-synthesis-shims.md.
        if ("ID".equals(typeName) && !list && !field.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta = catalog.nodeIdMetadata(tableName);
            if (nodeIdMeta.isPresent()) {
                NODE_ID_SHIM_LOGGER.warn("input field '{}.{}' synthesizes a NodeId-decoded column"
                    + " without '@nodeId'; declare the directive explicitly. The synthesis shim"
                    + " will be removed in a future release."
                    + " See graphitron-rewrite/roadmap/retire-synthesis-shims.md",
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
        // R209: @condition(override: true) means no implicit column predicate is emitted, so the
        // field does not need a matching column. Build the condition and emit a ConditionOnlyField;
        // the explicit method owns the predicate entirely at runtime. If the condition itself fails
        // to build, fall through to the Unresolved arm (condErrors already populated by
        // buildInputFieldCondition will surface separately).
        var conditionDirective = readConditionDirective(field);
        if (conditionDirective != null && conditionDirective.override()) {
            int errorsBefore = errors.size();
            Optional<ArgConditionRef> overrideCond = buildInputFieldCondition(field, name, errors);
            if (overrideCond.isPresent()) {
                return new InputFieldResolution.Resolved(new InputField.ConditionOnlyField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    overrideCond.get()));
            }
            if (errors.size() > errorsBefore) {
                // Condition reflection / arg-mapping failure already lifted into errors; suppress
                // the redundant "no column found" message so the schema author sees the actionable
                // root cause without a confusing column-miss alongside it.
                return new InputFieldResolution.Unresolved(name, null,
                    "@condition(override: true) failed to build; see condition error above");
            }
        }
        return new InputFieldResolution.Unresolved(name, columnName,
            "no column '" + columnName + "' found in table '" + tableName + "'");
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
     * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement}: input-field
     * filter leaves drop malformed encoded ids silently to "no row matches" rather than throwing.
     * Argument-level lookup leaves in {@link FieldBuilder#classifyArgument} can pick
     * {@code ThrowOnMismatch}; that arm is not reachable from here.
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
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement(st.decodeMethod());
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
                var extraction = new no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement(direct.decodeMethod());
                if (direct.keyColumns().size() == 1) {
                    return new InputFieldResolution.Resolved(new InputField.ColumnReferenceField(
                        parentTypeName, name, locationOf(field), typeName, nonNull, list,
                        direct.keyColumns().get(0), direct.joinPath(),
                        direct.liftedSourceColumns(), cond, extraction));
                }
                return new InputFieldResolution.Resolved(new InputField.CompositeColumnReferenceField(
                    parentTypeName, name, locationOf(field), typeName, nonNull, list,
                    direct.keyColumns(), direct.joinPath(),
                    direct.liftedSourceColumns(), cond, extraction));
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
        if (targetKeyColumns.size() == 1) {
            return new InputFieldResolution.Resolved(new InputField.ColumnReferenceField(parentTypeName, name, location,
                typeName, nonNull, list, targetKeyColumns.get(0), joinPath, liftedSourceColumns, cond,
                extraction));
        }
        return new InputFieldResolution.Resolved(new InputField.CompositeColumnReferenceField(parentTypeName, name, location,
            typeName, nonNull, list, targetKeyColumns, joinPath, liftedSourceColumns, cond, extraction));
    }

    /**
     * Resolves the {@code decode<TypeName>} {@link no.sikt.graphitron.rewrite.model.HelperRef.Decode}
     * for the NodeType backing the given SQL table, or {@code null} when no unique mapping exists.
     * Used by the {@code @nodeId} synthesis shim and other input-side classifier paths that need
     * the per-Node decode helper but only have the SQL table name in scope.
     *
     * <p>Prefers reading from {@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType#decodeMethod()}
     * when the {@code types} map is populated (post-first-pass). Falls back to inline
     * construction via the canonical {@code outputPackage + ".util" + "NodeIdEncoder"} +
     * {@code "decode" + typeName} formula otherwise; both BuildContext and TypeBuilder compute
     * the same name string from the GraphQL type name, so drift is bounded.
     */
    no.sikt.graphitron.rewrite.model.HelperRef.Decode resolveDecodeHelperForTable(
            String sqlTableName,
            String fallbackTypeNameOrTypeId,
            java.util.List<no.sikt.graphitron.rewrite.model.ColumnRef> keyColumns) {
        var encoderClass = no.sikt.graphitron.javapoet.ClassName.get(
            ctx.outputPackage() + ".util",
            no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator.CLASS_NAME);
        var typeNameOpt = findGraphQLTypeForTable(sqlTableName);
        if (typeNameOpt.isPresent()) {
            String typeName = typeNameOpt.get();
            if (types != null && types.get(typeName) instanceof no.sikt.graphitron.rewrite.model.GraphitronType.NodeType nt) {
                return nt.decodeMethod();
            }
            return new no.sikt.graphitron.rewrite.model.HelperRef.Decode(encoderClass, "decode" + typeName, keyColumns);
        }
        // No unique @table-annotated GraphQL object type backs this table (e.g. orphan-input
        // schemas where only an `input Foo @table(name: "bar")` exists). Fall back to the
        // metadata's typeId (the wire-format identifier) as the helper-method suffix; this
        // matches NodeType.encodeMethod / decodeMethod resolution in the default case where
        // typeId equals the GraphQL type name. The customized-typeId / no-NodeType combination
        // is only reachable through the synthesis shim, which is on a retirement track (see
        // graphitron-rewrite/roadmap/retire-synthesis-shims.md).
        if (fallbackTypeNameOrTypeId == null || fallbackTypeNameOrTypeId.isBlank()) return null;
        return new no.sikt.graphitron.rewrite.model.HelperRef.Decode(
            encoderClass, "decode" + fallbackTypeNameOrTypeId, keyColumns);
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
