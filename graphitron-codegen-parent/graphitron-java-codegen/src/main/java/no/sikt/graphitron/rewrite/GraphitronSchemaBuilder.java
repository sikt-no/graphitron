package no.sikt.graphitron.rewrite;

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
import no.sikt.graphitron.rewrite.type.ErrorHandlerSpec;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.rewrite.field.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.field.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.field.ArgumentSpec;
import no.sikt.graphitron.rewrite.field.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.field.ChildField.InterfaceField;
import no.sikt.graphitron.rewrite.field.ChildField.MultitableReferenceField;
import no.sikt.graphitron.rewrite.field.ChildField.NestingField;
import no.sikt.graphitron.rewrite.field.ChildField.NodeIdField;
import no.sikt.graphitron.rewrite.field.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.rewrite.field.ChildField.PropertyField;
import no.sikt.graphitron.rewrite.field.ChildField.RecordField;
import no.sikt.graphitron.rewrite.field.ChildField.RecordLookupTableField;
import no.sikt.graphitron.rewrite.field.ChildField.RecordTableField;
import no.sikt.graphitron.rewrite.field.ChildField.ServiceRecordField;
import no.sikt.graphitron.rewrite.field.ChildField.ServiceTableField;
import no.sikt.graphitron.rewrite.field.ChildField.TableField;
import no.sikt.graphitron.rewrite.field.ChildField.TableInterfaceField;
import no.sikt.graphitron.rewrite.field.ChildField.TableMethodField;
import no.sikt.graphitron.rewrite.field.ChildField.UnionField;
import no.sikt.graphitron.rewrite.field.MutationField;
import no.sikt.graphitron.rewrite.field.QueryField;
import no.sikt.graphitron.rewrite.field.DefaultOrderSpec;
import no.sikt.graphitron.rewrite.field.FieldWrapper;
import no.sikt.graphitron.rewrite.field.FieldConditionRef;
import no.sikt.graphitron.rewrite.field.OrderSpec;
import no.sikt.graphitron.rewrite.field.SortFieldSpec;
import no.sikt.graphitron.rewrite.field.ColumnRef;
import no.sikt.graphitron.rewrite.field.ColumnRef.ResolvedColumn;
import no.sikt.graphitron.rewrite.field.ColumnRef.UnresolvedColumn;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.rewrite.field.ArgumentRef;
import no.sikt.graphitron.rewrite.field.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.field.ExternalRef;
import no.sikt.graphitron.rewrite.field.MethodRef;
import no.sikt.graphitron.rewrite.field.ServiceMethodRef;
import no.sikt.graphitron.rewrite.field.SourcesRef;
import no.sikt.graphitron.rewrite.field.NodeTypeRef;
import no.sikt.graphitron.rewrite.field.NodeTypeRef.ResolvedNodeType;
import no.sikt.graphitron.rewrite.field.ReturnTypeRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.UnresolvedConditionRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.UnresolvedKeyAndConditionRef;
import no.sikt.graphitron.rewrite.field.ReferencePathElementRef.UnresolvedKeyRef;
import no.sikt.graphitron.rewrite.type.GraphitronType;
import no.sikt.graphitron.rewrite.type.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.type.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.type.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.type.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.type.InputFieldRef;
import no.sikt.graphitron.rewrite.type.InputFieldRef.TableInputField;
import no.sikt.graphitron.rewrite.type.InputFieldRef.UnresolvedInputField;
import no.sikt.graphitron.rewrite.type.InputFieldSpec;
import no.sikt.graphitron.rewrite.type.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.type.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.type.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.type.KeyColumnRef;
import no.sikt.graphitron.rewrite.type.KeyColumnRef.ResolvedKeyColumn;
import no.sikt.graphitron.rewrite.type.KeyColumnRef.UnresolvedKeyColumn;
import no.sikt.graphitron.rewrite.type.NodeRef;
import no.sikt.graphitron.rewrite.type.NodeRef.NoNode;
import no.sikt.graphitron.rewrite.type.NodeRef.NodeDirective;
import no.sikt.graphitron.rewrite.type.ParticipantRef;
import no.sikt.graphitron.rewrite.type.ParticipantRef.BoundParticipant;
import no.sikt.graphitron.rewrite.type.ParticipantRef.UnboundParticipant;
import no.sikt.graphitron.rewrite.type.TableRef;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;
import no.sikt.graphitron.rewrite.type.TableRef.UnresolvedTable;
import org.jooq.ForeignKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * Optimistically promotes an {@link InputType} (no {@code @table}) to a
     * {@link TableInputType} using {@code rt} as the implied table, or detects and records a
     * conflict when the same input type has already been promoted with a different table.
     *
     * <p>Call this for every input-type argument encountered while building any field that has a
     * resolved return table. The promotion/demotion is a side-effect on {@link #types}: no
     * separate post-processing pass is needed.
     */
    private void resolveInputTypeImplicitly(String typeName, ResolvedTable rt) {
        if (rt == null) return;
        var current = types.get(typeName);
        if (current instanceof InputType it) {
            types.put(typeName, promoteToTableInputType(it, rt));
        } else if (current instanceof TableInputType tit
                && tit.table() instanceof ResolvedTable existing
                && !existing.tableName().equalsIgnoreCase(rt.tableName())) {
            types.put(typeName, new GraphitronType.UnclassifiedType(typeName, tit.location(),
                "used as an argument on fields with conflicting return tables: '"
                + existing.tableName() + "' and '" + rt.tableName() + "'"));
        }
    }

    /**
     * Resolves all input-type arguments in {@code args} against the given {@code returnType}.
     * For each arg whose type is a known {@link InputType}, calls
     * {@link #resolveInputTypeImplicitly} to promote or detect conflicts.
     */
    private void resolveInputTypeArgs(List<ArgumentSpec> args, ReturnTypeRef returnType) {
        if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) return;
        if (!(tb.table() instanceof ResolvedTable rt)) return;
        for (var arg : args) {
            if (types.containsKey(arg.typeName())) {
                resolveInputTypeImplicitly(arg.typeName(), rt);
            }
        }
    }

    /**
     * Promotes an {@link InputType} to a {@link TableInputType} by resolving each field's column
     * against the given {@link ResolvedTable}.
     */
    private TableInputType promoteToTableInputType(InputType inputType, ResolvedTable resolvedTable) {
        List<InputFieldRef> resolvedFields = inputType.fields().stream()
            .map(spec -> catalog.findColumn(resolvedTable.tableName(), spec.columnName())
                .<InputFieldRef>map(e -> new TableInputField(
                    spec.name(), spec.typeName(), spec.nonNull(), spec.list(),
                    resolvedTable, e.javaName(), e.columnClass()))
                .orElseGet(() -> new UnresolvedInputField(
                    spec.name(), spec.typeName(), spec.nonNull(), spec.list(),
                    spec.columnName())))
            .toList();
        return new TableInputType(inputType.name(), inputType.location(), resolvedTable, resolvedFields);
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
            case TableInterfaceType tit  -> enrichTableInterfaceType(tit, result);
            case InterfaceType it        -> enrichInterfaceType(it, result);
            case UnionType ut            -> enrichUnionType(ut, result);
            case TableType ignored       -> type;
            case ResultType ignored      -> type;
            case RootType ignored        -> type;
            case ErrorType ignored       -> type;
            case InputType ignored       -> type;
            case TableInputType ignored  -> type;
            case UnclassifiedType ignored -> type;
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
            String typeConflict = detectTypeDirectiveConflict(objType);
            if (typeConflict != null) {
                return new GraphitronType.UnclassifiedType(name, location, typeConflict);
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
            .<TableRef>map(e -> {
                var pk = e.table().getPrimaryKey();
                List<String> pkCols = pk != null
                    ? pk.getFields().stream().map(f -> f.getName()).toList()
                    : List.of();
                List<String> pkJavaTypes = pk != null
                    ? pk.getFields().stream().map(f -> f.getType().getName()).toList()
                    : List.of();
                return new ResolvedTable(sqlName, e.javaFieldName(), e.table().getClass().getSimpleName(), pk != null, pkCols, pkJavaTypes);
            })
            .orElseGet(() -> new UnresolvedTable(sqlName));
    }

    private NodeRef buildNodeRef(GraphQLObjectType objType, TableRef tableRef) {
        if (!objType.hasAppliedDirective(DIR_NODE)) {
            return new NoNode();
        }
        String typeId = argString(objType, DIR_NODE, ARG_TYPE_ID).orElse(null);
        List<String> keyColumnNames = argStringList(objType, DIR_NODE, ARG_KEY_COLUMNS);
        String resolvedTableSqlName = tableRef instanceof ResolvedTable rt ? rt.tableName() : null;
        List<KeyColumnRef> keyColumns = keyColumnNames.stream()
            .map(colName -> resolveKeyColumn(colName, resolvedTableSqlName))
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

    private GraphitronType buildInputType(GraphQLInputObjectType inputType) {
        String name = inputType.getName();
        SourceLocation location = locationOf(inputType);
        if (inputType.hasAppliedDirective(DIR_TABLE)) {
            String tableName = argString(inputType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
            TableRef tableRef = resolveTable(tableName);
            String resolvedTableSqlName = tableRef instanceof ResolvedTable rt ? rt.tableName() : null;
            List<InputFieldRef> fields = inputType.getFieldDefinitions().stream()
                .filter(f -> !f.hasAppliedDirective(DIR_NOT_GENERATED))
                .map(f -> buildInputFieldRef(f, tableRef instanceof ResolvedTable rt ? rt : null, resolvedTableSqlName))
                .toList();
            return new TableInputType(name, location, tableRef, fields);
        }
        List<InputFieldSpec> fields = inputType.getFieldDefinitions().stream()
            .filter(f -> !f.hasAppliedDirective(DIR_NOT_GENERATED))
            .map(this::buildInputFieldSpec)
            .toList();
        return new InputType(name, location, fields);
    }

    private InputFieldRef buildInputFieldRef(GraphQLInputObjectField field, ResolvedTable resolvedTable, String tableSqlName) {
        String name = field.getName();
        GraphQLType type = field.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
        boolean hasFieldDir = field.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDir
            ? argString(field, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        if (resolvedTable == null || tableSqlName == null) {
            return new UnresolvedInputField(name, typeName, nonNull, list, columnName);
        }
        return catalog.findColumn(tableSqlName, columnName)
            .<InputFieldRef>map(e -> new TableInputField(name, typeName, nonNull, list, resolvedTable, e.javaName(), e.columnClass()))
            .orElseGet(() -> new UnresolvedInputField(name, typeName, nonNull, list, columnName));
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
            var returnType = (ReturnTypeRef.TableBoundReturnType) resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            var args = parseArguments(fieldDef);
            resolveInputTypeArgs(args, returnType);
            var referencePath = parseReferencePath(fieldDef);
            boolean hasSplitQuery = fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY);
            boolean hasLookupKey  = hasLookupKeyAnywhere(fieldDef);
            if (hasSplitQuery && hasLookupKey) {
                return new no.sikt.graphitron.rewrite.field.ChildField.SplitLookupTableField(
                    parentTypeName, name, location, returnType, referencePath, args);
            }
            if (!hasSplitQuery && hasLookupKey) {
                return new no.sikt.graphitron.rewrite.field.ChildField.LookupTableField(
                    parentTypeName, name, location, returnType, referencePath, args);
            }
            if (hasSplitQuery) {
                return new no.sikt.graphitron.rewrite.field.ChildField.SplitTableField(
                    parentTypeName, name, location, returnType,
                    referencePath, new FieldConditionRef.NoFieldCondition(), args);
            }
            return new TableField(parentTypeName, name, location,
                returnType, referencePath, new FieldConditionRef.NoFieldCondition(), args);
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
        return new UnclassifiedField(parentTypeName, name, location,
            "ConstructorField (child field on @table type returning a @record type) is not yet supported");
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

    private KeyColumnRef resolveKeyColumn(String colName, String tableSqlName) {
        if (tableSqlName == null) {
            return new UnresolvedKeyColumn(colName);
        }
        return catalog.findColumn(tableSqlName, colName)
            .<KeyColumnRef>map(e -> new ResolvedKeyColumn(colName, e.javaName()))
            .orElseGet(() -> new UnresolvedKeyColumn(colName));
    }

    // ===== Field classification =====

    private GraphitronField classifyField(GraphQLFieldDefinition fieldDef, String parentTypeName, GraphitronType parentType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        // Detect conflicts among the child-field exclusive directives before the @notGenerated and
        // @multitableReference early-returns — those returns would otherwise silently mask a
        // conflicting directive on the same field.
        if (!(parentType instanceof RootType)) {
            String conflict = detectChildFieldConflict(fieldDef);
            if (conflict != null) {
                return new UnclassifiedField(parentTypeName, name, location, conflict);
            }
        }

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

        return new UnclassifiedField(parentTypeName, name, location,
            "parent type '" + parentTypeName + "' has no supported Graphitron classification");
    }

    // ===== Root field classification (P5) =====

    private GraphitronField classifyRootField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        if (parentTypeName.equals("Mutation")) {
            return classifyMutationField(fieldDef, parentTypeName);
        }
        if (parentTypeName.equals("Query")) {
            return classifyQueryField(fieldDef, parentTypeName);
        }
        return new UnclassifiedField(parentTypeName, fieldDef.getName(), locationOf(fieldDef),
            "fields on '" + parentTypeName + "' (Subscription is not supported)");
    }

    private GraphitronField classifyQueryField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        String conflict = detectQueryFieldConflict(fieldDef);
        if (conflict != null) {
            return new UnclassifiedField(parentTypeName, name, location, conflict);
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            ReturnTypeRef returnType = resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<ArgumentSpec> args = parseArguments(fieldDef);
            List<String> contextArgs = parseContextArguments(fieldDef, DIR_SERVICE);
            if (returnType instanceof ReturnTypeRef.TableBoundReturnType tb) {
                return new QueryField.QueryServiceTableField(parentTypeName, name, location, tb, serviceRef, args, contextArgs);
            }
            return new QueryField.QueryServiceRecordField(parentTypeName, name, location, returnType, serviceRef, args, contextArgs);
        }

        if (name.equals("_entities")) {
            return new QueryField.QueryEntityField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (name.equals("node")) {
            return new QueryField.QueryNodeField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (hasLookupKeyAnywhere(fieldDef)) {
            var returnType = resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef));
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location,
                    "@lookupKey requires a @table-annotated return type");
            }
            var rt = tb.table() instanceof TableRef.ResolvedTable r ? r : null;
            var arguments = parseArguments(fieldDef).stream()
                .map(arg -> buildLookupArg(arg, rt))
                .toList();
            return new QueryField.QueryLookupTableField(parentTypeName, name, location, tb, arguments);
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            var returnType = resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location,
                    "@tableMethod requires a @table-annotated return type");
            }
            var args = parseArguments(fieldDef);
            resolveInputTypeArgs(args, tb);
            return new QueryField.QueryTableMethodTableField(parentTypeName, name, location,
                tb,
                parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF),
                args,
                parseContextArguments(fieldDef, DIR_TABLE_METHOD));
        }

        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
        GraphitronType elementType = types.get(elementTypeName);

        if (elementType instanceof TableType) {
            var returnType = (ReturnTypeRef.TableBoundReturnType) resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            var args = parseArguments(fieldDef);
            resolveInputTypeArgs(args, returnType);
            return new QueryField.QueryTableField(parentTypeName, name, location,
                returnType,
                args);
        }
        if (elementType instanceof TableInterfaceType) {
            return new QueryField.QueryTableInterfaceField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)));
        }
        if (elementType instanceof InterfaceType) {
            return new QueryField.QueryInterfaceField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)));
        }
        if (elementType instanceof UnionType) {
            return new QueryField.QueryUnionField(parentTypeName, name, location,
                resolveReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        return new UnclassifiedField(parentTypeName, name, location,
            "return type '" + elementTypeName + "' is not a @table, interface, or union Graphitron type; " +
            "@service, @lookupKey, and @tableMethod are all absent");
    }

    private GraphitronField classifyMutationField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE) && fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            return new UnclassifiedField(parentTypeName, name, location,
                "@" + DIR_SERVICE + ", @" + DIR_MUTATION + " are mutually exclusive");
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            ReturnTypeRef returnType = resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef));
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<ArgumentSpec> args = parseArguments(fieldDef);
            List<String> contextArgs = parseContextArguments(fieldDef, DIR_SERVICE);
            if (returnType instanceof ReturnTypeRef.TableBoundReturnType tb) {
                return new MutationField.MutationServiceTableField(parentTypeName, name, location, tb, serviceRef, args, contextArgs);
            }
            return new MutationField.MutationServiceRecordField(parentTypeName, name, location, returnType, serviceRef, args, contextArgs);
        }

        if (fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            String typeName = getMutationTypeName(fieldDef);
            if (typeName != null) {
                String rawReturn = baseTypeName(fieldDef);
                ReturnTypeRef returnType = resolveReturnType(rawReturn, buildWrapper(fieldDef));
                List<ArgumentSpec> arguments = parseArguments(fieldDef);
                return switch (typeName) {
                    case "INSERT" -> new MutationField.MutationInsertTableField(parentTypeName, name, location, returnType, arguments);
                    case "UPDATE" -> new MutationField.MutationUpdateTableField(parentTypeName, name, location, returnType, arguments);
                    case "DELETE" -> new MutationField.MutationDeleteTableField(parentTypeName, name, location, returnType, arguments);
                    case "UPSERT" -> new MutationField.MutationUpsertTableField(parentTypeName, name, location, returnType, arguments);
                    default       -> new UnclassifiedField(parentTypeName, name, location,
                        "unknown @mutation(typeName:) value '" + typeName + "'");
                };
            }
        }

        return new UnclassifiedField(parentTypeName, name, location,
            "@" + DIR_SERVICE + " and @" + DIR_MUTATION + " are both absent on this mutation field");
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

    /**
     * Classifies one argument of a {@code QueryLookupTableField} into the appropriate
     * {@link ArgumentRef} variant.
     *
     * <p>Arguments with {@code @orderBy} or {@code @condition} become
     * {@link ArgumentRef.InputTypeArg.PlainInputTypeArg} with the corresponding flag set — the
     * validator rejects them on lookup fields.
     * Arguments whose type is a user-defined input type (any entry in {@code types}) are resolved
     * via {@link #resolveInputTypeImplicitly}: if the type is or becomes a
     * {@link TableInputType}, a {@link ArgumentRef.InputTypeArg.TableInputTypeArg} is returned;
     * otherwise a {@link ArgumentRef.InputTypeArg.PlainInputTypeArg}.
     * Remaining (scalar) arguments are resolved against the return table via the catalog.
     */
    private ArgumentRef buildLookupArg(ArgumentSpec arg, TableRef.ResolvedTable rt) {
        if (arg.conditionArg()) {
            return new ArgumentRef.UnclassifiedArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list(),
                "@condition is only supported on field definitions, not on arguments");
        }
        if (arg.orderBy()) {
            return resolveOrderByArg(arg);
        }
        if (types.containsKey(arg.typeName())) {
            resolveInputTypeImplicitly(arg.typeName(), rt);
            return types.get(arg.typeName()) instanceof TableInputType
                ? new ArgumentRef.InputTypeArg.TableInputTypeArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list())
                : new ArgumentRef.InputTypeArg.PlainInputTypeArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list());
        }
        // Scalar arg — resolve against the return type's table
        if (rt == null) {
            return new ArgumentRef.ScalarArg.UnboundScalarArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list(), arg.columnName());
        }
        return catalog.findColumn(rt.tableName(), arg.columnName())
            .<ArgumentRef>map(e -> new ArgumentRef.ScalarArg.ColumnArg(
                arg.name(), arg.typeName(), arg.nonNull(), arg.list(), e.javaName(), e.columnClass()))
            .orElseGet(() -> new ArgumentRef.ScalarArg.UnboundScalarArg(
                arg.name(), arg.typeName(), arg.nonNull(), arg.list(), arg.columnName()));
    }

    /**
     * Resolves an {@code @orderBy} argument to an {@link ArgumentRef.InputTypeArg.OrderByArg}.
     *
     * <p>Looks up the argument's input type in the schema and expects it to contain exactly one
     * enum field whose values carry {@code @order} directives (the sort field) and exactly one
     * other enum field (the direction field). Returns an {@link ArgumentRef.UnclassifiedArg} with
     * a descriptive reason if the structure is invalid.
     */
    private ArgumentRef resolveOrderByArg(ArgumentSpec arg) {
        var rawType = schema.getType(arg.typeName());
        if (!(rawType instanceof GraphQLInputObjectType inputType)) {
            return new ArgumentRef.UnclassifiedArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list(),
                "@orderBy argument type '" + arg.typeName() + "' is not an input type");
        }
        String sortFieldName = null;
        String directionFieldName = null;
        for (var field : inputType.getFieldDefinitions()) {
            var fieldType = GraphQLTypeUtil.unwrapNonNull(field.getType());
            if (!(fieldType instanceof GraphQLEnumType enumType)) continue;
            boolean isSortEnum = enumType.getValues().stream()
                .anyMatch(v -> v.hasAppliedDirective("order"));
            if (isSortEnum) {
                if (sortFieldName != null) {
                    return new ArgumentRef.UnclassifiedArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list(),
                        "@orderBy input type '" + arg.typeName() + "' must have exactly one sort enum field, but found multiple");
                }
                sortFieldName = field.getName();
            } else {
                if (directionFieldName != null) {
                    return new ArgumentRef.UnclassifiedArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list(),
                        "@orderBy input type '" + arg.typeName() + "' must have exactly one direction field, but found multiple");
                }
                directionFieldName = field.getName();
            }
        }
        if (sortFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list(),
                "@orderBy input type '" + arg.typeName() + "' has no sort enum field (no enum values with @order)");
        }
        if (directionFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list(),
                "@orderBy input type '" + arg.typeName() + "' has no direction field");
        }
        return new ArgumentRef.InputTypeArg.OrderByArg(arg.name(), arg.typeName(), arg.nonNull(), arg.list(),
            sortFieldName, directionFieldName);
    }

    // ===== Conflict detection helpers =====
    // Each method returns a human-readable reason string when mutually exclusive directives are
    // found together, or {@code null} when no conflict exists. Callers produce an
    // {@link UnclassifiedField} or {@link GraphitronType.UnclassifiedType} carrying the reason,
    // which the validator then reports as a standard error.

    /**
     * Returns a reason string when {@code @table}, {@code @record}, and/or {@code @error} appear
     * together on one type, or {@code null} when at most one is present.
     */
    private static String detectTypeDirectiveConflict(GraphQLObjectType objType) {
        var present = List.of(DIR_TABLE, DIR_RECORD, DIR_ERROR).stream()
            .filter(objType::hasAppliedDirective)
            .toList();
        if (present.size() <= 1) return null;
        return present.stream().map(d -> "@" + d).collect(Collectors.joining(", ")) + " are mutually exclusive";
    }

    /**
     * Returns a reason string when mutually exclusive child-field classification directives appear
     * together, or {@code null} when at most one exclusive slot is occupied.
     *
     * <p>Note: {@code @reference} is a path-annotation directive, not a classification directive —
     * it may be combined with {@code @service}, {@code @externalField}, {@code @tableMethod}, and
     * {@code @tableField} (as a FK reference path) or with {@code @nodeId} (producing
     * {@link NodeIdReferenceField}). It is therefore not included in this check.
     */
    private String detectChildFieldConflict(GraphQLFieldDefinition fieldDef) {
        boolean hasNotGenerated  = fieldDef.hasAppliedDirective(DIR_NOT_GENERATED);
        boolean hasMultitable    = fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE);
        boolean hasService       = fieldDef.hasAppliedDirective(DIR_SERVICE);
        boolean hasExternalField = fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD);
        boolean hasTableMethod   = fieldDef.hasAppliedDirective(DIR_TABLE_METHOD);
        boolean hasNodeId        = fieldDef.hasAppliedDirective(DIR_NODE_ID);

        int slots = (hasNotGenerated  ? 1 : 0)
                  + (hasMultitable    ? 1 : 0)
                  + (hasService       ? 1 : 0)
                  + (hasExternalField ? 1 : 0)
                  + (hasTableMethod   ? 1 : 0)
                  + (hasNodeId        ? 1 : 0);

        if (slots <= 1) return null;

        var names = new ArrayList<String>();
        if (hasNotGenerated)  names.add("@" + DIR_NOT_GENERATED);
        if (hasMultitable)    names.add("@" + DIR_MULTITABLE_REFERENCE);
        if (hasService)       names.add("@" + DIR_SERVICE);
        if (hasExternalField) names.add("@" + DIR_EXTERNAL_FIELD);
        if (hasTableMethod)   names.add("@" + DIR_TABLE_METHOD);
        if (hasNodeId)        names.add("@" + DIR_NODE_ID);
        return String.join(", ", names) + " are mutually exclusive";
    }

    /**
     * Returns a reason string when mutually exclusive query-field directives appear together
     * ({@code @service}, {@code @lookupKey} on arguments, {@code @tableMethod}), or {@code null}.
     */
    private String detectQueryFieldConflict(GraphQLFieldDefinition fieldDef) {
        boolean hasService     = fieldDef.hasAppliedDirective(DIR_SERVICE);
        boolean hasLookupKey   = hasLookupKeyAnywhere(fieldDef);
        boolean hasTableMethod = fieldDef.hasAppliedDirective(DIR_TABLE_METHOD);

        int slots = (hasService     ? 1 : 0)
                  + (hasLookupKey   ? 1 : 0)
                  + (hasTableMethod ? 1 : 0);

        if (slots <= 1) return null;

        var names = new ArrayList<String>();
        if (hasService)     names.add("@" + DIR_SERVICE);
        if (hasLookupKey)   names.add("@" + DIR_LOOKUP_KEY);
        if (hasTableMethod) names.add("@" + DIR_TABLE_METHOD);
        return String.join(", ", names) + " are mutually exclusive";
    }

    private GraphitronField classifyChildFieldOnResultType(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<ArgumentSpec> arguments = parseArguments(fieldDef);
            List<String> contextArguments = parseContextArguments(fieldDef, DIR_SERVICE);
            Set<String> argNames = arguments.stream().map(ArgumentSpec::name).collect(Collectors.toSet());
            ServiceMethodRef serviceMethodRef = reflectServiceMethod(serviceRef, argNames, new java.util.HashSet<>(contextArguments));
            ReturnTypeRef returnType = resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            if (returnType instanceof ReturnTypeRef.TableBoundReturnType tb) {
                return new ServiceTableField(parentTypeName, name, location, tb,
                    parseReferencePath(fieldDef), serviceRef, arguments, contextArguments, serviceMethodRef);
            }
            return new ServiceRecordField(parentTypeName, name, location, returnType,
                parseReferencePath(fieldDef), serviceRef, arguments, contextArguments, serviceMethodRef);
        }

        if (isScalarOrEnum(fieldDef)) {
            String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
                ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
                : name;
            return new PropertyField(parentTypeName, name, location, columnName);
        }

        // Object return type on a result-mapped parent.
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
        ReturnTypeRef returnType = resolveReturnType(elementTypeName, buildWrapper(fieldDef));

        if (returnType instanceof ReturnTypeRef.TableBoundReturnType tb) {
            boolean hasLookupKey = hasLookupKeyAnywhere(fieldDef);
            if (hasLookupKey) {
                return new RecordLookupTableField(parentTypeName, name, location, tb,
                    parseReferencePath(fieldDef), parseArguments(fieldDef));
            }
            return new RecordTableField(parentTypeName, name, location, tb,
                parseReferencePath(fieldDef), new FieldConditionRef.NoFieldCondition(), parseArguments(fieldDef));
        }

        // Non-table object return (result-mapped or other) — nested record access.
        String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        return new RecordField(parentTypeName, name, location, returnType, columnName);
    }

    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableType tableType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<ArgumentSpec> arguments = parseArguments(fieldDef);
            List<String> contextArguments = parseContextArguments(fieldDef, DIR_SERVICE);
            Set<String> argNames = arguments.stream().map(ArgumentSpec::name).collect(Collectors.toSet());
            ServiceMethodRef serviceMethodRef = reflectServiceMethod(serviceRef, argNames, new java.util.HashSet<>(contextArguments));
            ReturnTypeRef returnType = resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            if (returnType instanceof ReturnTypeRef.TableBoundReturnType tb) {
                return new ServiceTableField(parentTypeName, name, location, tb,
                    parseReferencePath(fieldDef), serviceRef, arguments, contextArguments, serviceMethodRef);
            }
            return new ServiceRecordField(parentTypeName, name, location, returnType,
                parseReferencePath(fieldDef), serviceRef, arguments, contextArguments, serviceMethodRef);
        }

        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) {
            return new ComputedField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)),
                parseReferencePath(fieldDef));
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            var returnType = resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            var args = parseArguments(fieldDef);
            resolveInputTypeArgs(args, returnType);
            return new TableMethodField(parentTypeName, name, location,
                returnType,
                parseReferencePath(fieldDef),
                parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF),
                args,
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
        return resolveColumnInTable(columnName, resolvedTable.tableName());
    }

    private ColumnRef resolveColumnForReference(String columnName, List<ReferencePathElementRef> path, TableType sourceType) {
        if (!(sourceType.table() instanceof ResolvedTable rt)) {
            return new UnresolvedColumn();
        }
        String currentTableSqlName = rt.tableName();
        for (var step : path) {
            if (step instanceof FkRef fk) {
                currentTableSqlName = fk.keyTableSqlName();
            } else {
                return new UnresolvedColumn();
            }
        }
        return resolveColumnInTable(columnName, currentTableSqlName);
    }

    private ColumnRef resolveColumnInTable(String columnName, String tableSqlName) {
        return catalog.findColumn(tableSqlName, columnName)
            .<ColumnRef>map(e -> new ResolvedColumn(e.javaName(), e.columnClass()))
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
        String columnName = argString(arg, DIR_FIELD, ARG_NAME).orElse(arg.getName());
        return new ArgumentSpec(arg.getName(), typeName, nonNull, list, orderBy, conditionArg, columnName);
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
                var f = fk.get();
                return new FkWithConditionRef(
                    f.getName(),
                    f.getKey().getTable().getName(),
                    f.getTable().getName(),
                    resolved,
                    resolveFkColumns(f.getKey().getTable(), f.getKey().getFields()),
                    resolveFkColumns(f.getTable(), f.getFields()));
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
            .<ReferencePathElementRef>map(fk -> new FkRef(
                fk.getName(),
                fk.getKey().getTable().getName(),
                fk.getTable().getName(),
                resolveFkColumns(fk.getKey().getTable(), fk.getKey().getFields()),
                resolveFkColumns(fk.getTable(), fk.getFields())))
            .orElseGet(() -> new UnresolvedKeyRef(keyName));
    }

    @SuppressWarnings("unchecked")
    private List<JooqCatalog.ColumnEntry> resolveFkColumns(org.jooq.Table<?> table, List<?> fields) {
        return ((List<org.jooq.TableField<?, ?>>) fields).stream()
            .map(f -> catalog.findColumn(table, f.getName()).orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    /**
     * Condition resolution via reflection is implemented in a later deliverable (P3).
     * Returns {@code null} to signal that the condition is unresolved.
     */
    private MethodRef resolveConditionRef(Map<String, Object> conditionMap) {
        return null;
    }

    /**
     * Attempts to load the service class and method via reflection and classify each parameter.
     *
     * <p>Returns {@link ServiceMethodRef.Resolved} when the class and at least one matching method
     * are found; {@link ServiceMethodRef.Unresolved} otherwise. Parameters whose name matches a
     * GraphQL argument become {@link ServiceMethodRef.ServiceParam.ArgParam}, parameters whose name
     * matches a context key become {@link ServiceMethodRef.ServiceParam.ContextParam}, and all
     * others (including parameters whose name is absent because the class was compiled without
     * {@code -parameters}) become {@link ServiceMethodRef.ServiceParam.SourcesParam} with the
     * element type classified by {@link #classifySourcesType}.
     */
    private ServiceMethodRef reflectServiceMethod(ExternalRef serviceRef, Set<String> argNames, Set<String> ctxKeys) {
        if (serviceRef == null || serviceRef.className() == null || serviceRef.methodName() == null) {
            return new ServiceMethodRef.Unresolved("service reference is incomplete");
        }
        try {
            Class<?> cls = Class.forName(serviceRef.className());
            var methods = java.util.Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals(serviceRef.methodName()))
                .toList();
            if (methods.isEmpty()) {
                return new ServiceMethodRef.Unresolved(
                    "method '" + serviceRef.methodName() + "' not found in class '" + serviceRef.className() + "'");
            }
            var method = methods.get(0);
            var params = java.util.Arrays.stream(method.getParameters())
                .map(p -> {
                    String pName = p.isNamePresent() ? p.getName() : null;
                    String displayName = pName != null ? pName : p.getType().getSimpleName();
                    if (pName != null && argNames.contains(pName)) {
                        return (ServiceMethodRef.ServiceParam) new ServiceMethodRef.ServiceParam.ArgParam(
                            displayName, p.getParameterizedType().getTypeName());
                    } else if (pName != null && ctxKeys.contains(pName)) {
                        return (ServiceMethodRef.ServiceParam) new ServiceMethodRef.ServiceParam.ContextParam(
                            displayName, p.getParameterizedType().getTypeName());
                    } else {
                        return (ServiceMethodRef.ServiceParam) new ServiceMethodRef.ServiceParam.SourcesParam(
                            displayName, classifySourcesType(p.getParameterizedType()));
                    }
                })
                .toList();
            return new ServiceMethodRef.Resolved(params, method.getReturnType().getName());
        } catch (ClassNotFoundException e) {
            return new ServiceMethodRef.Unresolved("class '" + serviceRef.className() + "' could not be loaded");
        }
    }

    /**
     * Classifies the element type of a {@code List<?>} SOURCES parameter into a {@link SourcesRef}
     * variant.
     *
     * <ul>
     *   <li>{@code List<RowN<T1,...>>} → {@link SourcesRef.RowKeyed}</li>
     *   <li>{@code List<RecordN<T1,...>>} → {@link SourcesRef.RecordKeyed}</li>
     *   <li>{@code List<SomeTableRecord>} (a {@link org.jooq.TableRecord} subclass) →
     *       {@link SourcesRef.TableRecordKeyed}</li>
     *   <li>Anything else → {@link SourcesRef.Unrecognized}</li>
     * </ul>
     */
    private static SourcesRef classifySourcesType(java.lang.reflect.Type paramType) {
        if (!(paramType instanceof java.lang.reflect.ParameterizedType pt)
                || pt.getRawType() != java.util.List.class) {
            return new SourcesRef.Unrecognized(paramType.getTypeName());
        }
        java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
        if (typeArgs.length != 1) {
            return new SourcesRef.Unrecognized(paramType.getTypeName());
        }
        java.lang.reflect.Type elementType = typeArgs[0];

        if (elementType instanceof java.lang.reflect.ParameterizedType ept
                && ept.getRawType() instanceof Class<?> rawClass) {
            String rawName = rawClass.getName();
            if (rawName.startsWith("org.jooq.Row")) {
                String suffix = rawName.substring("org.jooq.Row".length());
                if (suffix.matches("\\d+")) {
                    List<String> pkTypes = java.util.Arrays.stream(ept.getActualTypeArguments())
                        .map(java.lang.reflect.Type::getTypeName)
                        .toList();
                    return new SourcesRef.RowKeyed(pkTypes);
                }
            }
            if (rawName.startsWith("org.jooq.Record")) {
                String suffix = rawName.substring("org.jooq.Record".length());
                if (suffix.matches("\\d+")) {
                    List<String> pkTypes = java.util.Arrays.stream(ept.getActualTypeArguments())
                        .map(java.lang.reflect.Type::getTypeName)
                        .toList();
                    return new SourcesRef.RecordKeyed(pkTypes);
                }
            }
        } else if (elementType instanceof Class<?> elementClass
                && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
            return new SourcesRef.TableRecordKeyed(elementClass.getName());
        }

        return new SourcesRef.Unrecognized(paramType.getTypeName());
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
