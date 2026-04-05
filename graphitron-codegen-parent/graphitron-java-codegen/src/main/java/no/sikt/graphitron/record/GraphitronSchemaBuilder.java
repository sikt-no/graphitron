package no.sikt.graphitron.record;

import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.NullValue;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLAppliedDirectiveArgument;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLDirectiveContainer;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
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
import no.sikt.graphitron.record.field.ChildField.MultitableReferenceField;
import no.sikt.graphitron.record.field.ChildField.NestingField;
import no.sikt.graphitron.record.field.ChildField.NodeIdField;
import no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.record.field.ChildField.TableField;
import no.sikt.graphitron.record.field.ChildField.TableMethodField;
import no.sikt.graphitron.record.field.DefaultOrderSpec;
import no.sikt.graphitron.record.field.FieldCardinality;
import no.sikt.graphitron.record.field.FieldConditionRef;
import no.sikt.graphitron.record.field.OrderSpec;
import no.sikt.graphitron.record.field.SortFieldSpec;
import no.sikt.graphitron.record.field.ColumnRef;
import no.sikt.graphitron.record.field.ColumnRef.ResolvedColumn;
import no.sikt.graphitron.record.field.ColumnRef.UnresolvedColumn;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.record.field.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.NodeTypeRef;
import no.sikt.graphitron.record.field.NodeTypeRef.ResolvedNodeType;
import no.sikt.graphitron.record.field.NodeTypeRef.UnresolvedNodeType;
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
import no.sikt.graphitron.record.type.GraphitronType.InterfaceType;
import no.sikt.graphitron.record.type.GraphitronType.ResultType;
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

    // Argument names for the directives above.
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
    private static final String ARG_SORT_FIELD_NAME = "name";    // FieldSort.name (database field name)
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
            return new BoundParticipant(typeName, tableType.table());
        }
        return new UnboundParticipant(typeName);
    }

    private GraphitronType classifyType(GraphQLNamedType namedType) {
        if (namedType instanceof GraphQLScalarType
                || namedType instanceof GraphQLEnumType
                || namedType instanceof GraphQLInputObjectType) {
            return null;
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
        String returnTypeName = baseTypeName(fieldDef);
        GraphitronType returnType = types.get(returnTypeName);

        if (returnType instanceof TableType) {
            return new TableField(parentTypeName, name, location,
                resolveReturnType(returnTypeName),
                parseReferencePath(fieldDef),
                new FieldConditionRef.NoFieldCondition(),
                fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY),
                buildCardinality(fieldDef));
        }

        // NestingField: a plain object type in the schema with no Graphitron classification.
        // Its fields are resolved from the same table context as the parent.
        if (schema.getType(returnTypeName) instanceof GraphQLObjectType && returnType == null) {
            return new NestingField(parentTypeName, name, location,
                new ReturnTypeRef.UnresolvedReturnType(returnTypeName));
        }

        return new UnclassifiedField(parentTypeName, name, location);
    }

    // ===== Cardinality helpers =====

    /**
     * Builds a {@link FieldCardinality} from the return type shape of the field and any
     * {@code @defaultOrder} directive. A type name ending in {@code "Connection"} produces
     * {@link FieldCardinality.Connection}; a list-wrapped type produces {@link FieldCardinality.List};
     * anything else produces {@link FieldCardinality.Single}.
     *
     * <p>{@code @orderBy} enum value specs are not populated here — that is deferred to P4
     * (field argument modeling).
     */
    private FieldCardinality buildCardinality(GraphQLFieldDefinition fieldDef) {
        String returnTypeName = baseTypeName(fieldDef);
        DefaultOrderSpec defaultOrder = parseDefaultOrderSpec(fieldDef);

        if (returnTypeName.endsWith("Connection")) {
            return new FieldCardinality.Connection(defaultOrder, List.of());
        }
        if (GraphQLTypeUtil.isList(GraphQLTypeUtil.unwrapNonNull(fieldDef.getType()))) {
            return new FieldCardinality.List(defaultOrder, List.of());
        }
        return new FieldCardinality.Single();
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
        Object nameRaw = item.get(ARG_SORT_FIELD_NAME);
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

        if (parentType instanceof TableType tableType) {
            return classifyChildFieldOnTableType(fieldDef, parentTypeName, tableType);
        }

        return new UnclassifiedField(parentTypeName, name, location);
    }

    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableType tableType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            return new TableMethodField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef)),
                parseReferencePath(fieldDef),
                buildCardinality(fieldDef));
        }

        if (!isScalarOrEnum(fieldDef)) {
            return classifyObjectReturnChildField(fieldDef, parentTypeName);
        }

        if (fieldDef.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<String> typeName = argString(fieldDef, DIR_NODE_ID, ARG_TYPE_NAME);
            if (typeName.isPresent()) {
                ReturnTypeRef targetType = resolveReturnType(typeName.get());
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

    private ReturnTypeRef resolveReturnType(String targetTypeName) {
        GraphitronType target = types.get(targetTypeName);
        if (target instanceof TableType tt) return new ReturnTypeRef.TableBoundReturnType(targetTypeName, tt.table());
        if (target != null) return new ReturnTypeRef.OtherReturnType(targetTypeName);
        return new ReturnTypeRef.UnresolvedReturnType(targetTypeName);
    }

    private NodeTypeRef resolveNodeType(String targetTypeName) {
        GraphitronType target = types.get(targetTypeName);
        if (target instanceof TableType tt && tt.node() instanceof NodeDirective nd)
            return new ResolvedNodeType(nd);
        return new UnresolvedNodeType();
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
        return new UnresolvedKeyRef("");
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
                .collect(Collectors.toList());
        }
        if (value instanceof List<?> list) {
            return list.stream()
                .map(v -> v == null ? null : v.toString().strip())
                .collect(Collectors.toList());
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
