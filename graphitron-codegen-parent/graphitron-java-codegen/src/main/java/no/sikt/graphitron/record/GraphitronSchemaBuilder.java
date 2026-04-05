package no.sikt.graphitron.record;

import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.NullValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import graphql.schema.idl.EchoingWiringFactory;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.ErrorHandlerType;
import no.sikt.graphitron.record.type.ErrorHandlerSpec;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.record.field.ChildField.ColumnField;
import no.sikt.graphitron.record.field.ChildField.ColumnReferenceField;
import no.sikt.graphitron.record.field.ArgumentSpec;
import no.sikt.graphitron.record.field.ChildField.ComputedField;
import no.sikt.graphitron.record.field.ChildField.InterfaceField;
import no.sikt.graphitron.record.field.ChildField.MultitableReferenceField;
import no.sikt.graphitron.record.field.ChildField.NestingField;
import no.sikt.graphitron.record.field.ChildField.NodeIdField;
import no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.record.field.ChildField.PropertyField;
import no.sikt.graphitron.record.field.ChildField.ServiceField;
import no.sikt.graphitron.record.field.ChildField.TableField;
import no.sikt.graphitron.record.field.ChildField.TableInterfaceField;
import no.sikt.graphitron.record.field.ChildField.TableMethodField;
import no.sikt.graphitron.record.field.ChildField.UnionField;
import no.sikt.graphitron.record.field.MutationField;
import no.sikt.graphitron.record.field.QueryField;
import no.sikt.graphitron.record.field.DefaultOrderSpec;
import no.sikt.graphitron.record.field.FieldWrapper;
import no.sikt.graphitron.record.field.FieldConditionRef;
import no.sikt.graphitron.record.field.OrderSpec;
import no.sikt.graphitron.record.field.SortFieldSpec;
import no.sikt.graphitron.record.field.ColumnRef;
import no.sikt.graphitron.record.field.ColumnRef.ResolvedColumn;
import no.sikt.graphitron.record.field.ColumnRef.UnresolvedColumn;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.record.field.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.record.field.ExternalRef;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.NodeTypeRef;
import no.sikt.graphitron.record.field.NodeTypeRef.ResolvedNodeType;
import no.sikt.graphitron.record.field.ReturnTypeRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyAndConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyRef;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.GraphitronType.ErrorType;
import no.sikt.graphitron.record.type.GraphitronType.InputType;
import no.sikt.graphitron.record.type.GraphitronType.InterfaceType;
import no.sikt.graphitron.record.type.GraphitronType.ResultType;
import no.sikt.graphitron.record.type.InputFieldSpec;
import no.sikt.graphitron.record.type.GraphitronType.RootType;
import no.sikt.graphitron.record.type.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.record.type.GraphitronType.TableType;
import no.sikt.graphitron.record.type.GraphitronType.UnionType;
import no.sikt.graphitron.record.type.KeyColumnRef;
import no.sikt.graphitron.record.type.KeyColumnRef.ResolvedKeyColumn;
import no.sikt.graphitron.record.type.KeyColumnRef.UnresolvedKeyColumn;
import no.sikt.graphitron.record.type.NodeRef;
import no.sikt.graphitron.record.type.NodeRef.NoNode;
import no.sikt.graphitron.record.type.NodeRef.NodeDirective;
import no.sikt.graphitron.record.type.ParticipantRef;
import no.sikt.graphitron.record.type.ParticipantRef.BoundParticipant;
import no.sikt.graphitron.record.type.ParticipantRef.UnboundParticipant;
import no.sikt.graphitron.record.type.TableRef;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import no.sikt.graphitron.record.type.TableRef.UnresolvedTable;
import org.jooq.ForeignKey;
import org.jooq.Table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a {@link GraphitronSchema} from a {@link TypeDefinitionRegistry} by classifying every
 * named type into the sealed {@link GraphitronType} hierarchy and every field into the sealed
 * {@link GraphitronField} hierarchy.
 *
 * <p>This is the directive-reading boundary: the only place in the pipeline that reads schema
 * directives ({@code @table}, {@code @record}, {@code @node}, {@code @discriminate},
 * {@code @field}, {@code @reference}, {@code @nodeId}, etc.). Downstream code works exclusively
 * with the produced {@link GraphitronType} and {@link GraphitronField} values.
 *
 * <p>The Maven plugin calls {@link #build(TypeDefinitionRegistry)} before running
 * {@link GraphitronSchemaValidator#validate(GraphitronSchema)}.
 *
 * <h2>Incremental classification</h2>
 * <p>Fields that are not yet handled by any classification rule are classified as
 * {@link UnclassifiedField}. The {@link GraphitronSchemaValidator} reports an error for every
 * {@code UnclassifiedField}, so the schema cannot be used for code generation until all fields
 * are handled.
 */
public class GraphitronSchemaBuilder {

    private static final Set<String> ROOT_TYPE_NAMES = Set.of("Query", "Mutation", "Subscription");

    // Directive names — these are the ground truth for what this builder reads from the schema.
    // They are validated against the assembled GraphQLSchema at build time (see validateDirectiveSchema).
    private static final String DIR_TABLE = "table";
    private static final String DIR_RECORD = "record";
    private static final String DIR_DISCRIMINATE = "discriminate";
    private static final String DIR_NODE = "node";
    private static final String DIR_NOT_GENERATED = "notGenerated";
    private static final String DIR_MULTITABLE_REFERENCE = "multitableReference";
    private static final String DIR_NODE_ID = "nodeId";
    private static final String DIR_FIELD = "field";
    private static final String DIR_REFERENCE = "reference";
    private static final String DIR_ERROR = "error";
    private static final String DIR_TABLE_METHOD = "tableMethod";
    private static final String DIR_DEFAULT_ORDER = "defaultOrder";
    private static final String DIR_SPLIT_QUERY = "splitQuery";
    private static final String DIR_SERVICE = "service";
    private static final String DIR_EXTERNAL_FIELD = "externalField";
    private static final String DIR_LOOKUP_KEY = "lookupKey";
    private static final String DIR_ORDER_BY = "orderBy";
    private static final String DIR_CONDITION = "condition";
    private static final String DIR_MUTATION = "mutation";
    private static final String DIR_DISCRIMINATOR = "discriminator";

    // Argument names for the directives above.
    private static final String ARG_CONTEXT_ARGUMENTS = "contextArguments";
    private static final String ARG_SERVICE_REF = "service";
    private static final String ARG_TABLE_METHOD_REF = "tableMethodReference";
    private static final String ARG_METHOD = "method";
    private static final String ARG_VALUE = "value";  // @discriminator(value:)
    private static final String ARG_NAME = "name";
    private static final String ARG_ON = "on";
    private static final String ARG_TYPE_ID = "typeId";
    private static final String ARG_KEY_COLUMNS = "keyColumns";
    private static final String ARG_TYPE_NAME = "typeName";
    private static final String ARG_JAVA_NAME = "javaName";
    private static final String ARG_PATH = "path";
    private static final String ARG_KEY = "key";
    private static final String ARG_CONDITION = "condition";
    // Argument names for @defaultOrder.
    private static final String ARG_INDEX = "index";
    private static final String ARG_FIELDS = "fields";
    private static final String ARG_PRIMARY_KEY = "primaryKey";
    private static final String ARG_DIRECTION = "direction";
    private static final String ARG_COLLATE = "collate";         // FieldSort.collate (collation string)
    // Argument names for @error / ErrorHandler input fields.
    private static final String ARG_HANDLERS = "handlers";
    private static final String ARG_HANDLER = "handler";
    private static final String ARG_CLASS_NAME = "className";
    private static final String ARG_CODE = "code";
    private static final String ARG_SQL_STATE = "sqlState";
    private static final String ARG_MATCHES = "matches";
    private static final String ARG_DESCRIPTION = "description";

    private final GraphQLSchema schema;
    private final JooqCatalog catalog;
    private Map<String, GraphitronType> types;

    private GraphitronSchemaBuilder(GraphQLSchema schema, JooqCatalog catalog) {
        this.schema = schema;
        this.catalog = catalog;
    }

    /**
     * Classifies all types and fields in {@code registry} and returns the resulting
     * {@link GraphitronSchema}. The registry must already include the Graphitron directive
     * definitions.
     *
     * <p>The registry is assembled into a {@link GraphQLSchema} using
     * {@link EchoingWiringFactory} (same pattern as
     * {@code SchemaTransformer.assembleSchema()}) so that type resolution, interface
     * linkage, and directive coercion are all handled by graphql-java rather than
     * re-implemented at the AST level.
     */
    public static GraphitronSchema build(TypeDefinitionRegistry registry) {
        var runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring ->
            registry.scalars().forEach((name, v) -> {
                if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                    wiring.scalar(EchoingWiringFactory.fakeScalar(name));
                }
            })
        );
        var assembled = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        return new GraphitronSchemaBuilder(assembled, new JooqCatalog(GeneratorConfig.getGeneratedJooqPackage())).buildSchema();
    }

    private GraphitronSchema buildSchema() {
        validateDirectiveSchema();
        types = buildTypes();
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();

        schema.getAllTypesAsList().stream()
            .filter(t -> t instanceof GraphQLObjectType && !t.getName().startsWith("__"))
            .map(t -> (GraphQLObjectType) t)
            .forEach(objType -> {
                var parentType = types.get(objType.getName());
                if (parentType == null) return;
                objType.getFieldDefinitions().forEach(fieldDef -> {
                    var gField = classifyField(fieldDef, objType.getName(), parentType);
                    fields.put(FieldCoordinates.coordinates(objType.getName(), fieldDef.getName()), gField);
                });
            });

        return new GraphitronSchema(types, fields);
    }

    // ===== Type classification =====

    private Map<String, GraphitronType> buildTypes() {
        // First pass: classify every type (interface/union participants are initially empty).
        var result = new LinkedHashMap<String, GraphitronType>();
        schema.getAllTypesAsList().stream()
            .filter(t -> !t.getName().startsWith("__"))
            .forEach(namedType -> {
                var gType = classifyType(namedType);
                if (gType != null) {
                    result.put(namedType.getName(), gType);
                }
            });

        // Second pass: enrich interface and union types with their participant lists.
        result.replaceAll((name, type) -> switch (type) {
            case TableInterfaceType tit -> enrichTableInterfaceType(tit, result);
            case InterfaceType it       -> enrichInterfaceType(it, result);
            case UnionType ut           -> enrichUnionType(ut, result);
            default                     -> type;
        });

        return result;
    }

    private TableInterfaceType enrichTableInterfaceType(TableInterfaceType type, Map<String, GraphitronType> types) {
        var participants = implementorsOf(type.name(), types);
        return new TableInterfaceType(type.name(), type.location(), type.discriminatorColumn(), type.table(), participants);
    }

    private InterfaceType enrichInterfaceType(InterfaceType type, Map<String, GraphitronType> types) {
        var participants = implementorsOf(type.name(), types);
        return new InterfaceType(type.name(), type.location(), participants);
    }

    private UnionType enrichUnionType(UnionType type, Map<String, GraphitronType> types) {
        var unionType = (GraphQLUnionType) schema.getType(type.name());
        var participants = unionType.getTypes().stream()
            .map(memberType -> participantRef(memberType.getName(), types))
            .toList();
        return new UnionType(type.name(), type.location(), participants);
    }

    /** Returns one {@link ParticipantRef} for each type that implements {@code interfaceName}. */
    private List<ParticipantRef> implementorsOf(String interfaceName, Map<String, GraphitronType> types) {
        var iface = (GraphQLInterfaceType) schema.getType(interfaceName);
        return schema.getImplementations(iface).stream()
            .map(obj -> participantRef(obj.getName(), types))
            .toList();
    }

    private ParticipantRef participantRef(String typeName, Map<String, GraphitronType> types) {
        if (types.get(typeName) instanceof TableType tableType) {
            String discriminatorValue = argString(schema.getObjectType(typeName), DIR_DISCRIMINATOR, ARG_VALUE).orElse(null);
            return new BoundParticipant(typeName, tableType.table(), discriminatorValue);
        }
        return new UnboundParticipant(typeName);
    }

    private GraphitronType classifyType(GraphQLNamedType namedType) {
        if (namedType instanceof GraphQLScalarType
                || namedType instanceof GraphQLEnumType) {
            return null;
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
            if (objType.hasAppliedDirective(DIR_TABLE)) {
                return buildTableType(objType);
            }
            if (objType.hasAppliedDirective(DIR_RECORD)) {
                return new ResultType(name, location);
            }
            if (objType.hasAppliedDirective(DIR_ERROR)) {
                return buildErrorType(objType);
            }
            return null;
        }
        if (namedType instanceof GraphQLInterfaceType iface) {
            if (iface.hasAppliedDirective(DIR_TABLE) && iface.hasAppliedDirective(DIR_DISCRIMINATE)) {
                return buildTableInterfaceType(iface);
            }
            return new InterfaceType(name, location, List.of());
        }
        if (namedType instanceof GraphQLUnionType) {
            return new UnionType(name, location, List.of());
        }
        return null;
    }

    private TableType buildTableType(GraphQLObjectType objType) {
        String name = objType.getName();
        SourceLocation location = locationOf(objType);
        String tableName = argString(objType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        TableRef tableRef = resolveTable(tableName);
        NodeRef nodeRef = buildNodeRef(objType, tableRef);
        return new TableType(name, location, tableRef, nodeRef);
    }

    private TableInterfaceType buildTableInterfaceType(GraphQLInterfaceType iface) {
        String name = iface.getName();
        SourceLocation location = locationOf(iface);
        String tableName = argString(iface, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        String discriminatorColumn = argString(iface, DIR_DISCRIMINATE, ARG_ON).orElse(null);
        TableRef tableRef = resolveTable(tableName);
        return new TableInterfaceType(name, location, discriminatorColumn, tableRef, List.of());
    }

    private TableRef resolveTable(String sqlName) {
        return catalog.findTable(sqlName)
            .<TableRef>map(e -> new ResolvedTable(sqlName, e.javaFieldName(), e.table()))
            .orElseGet(() -> new UnresolvedTable(sqlName));
    }

    private NodeRef buildNodeRef(GraphQLObjectType objType, TableRef tableRef) {
        if (!objType.hasAppliedDirective(DIR_NODE)) {
            return new NoNode();
        }
        String typeId = argString(objType, DIR_NODE, ARG_TYPE_ID).orElse(null);
        List<String> keyColumnNames = argStringList(objType, DIR_NODE, ARG_KEY_COLUMNS);
        Table<?> resolvedTable = tableRef instanceof ResolvedTable rt ? rt.table() : null;
        List<KeyColumnRef> keyColumns = keyColumnNames.stream()
            .map(colName -> resolveKeyColumn(colName, resolvedTable))
            .toList();
        return new NodeDirective(typeId, keyColumns);
    }

    private ErrorType buildErrorType(GraphQLObjectType objType) {
        String name = objType.getName();
        SourceLocation location = locationOf(objType);
        var dir = objType.getAppliedDirective(DIR_ERROR);
        var handlersArg = dir.getArgument(ARG_HANDLERS);
        Object value = handlersArg.getValue();
        List<?> items = value instanceof List<?> l ? l : List.of(value);
        List<ErrorHandlerSpec> handlers = items.stream()
            .filter(v -> v instanceof Map)
            .map(v -> parseErrorHandlerSpec(asMap(v)))
            .toList();
        return new ErrorType(name, location, handlers);
    }

    private InputType buildInputType(GraphQLInputObjectType inputType) {
        String name = inputType.getName();
        SourceLocation location = locationOf(inputType);
        List<InputFieldSpec> fields = inputType.getFieldDefinitions().stream()
            .filter(f -> !f.hasAppliedDirective(DIR_NOT_GENERATED))
            .map(this::buildInputFieldSpec)
            .toList();
        return new InputType(name, location, fields);
    }

    private InputFieldSpec buildInputFieldSpec(GraphQLInputObjectField field) {
        String name = field.getName();
        GraphQLType type = field.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
        boolean orderBy = field.hasAppliedDirective(DIR_ORDER_BY);
        boolean hasFieldDir = field.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDir
            ? argString(field, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        boolean javaNamePresent = hasFieldDir && argString(field, DIR_FIELD, ARG_JAVA_NAME).isPresent();
        return new InputFieldSpec(name, typeName, nonNull, list, orderBy, columnName, javaNamePresent);
    }

    private ErrorHandlerSpec parseErrorHandlerSpec(Map<String, Object> item) {
        Object handlerRaw = item.get(ARG_HANDLER);
        ErrorHandlerType handlerType = handlerRaw != null
            ? ErrorHandlerType.valueOf(handlerRaw.toString())
            : null;
        if (handlerType == null) {
            throw new IllegalStateException("Missing required 'handler' field in @error handler");
        }
        String className = Optional.ofNullable(item.get(ARG_CLASS_NAME)).map(Object::toString).map(String::strip).orElse(null);
        String code = Optional.ofNullable(item.get(ARG_CODE)).map(Object::toString).map(String::strip).orElse(null);
        String sqlState = Optional.ofNullable(item.get(ARG_SQL_STATE)).map(Object::toString).map(String::strip).orElse(null);
        String matches = Optional.ofNullable(item.get(ARG_MATCHES)).map(Object::toString).map(String::strip).orElse(null);
        String description = Optional.ofNullable(item.get(ARG_DESCRIPTION)).map(Object::toString).map(String::strip).orElse(null);
        return new ErrorHandlerSpec(handlerType, className, code, sqlState, matches, description);
    }

    // ===== Object-return child field classification (P2+) =====

    /**
     * Classifies a child field on a {@link TableType} parent whose return type is an object, interface,
     * or union — not a scalar or enum. Called after the {@code @tableMethod} check in
     * {@link #classifyChildFieldOnTableType}.
     *
     * <p>P2 handles {@link TableField} and {@link NestingField}. Remaining variants
     * ({@code TableInterfaceField}, {@code InterfaceField}, {@code UnionField}, {@code ServiceField},
     * {@code ComputedField}) are added in P3.
     */
    private GraphitronField classifyObjectReturnChildField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);
        String rawTypeName = baseTypeName(fieldDef);

        // For connection types the element type is edges.node, not the connection wrapper type.
        String elementTypeName = isConnectionType(rawTypeName)
            ? connectionElementTypeName(rawTypeName)
            : rawTypeName;
        GraphitronType elementType = types.get(elementTypeName);

        if (elementType instanceof TableType) {
            return new TableField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)),
                parseReferencePath(fieldDef),
                new FieldConditionRef.NoFieldCondition(),
                fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY),
                parseArguments(fieldDef));
        }

        if (elementType instanceof TableInterfaceType) {
            return new TableInterfaceField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        if (elementType instanceof InterfaceType) {
            return new InterfaceField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        if (elementType instanceof UnionType) {
            return new UnionField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        // NestingField: a plain object type in the schema with no Graphitron classification.
        // Its fields are resolved from the same table context as the parent.
        if (schema.getType(elementTypeName) instanceof GraphQLObjectType && elementType == null) {
            return new NestingField(parentTypeName, name, location,
                new ReturnTypeRef.OtherReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        // ConstructorField is intentionally not classified here — its directive and generation
        // semantics are not yet defined (planned future deliverable). Fields that would logically
        // map to ConstructorField fall through to UnclassifiedField, which the validator rejects
        // with a clear error, making the gap visible and enforced rather than silently ignored.
        return new UnclassifiedField(parentTypeName, name, location);
    }

    // ===== Wrapper helpers =====

    /**
     * Builds a {@link FieldWrapper} from the return type shape of the field and any
     * {@code @defaultOrder} directive.
     *
     * <p>Connection is detected structurally — the return type must be a {@link GraphQLObjectType}
     * that has an {@code edges} field whose element type in turn has a {@code node} field. This is
     * more robust than the naming convention and is the authoritative Relay definition.
     *
     * <p>{@code @orderBy} enum value specs are not populated here — that is deferred to P4.
     */
    private FieldWrapper buildWrapper(GraphQLFieldDefinition fieldDef) {
        GraphQLType fieldType = fieldDef.getType();
        boolean outerNullable = !(fieldType instanceof GraphQLNonNull);
        GraphQLType unwrappedOnce = GraphQLTypeUtil.unwrapNonNull(fieldType);
        DefaultOrderSpec defaultOrder = parseDefaultOrderSpec(fieldDef);

        if (unwrappedOnce instanceof GraphQLList listType) {
            boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
            return new FieldWrapper.List(outerNullable, itemNullable, defaultOrder, List.of());
        }

        String typeName = baseTypeName(fieldDef);
        if (isConnectionType(typeName)) {
            boolean itemNullable = connectionItemNullable(typeName);
            return new FieldWrapper.Connection(outerNullable, itemNullable, defaultOrder, List.of());
        }

        return new FieldWrapper.Single(outerNullable);
    }

    /**
     * Returns {@code true} when {@code typeName} refers to a Relay connection type — i.e. when
     * the type is an object type whose {@code edges} field's element type has a {@code node} field.
     * This uses the schema structure rather than a naming convention.
     */
    private boolean isConnectionType(String typeName) {
        if (!(schema.getType(typeName) instanceof GraphQLObjectType connType)) return false;
        var edgesField = connType.getFieldDefinition("edges");
        if (edgesField == null) return false;
        var edgeType = GraphQLTypeUtil.unwrapAll(edgesField.getType());
        return edgeType instanceof GraphQLObjectType edgeObj && edgeObj.getFieldDefinition("node") != null;
    }

    /**
     * Returns the nullability of the {@code edges.node} field for a confirmed connection type.
     * {@code true} when the node field's type has no {@code !} wrapper (the item may be null).
     */
    private boolean connectionItemNullable(String connectionTypeName) {
        var connType = (GraphQLObjectType) schema.getType(connectionTypeName);
        var edgesField = connType.getFieldDefinition("edges");
        var edgeType = (GraphQLObjectType) GraphQLTypeUtil.unwrapAll(edgesField.getType());
        var nodeField = edgeType.getFieldDefinition("node");
        return !(nodeField.getType() instanceof GraphQLNonNull);
    }

    /**
     * Returns the name of the element type for a confirmed connection type by navigating
     * {@code edges.node}. This is the authoritative element type per the Relay spec.
     */
    private String connectionElementTypeName(String connectionTypeName) {
        var connType = (GraphQLObjectType) schema.getType(connectionTypeName);
        var edgesField = connType.getFieldDefinition("edges");
        var edgeType = (GraphQLObjectType) GraphQLTypeUtil.unwrapAll(edgesField.getType());
        var nodeField = edgeType.getFieldDefinition("node");
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(nodeField.getType())).getName();
    }

    /**
     * Parses the {@code @defaultOrder} directive on a field into a {@link DefaultOrderSpec}, or
     * returns {@code null} when the directive is absent.
     *
     * <p>For {@code index:} and {@code primaryKey:} variants the resulting {@link OrderSpec} is
     * a lookup-based spec ({@link OrderSpec.IndexOrder} / {@link OrderSpec.PrimaryKeyOrder}) that
     * is later resolved against the jOOQ catalog by the validator. For {@code fields:} the spec is
     * fully resolved at parse time.
     */
    private DefaultOrderSpec parseDefaultOrderSpec(GraphQLFieldDefinition fieldDef) {
        if (!fieldDef.hasAppliedDirective(DIR_DEFAULT_ORDER)) return null;
        var dir = fieldDef.getAppliedDirective(DIR_DEFAULT_ORDER);

        // direction has a default of ASC in the directive; absent arg means ASC.
        var dirArg = dir.getArgument(ARG_DIRECTION);
        String direction = "ASC";
        if (dirArg != null) {
            Object dirVal = dirArg.getValue();
            if (dirVal instanceof EnumValue ev) direction = ev.getName();
            else if (dirVal instanceof String s) direction = s;
        }

        var indexArg = dir.getArgument(ARG_INDEX);
        if (indexArg != null) {
            Object indexVal = indexArg.getValue();
            String indexName = indexVal instanceof StringValue sv ? sv.getValue().strip()
                : indexVal instanceof String s ? s.strip() : null;
            if (indexName != null) {
                return new DefaultOrderSpec(new OrderSpec.IndexOrder(indexName), direction);
            }
        }

        var pkArg = dir.getArgument(ARG_PRIMARY_KEY);
        boolean primaryKey = pkArg != null && (
            pkArg.getValue() instanceof BooleanValue bv ? bv.isValue()
            : Boolean.TRUE.equals(pkArg.getValue()));
        if (primaryKey) {
            return new DefaultOrderSpec(new OrderSpec.PrimaryKeyOrder(), direction);
        }

        var fieldsArg = dir.getArgument(ARG_FIELDS);
        if (fieldsArg != null) {
            Object value = fieldsArg.getValue();
            List<?> items = value instanceof List<?> l ? l : List.of(value);
            var sortFields = items.stream()
                .filter(v -> v instanceof Map)
                .map(v -> parseSortFieldSpec(asMap(v)))
                .toList();
            return new DefaultOrderSpec(new OrderSpec.FieldsOrder(sortFields), direction);
        }

        return null;
    }

    private SortFieldSpec parseSortFieldSpec(Map<String, Object> item) {
        // FieldSort uses `name` (database field name) and `collate` (optional collation).
        Object nameRaw = item.get(ARG_NAME);
        if (nameRaw == null) {
            throw new IllegalStateException("Missing required 'name' in FieldSort");
        }
        String columnName = nameRaw.toString().strip();
        String collation = Optional.ofNullable(item.get(ARG_COLLATE)).map(Object::toString).map(String::strip).orElse(null);
        return new SortFieldSpec(columnName, collation);
    }

    private KeyColumnRef resolveKeyColumn(String colName, Table<?> table) {
        if (table == null) {
            return new UnresolvedKeyColumn(colName);
        }
        return catalog.findColumn(table, colName)
            .<KeyColumnRef>map(e -> new ResolvedKeyColumn(colName, e.javaName()))
            .orElseGet(() -> new UnresolvedKeyColumn(colName));
    }

    // ===== Field classification =====

    private GraphitronField classifyField(GraphQLFieldDefinition fieldDef, String parentTypeName, GraphitronType parentType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return new NotGeneratedField(parentTypeName, name, location);
        }
        if (fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE)) {
            return new MultitableReferenceField(parentTypeName, name, location);
        }

        if (parentType instanceof RootType) {
            return classifyRootField(fieldDef, parentTypeName);
        }
        if (parentType instanceof TableType tableType) {
            return classifyChildFieldOnTableType(fieldDef, parentTypeName, tableType);
        }
        if (parentType instanceof ResultType) {
            return classifyChildFieldOnResultType(fieldDef, parentTypeName);
        }

        return new UnclassifiedField(parentTypeName, name, location);
    }

    // ===== Root field classification (P5) =====

    private GraphitronField classifyRootField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        if (parentTypeName.equals("Mutation")) {
            return classifyMutationField(fieldDef, parentTypeName);
        }
        if (parentTypeName.equals("Query")) {
            return classifyQueryField(fieldDef, parentTypeName);
        }
        return new UnclassifiedField(parentTypeName, fieldDef.getName(), locationOf(fieldDef));
    }

    private GraphitronField classifyQueryField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            return new QueryField.ServiceQueryField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)),
                parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF),
                parseArguments(fieldDef),
                parseContextArguments(fieldDef, DIR_SERVICE));
        }

        if (name.equals("_entities")) {
            return new QueryField.EntityQueryField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (name.equals("node")) {
            return new QueryField.NodeQueryField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (hasLookupKeyAnywhere(fieldDef)) {
            return new QueryField.LookupQueryField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)),
                parseArguments(fieldDef));
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            return new QueryField.TableMethodQueryField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)),
                parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF),
                parseArguments(fieldDef),
                parseContextArguments(fieldDef, DIR_TABLE_METHOD));
        }

        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
        GraphitronType elementType = types.get(elementTypeName);

        if (elementType instanceof TableType) {
            return new QueryField.TableQueryField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)),
                parseArguments(fieldDef));
        }
        if (elementType instanceof TableInterfaceType) {
            return new QueryField.TableInterfaceQueryField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)));
        }
        if (elementType instanceof InterfaceType) {
            return new QueryField.InterfaceQueryField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)));
        }
        if (elementType instanceof UnionType) {
            return new QueryField.UnionQueryField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        return new UnclassifiedField(parentTypeName, name, location);
    }

    private GraphitronField classifyMutationField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            return new MutationField.ServiceMutationField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)),
                parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF),
                parseArguments(fieldDef),
                parseContextArguments(fieldDef, DIR_SERVICE));
        }

        if (fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            String typeName = getMutationTypeName(fieldDef);
            if (typeName != null) {
                String rawReturn = baseTypeName(fieldDef);
                ReturnTypeRef returnType = resolveReturnType(rawReturn, buildWrapper(fieldDef));
                List<ArgumentSpec> arguments = parseArguments(fieldDef);
                return switch (typeName) {
                    case "INSERT" -> new MutationField.InsertMutationField(parentTypeName, name, location, returnType, arguments);
                    case "UPDATE" -> new MutationField.UpdateMutationField(parentTypeName, name, location, returnType, arguments);
                    case "DELETE" -> new MutationField.DeleteMutationField(parentTypeName, name, location, returnType, arguments);
                    case "UPSERT" -> new MutationField.UpsertMutationField(parentTypeName, name, location, returnType, arguments);
                    default       -> new UnclassifiedField(parentTypeName, name, location);
                };
            }
        }

        return new UnclassifiedField(parentTypeName, name, location);
    }

    /**
     * Returns {@code true} when {@code @lookupKey} appears on any direct argument of the field,
     * or on any field within an input-type argument (recursively). This is the field-level
     * classification signal — which specific argument carries it has no semantic significance.
     */
    private boolean hasLookupKeyAnywhere(GraphQLFieldDefinition fieldDef) {
        for (var arg : fieldDef.getArguments()) {
            if (arg.hasAppliedDirective(DIR_LOOKUP_KEY)) return true;
            String argTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(arg.getType())).getName();
            if (schema.getType(argTypeName) instanceof GraphQLInputObjectType inputType) {
                if (inputTypeHasLookupKey(inputType, 0)) return true;
            }
        }
        return false;
    }

    private boolean inputTypeHasLookupKey(GraphQLInputObjectType inputType, int depth) {
        if (depth > 10) return false; // guard against pathological nesting
        for (var field : inputType.getFieldDefinitions()) {
            if (field.hasAppliedDirective(DIR_LOOKUP_KEY)) return true;
            String fieldTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(field.getType())).getName();
            if (schema.getType(fieldTypeName) instanceof GraphQLInputObjectType nested) {
                if (inputTypeHasLookupKey(nested, depth + 1)) return true;
            }
        }
        return false;
    }

    private String getMutationTypeName(GraphQLFieldDefinition fieldDef) {
        var dir = fieldDef.getAppliedDirective(DIR_MUTATION);
        if (dir == null) return null;
        var arg = dir.getArgument(ARG_TYPE_NAME);
        if (arg == null) return null;
        Object value = arg.getValue();
        if (value instanceof EnumValue ev) return ev.getName();
        if (value instanceof String s) return s;
        return null;
    }

    private GraphitronField classifyChildFieldOnResultType(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            return new ServiceField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)),
                parseReferencePath(fieldDef),
                parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF),
                parseArguments(fieldDef),
                parseContextArguments(fieldDef, DIR_SERVICE));
        }

        String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        return new PropertyField(parentTypeName, name, location, columnName);
    }

    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableType tableType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            return new ServiceField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)),
                parseReferencePath(fieldDef),
                parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF),
                parseArguments(fieldDef),
                parseContextArguments(fieldDef, DIR_SERVICE));
        }

        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) {
            return new ComputedField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)),
                parseReferencePath(fieldDef));
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            return new TableMethodField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)),
                parseReferencePath(fieldDef),
                parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF),
                parseArguments(fieldDef),
                parseContextArguments(fieldDef, DIR_TABLE_METHOD));
        }

        if (!isScalarOrEnum(fieldDef)) {
            return classifyObjectReturnChildField(fieldDef, parentTypeName);
        }

        if (fieldDef.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<String> typeName = argString(fieldDef, DIR_NODE_ID, ARG_TYPE_NAME);
            if (typeName.isPresent()) {
                ReturnTypeRef targetType = resolveReturnType(typeName.get(), new FieldWrapper.Single(true));
                ResolvedTable parentTable = tableType.table() instanceof ResolvedTable rt ? rt : null;
                NodeTypeRef nodeType = resolveNodeType(typeName.get());
                List<ReferencePathElementRef> path = parseReferencePath(fieldDef);
                return new NodeIdReferenceField(parentTypeName, name, location, typeName.get(), targetType, parentTable, nodeType, path);
            } else {
                return new NodeIdField(parentTypeName, name, location, tableType.node());
            }
        }

        boolean hasFieldDirective = fieldDef.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDirective
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        boolean javaNamePresent = hasFieldDirective
            && argString(fieldDef, DIR_FIELD, ARG_JAVA_NAME).isPresent();

        if (fieldDef.hasAppliedDirective(DIR_REFERENCE)) {
            List<ReferencePathElementRef> path = parseReferencePath(fieldDef);
            ColumnRef column = resolveColumnForReference(columnName, path, tableType);
            return new ColumnReferenceField(parentTypeName, name, location, columnName, column, path, javaNamePresent);
        }

        ColumnRef column = resolveColumn(columnName, tableType);
        return new ColumnField(parentTypeName, name, location, columnName, column, javaNamePresent);
    }

    private ReturnTypeRef resolveReturnType(String targetTypeName, FieldWrapper wrapper) {
        GraphitronType target = types.get(targetTypeName);
        if (target instanceof TableType tt) return new ReturnTypeRef.TableBoundReturnType(targetTypeName, tt.table(), wrapper);
        // OtherReturnType covers:
        //  - classified non-table types (ResultType, InputType, interfaces, unions)
        //  - scalars and enums (not classified by Graphitron but valid leaf types)
        //  - directive-argument type names that don't match any schema type (e.g. @nodeId(typeName:))
        //    — these are not validated by graphql-java, so the type may genuinely not exist;
        //    downstream validators (e.g. UnresolvedNodeType) catch those errors.
        return new ReturnTypeRef.OtherReturnType(targetTypeName, wrapper);
    }

    private NodeTypeRef resolveNodeType(String targetTypeName) {
        if (schema.getType(targetTypeName) == null) return new NodeTypeRef.NotFoundNodeType();
        GraphitronType target = types.get(targetTypeName);
        if (target instanceof TableType tt && tt.node() instanceof NodeDirective nd)
            return new ResolvedNodeType(nd);
        return new NodeTypeRef.NoNodeDirectiveType();
    }

    private boolean isScalarOrEnum(GraphQLFieldDefinition fieldDef) {
        var baseType = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
        return baseType instanceof GraphQLScalarType || baseType instanceof GraphQLEnumType;
    }

    private String baseTypeName(GraphQLFieldDefinition fieldDef) {
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
    }

    private ColumnRef resolveColumn(String columnName, TableType tableType) {
        if (!(tableType.table() instanceof ResolvedTable resolvedTable)) {
            return new UnresolvedColumn();
        }
        return resolveColumnInTable(columnName, resolvedTable.table());
    }

    private ColumnRef resolveColumnForReference(String columnName, List<ReferencePathElementRef> path, TableType sourceType) {
        if (!(sourceType.table() instanceof ResolvedTable rt)) {
            return new UnresolvedColumn();
        }
        var current = rt.table();
        for (var step : path) {
            if (step instanceof FkRef fk) {
                current = fk.key().getKey().getTable();
            } else {
                return new UnresolvedColumn();
            }
        }
        return resolveColumnInTable(columnName, current);
    }

    private ColumnRef resolveColumnInTable(String columnName, Table<?> table) {
        return catalog.findColumn(table, columnName)
            .<ColumnRef>map(e -> new ResolvedColumn(e.javaName(), e.column()))
            .orElseGet(UnresolvedColumn::new);
    }

    // ===== Argument parsing =====

    /**
     * Parses every argument on {@code fieldDef} into an {@link ArgumentSpec}.
     */
    private List<ArgumentSpec> parseArguments(GraphQLFieldDefinition fieldDef) {
        return fieldDef.getArguments().stream()
            .map(this::buildArgumentSpec)
            .toList();
    }

    private ArgumentSpec buildArgumentSpec(GraphQLArgument arg) {
        GraphQLType type = arg.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
        boolean orderBy = arg.hasAppliedDirective(DIR_ORDER_BY);
        boolean conditionArg = arg.hasAppliedDirective(DIR_CONDITION);
        return new ArgumentSpec(arg.getName(), typeName, nonNull, list, orderBy, conditionArg);
    }

    /**
     * Returns an {@link ExternalRef} from the {@code ExternalCodeReference} input object at
     * argument {@code argName} of the given directive on {@code fieldDef}.
     */
    private ExternalRef parseExternalRef(GraphQLFieldDefinition fieldDef, String directiveName, String argName) {
        var dir = fieldDef.getAppliedDirective(directiveName);
        if (dir == null) return null;
        var arg = dir.getArgument(argName);
        if (arg == null) return null;
        Map<String, Object> ref = asMap(arg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(null);
        return new ExternalRef(className, methodName);
    }

    /**
     * Returns the {@code contextArguments} list from the {@code @service} or {@code @tableMethod}
     * directive on {@code fieldDef}, or an empty list when the directive is absent or the argument
     * is not set.
     */
    private List<String> parseContextArguments(GraphQLFieldDefinition fieldDef, String directiveName) {
        return argStringList(fieldDef, directiveName, ARG_CONTEXT_ARGUMENTS);
    }

    // ===== Reference path parsing =====

    private List<ReferencePathElementRef> parseReferencePath(GraphQLFieldDefinition fieldDef) {
        var directive = fieldDef.getAppliedDirective(DIR_REFERENCE);
        if (directive == null) return List.of();

        var pathArg = directive.getArgument(ARG_PATH);
        if (pathArg == null) return List.of();

        Object pathValue = pathArg.getValue();
        List<?> elements = pathValue instanceof List<?> l ? l : List.of(pathValue);

        return elements.stream()
            .filter(v -> v instanceof Map)
            .map(v -> parsePathElement(asMap(v)))
            .toList();
    }

    private ReferencePathElementRef parsePathElement(Map<String, Object> element) {
        Object keyRaw = element.get(ARG_KEY);
        Object conditionRaw = element.get(ARG_CONDITION);

        Optional<String> keyName = Optional.ofNullable(keyRaw)
            .map(Object::toString)
            .filter(s -> !s.isBlank());
        boolean hasCondition = conditionRaw instanceof Map;

        if (keyName.isPresent() && !hasCondition) {
            return resolveKey(keyName.get());
        }
        if (keyName.isPresent()) {
            Optional<ForeignKey<?, ?>> fk = catalog.findForeignKey(keyName.get());
            Map<String, Object> condMap = hasCondition ? asMap(conditionRaw) : Map.of();
            String condName = extractConditionQualifiedName(condMap);
            MethodRef resolved = resolveConditionRef(condMap);
            if (fk.isPresent() && resolved != null) {
                return new FkWithConditionRef(fk.get(), resolved);
            }
            if (fk.isPresent()) {
                return new UnresolvedConditionRef(condName);
            }
            if (resolved != null) {
                return new UnresolvedKeyRef(keyName.get());
            }
            return new UnresolvedKeyAndConditionRef(keyName.get(), condName);
        }
        if (hasCondition) {
            Map<String, Object> condMap = asMap(conditionRaw);
            MethodRef resolved = resolveConditionRef(condMap);
            if (resolved != null) {
                return new ConditionOnlyRef(resolved);
            }
            return new UnresolvedConditionRef(extractConditionQualifiedName(condMap));
        }
        // A path element with neither 'key' nor 'condition' is structurally invalid.
        return new UnresolvedKeyRef("<empty path element — missing 'key' and 'condition'>");
    }

    private ReferencePathElementRef resolveKey(String keyName) {
        return catalog.findForeignKey(keyName)
            .<ReferencePathElementRef>map(FkRef::new)
            .orElseGet(() -> new UnresolvedKeyRef(keyName));
    }

    /**
     * Condition resolution via reflection is implemented in a later deliverable (P3).
     * Returns {@code null} to signal that the condition is unresolved.
     */
    private MethodRef resolveConditionRef(Map<String, Object> conditionMap) {
        return null;
    }

    private String extractConditionQualifiedName(Map<String, Object> conditionMap) {
        Object name = conditionMap.get(ARG_NAME);
        return name != null ? name.toString() : "unknown";
    }

    // ===== Directive reading helpers =====

    /**
     * Returns the stripped String value of an applied directive argument, if present.
     * Handles both literal {@link StringValue} (as stored by {@link SchemaGenerator}) and
     * already-coerced {@link String} values (defensive for future graphql-java versions).
     */
    private Optional<String> argString(GraphQLDirectiveContainer container, String directive, String arg) {
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
    private List<String> argStringList(GraphQLDirectiveContainer container, String directive, String arg) {
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
     * Casts an object to a {@code Map<String, Object>}. Used when processing input object values
     * returned by {@link GraphQLAppliedDirectiveArgument#getValue()} after graphql-java coercion.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return (Map<String, Object>) v;
    }

    // ===== Registry validation =====

    /**
     * Validates that every directive name and argument name used by this builder actually exists
     * in the assembled {@link GraphQLSchema}. Throws {@link IllegalStateException} if the
     * schema is out of sync with the constants declared in this class.
     */
    private void validateDirectiveSchema() {
        assertDirective(DIR_TABLE, ARG_NAME);
        assertDirective(DIR_RECORD);
        assertDirective(DIR_DISCRIMINATE, ARG_ON);
        assertDirective(DIR_DISCRIMINATOR, ARG_VALUE);
        assertDirective(DIR_NODE, ARG_TYPE_ID, ARG_KEY_COLUMNS);
        assertDirective(DIR_NOT_GENERATED);
        assertDirective(DIR_MULTITABLE_REFERENCE);
        assertDirective(DIR_NODE_ID, ARG_TYPE_NAME);
        assertDirective(DIR_FIELD, ARG_NAME, ARG_JAVA_NAME);
        assertDirective(DIR_REFERENCE, ARG_PATH);
        assertDirective(DIR_ERROR, ARG_HANDLERS);
        assertDirective(DIR_TABLE_METHOD);
        assertDirective(DIR_DEFAULT_ORDER);
        assertDirective(DIR_SPLIT_QUERY);
        assertDirective(DIR_SERVICE);
        assertDirective(DIR_EXTERNAL_FIELD);
        assertDirective(DIR_LOOKUP_KEY);
        assertDirective(DIR_ORDER_BY);
        assertDirective(DIR_CONDITION);
        assertDirective(DIR_MUTATION, ARG_TYPE_NAME);
    }

    private void assertDirective(String name, String... args) {
        var def = schema.getDirective(name);
        if (def == null) {
            throw new IllegalStateException("Expected directive @" + name + " in schema but it was not found.");
        }
        var argNames = def.getArguments().stream()
            .map(GraphQLArgument::getName)
            .collect(Collectors.toSet());
        for (var arg : args) {
            if (!argNames.contains(arg)) {
                throw new IllegalStateException(
                    "Expected argument '" + arg + "' on directive @" + name + " but it was not found.");
            }
        }
    }

    // ===== Source location helpers =====

    private static SourceLocation locationOf(GraphQLObjectType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    private static SourceLocation locationOf(GraphQLInterfaceType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    private static SourceLocation locationOf(GraphQLUnionType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    private static SourceLocation locationOf(GraphQLFieldDefinition field) {
        var def = field.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    private static SourceLocation locationOf(GraphQLInputObjectType type) {
        var def = type.getDefinition();
        return def != null ? def.getSourceLocation() : null;
    }

    /** Dispatches to the correct overload for any {@link GraphQLNamedType}. */
    private static SourceLocation locationOf(GraphQLNamedType namedType) {
        return switch (namedType) {
            case GraphQLObjectType t    -> locationOf(t);
            case GraphQLInterfaceType t -> locationOf(t);
            case GraphQLUnionType t     -> locationOf(t);
            default                     -> null;
        };
    }
}
