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
import no.sikt.graphitron.rewrite.model.ErrorHandlerSpec;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.ChildField.InterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.ChildField.NodeIdField;
import no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.PropertyField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordTableField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.TableMethodField;
import no.sikt.graphitron.rewrite.model.ChildField.UnionField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.DefaultOrderSpec;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.FieldConditionRef;
import no.sikt.graphitron.rewrite.model.OrderSpec;
import no.sikt.graphitron.rewrite.model.SortFieldSpec;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.rewrite.model.ArgumentRef;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ExternalRef;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ServiceMethodRef;
import no.sikt.graphitron.rewrite.model.SourcesRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.ReferencePathElementRef;
import no.sikt.graphitron.rewrite.model.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.rewrite.model.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.rewrite.model.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.model.InputFieldRef;
import no.sikt.graphitron.rewrite.model.InputFieldSpec;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.NodeRef;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.TableRef;
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
    private void resolveInputTypeImplicitly(String typeName, TableRef rt) {
        if (rt == null) return;
        var current = types.get(typeName);
        if (current instanceof InputType it) {
            types.put(typeName, promoteToTableInputType(it, rt));
        } else if (current instanceof TableInputType tit
                && !tit.table().tableName().equalsIgnoreCase(rt.tableName())) {
            types.put(typeName, new GraphitronType.UnclassifiedType(typeName, tit.location(),
                "used as an argument on fields with conflicting return tables: '"
                + tit.table().tableName() + "' and '" + rt.tableName() + "'"));
        }
    }

    /**
     * Promotes an {@link InputType} to a {@link TableInputType} by resolving each field's column
     * against the given {@link TableRef}. Returns {@link UnclassifiedType} when any field's column
     * cannot be resolved.
     */
    private GraphitronType promoteToTableInputType(InputType inputType, TableRef resolvedTable) {
        var errors = new ArrayList<String>();
        var resolvedFields = new ArrayList<InputFieldRef>();
        for (var spec : inputType.fields()) {
            var found = catalog.findColumn(resolvedTable.tableName(), spec.columnName())
                .map(e -> new InputFieldRef(spec.name(), spec.typeName(), spec.nonNull(), spec.list(),
                    resolvedTable, e.javaName(), e.columnClass()));
            if (found.isEmpty()) {
                errors.add("field '" + spec.name() + "' column '" + spec.columnName() + "' could not be resolved in the jOOQ table");
            } else {
                resolvedFields.add(found.get());
            }
        }
        if (!errors.isEmpty()) {
            return new UnclassifiedType(inputType.name(), inputType.location(), String.join("; ", errors));
        }
        return new TableInputType(inputType.name(), inputType.location(), resolvedTable, List.copyOf(resolvedFields));
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

        // Expose the first-pass result so that buildParticipantList can look up TableType entries
        // during the second pass below (buildParticipantList uses this.types).
        this.types = result;

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

    private GraphitronType enrichTableInterfaceType(TableInterfaceType type, Map<String, GraphitronType> types) {
        var participants = buildParticipantList(implementorNames(type.name(), types));
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), participants.error());
        }
        return new TableInterfaceType(type.name(), type.location(), type.discriminatorColumn(), type.table(), participants.list());
    }

    private GraphitronType enrichInterfaceType(InterfaceType type, Map<String, GraphitronType> types) {
        var participants = buildParticipantList(implementorNames(type.name(), types));
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), participants.error());
        }
        return new InterfaceType(type.name(), type.location(), participants.list());
    }

    private GraphitronType enrichUnionType(UnionType type, Map<String, GraphitronType> types) {
        var unionType = (GraphQLUnionType) schema.getType(type.name());
        var names = unionType.getTypes().stream().map(t -> t.getName()).toList();
        var participants = buildParticipantList(names);
        if (participants.error() != null) {
            return new UnclassifiedType(type.name(), type.location(), participants.error());
        }
        return new UnionType(type.name(), type.location(), participants.list());
    }

    /** Returns the names of all implementors of the given interface. */
    private List<String> implementorNames(String interfaceName, Map<String, GraphitronType> types) {
        var iface = (GraphQLInterfaceType) schema.getType(interfaceName);
        return schema.getImplementations(iface).stream().map(obj -> obj.getName()).toList();
    }

    private record ParticipantListResult(List<ParticipantRef> list, String error) {}

    private ParticipantListResult buildParticipantList(List<String> typeNames) {
        var result = new ArrayList<ParticipantRef>();
        var errors = new ArrayList<String>();
        for (var typeName : typeNames) {
            var gt = types.get(typeName);
            if (gt instanceof TableType tableType) {
                String discriminatorValue = argString(schema.getObjectType(typeName), DIR_DISCRIMINATOR, ARG_VALUE).orElse(null);
                result.add(new ParticipantRef(typeName, tableType.table(), discriminatorValue));
            } else {
                errors.add("implementing type '" + typeName + "' is not table-bound (missing @table directive)");
            }
        }
        if (!errors.isEmpty()) {
            return new ParticipantListResult(null, String.join("; ", errors));
        }
        return new ParticipantListResult(List.copyOf(result), null);
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
        if (namedType instanceof GraphQLUnionType graphQLUnionType) {
            return new UnionType(name, location, List.of());
        }
        return null;
    }

    private GraphitronType buildTableType(GraphQLObjectType objType) {
        String name = objType.getName();
        SourceLocation location = locationOf(objType);
        String tableName = argString(objType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog");
        }
        TableRef tableRef = tableOpt.get();
        if (!objType.hasAppliedDirective(DIR_NODE)) {
            return new TableType(name, location, tableRef, null);
        }
        // @node: resolve key columns; any failure → UnclassifiedType
        String typeId = argString(objType, DIR_NODE, ARG_TYPE_ID).orElse(null);
        List<String> keyColumnNames = argStringList(objType, DIR_NODE, ARG_KEY_COLUMNS);
        var keyColumnErrors = new ArrayList<String>();
        var keyColumns = new ArrayList<ColumnRef>();
        for (String colName : keyColumnNames) {
            Optional<ColumnRef> kc = resolveKeyColumn(colName, tableRef.tableName());
            if (kc.isEmpty()) {
                keyColumnErrors.add("key column '" + colName + "' in @node could not be resolved in the jOOQ table");
            } else {
                keyColumns.add(kc.get());
            }
        }
        if (!keyColumnErrors.isEmpty()) {
            return new UnclassifiedType(name, location, String.join("; ", keyColumnErrors));
        }
        return new TableType(name, location, tableRef, new NodeRef(typeId, List.copyOf(keyColumns)));
    }

    private GraphitronType buildTableInterfaceType(GraphQLInterfaceType iface) {
        String name = iface.getName();
        SourceLocation location = locationOf(iface);
        String tableName = argString(iface, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog");
        }
        String discriminatorColumn = argString(iface, DIR_DISCRIMINATE, ARG_ON).orElse(null);
        return new TableInterfaceType(name, location, discriminatorColumn, tableOpt.get(), List.of());
    }

    private Optional<TableRef> resolveTable(String sqlName) {
        return catalog.findTable(sqlName)
            .map(e -> {
                var pk = e.table().getPrimaryKey();
                List<String> pkCols = pk != null
                    ? pk.getFields().stream().map(f -> f.getName()).toList()
                    : List.of();
                List<String> pkJavaTypes = pk != null
                    ? pk.getFields().stream().map(f -> f.getType().getName()).toList()
                    : List.of();
                return new TableRef(sqlName, e.javaFieldName(), e.table().getClass().getSimpleName(), pk != null, pkCols, pkJavaTypes);
            });
    }

    private Optional<ColumnRef> resolveKeyColumn(String colName, String tableSqlName) {
        return catalog.findColumn(tableSqlName, colName)
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()));
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
            Optional<TableRef> tableOpt = resolveTable(tableName);
            if (tableOpt.isEmpty()) {
                return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog");
            }
            TableRef tableRef = tableOpt.get();
            var errors = new ArrayList<String>();
            var resolvedFields = new ArrayList<InputFieldRef>();
            for (var f : inputType.getFieldDefinitions().stream().filter(f -> !f.hasAppliedDirective(DIR_NOT_GENERATED)).toList()) {
                Optional<InputFieldRef> field = buildInputFieldRef(f, tableRef);
                if (field.isEmpty()) {
                    String colName = f.hasAppliedDirective(DIR_FIELD)
                        ? argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName()) : f.getName();
                    errors.add("field '" + f.getName() + "' column '" + colName + "' could not be resolved in the jOOQ table");
                } else {
                    resolvedFields.add(field.get());
                }
            }
            if (!errors.isEmpty()) {
                return new UnclassifiedType(name, location, String.join("; ", errors));
            }
            return new TableInputType(name, location, tableRef, List.copyOf(resolvedFields));
        }
        List<InputFieldSpec> fields = inputType.getFieldDefinitions().stream()
            .filter(f -> !f.hasAppliedDirective(DIR_NOT_GENERATED))
            .map(this::buildInputFieldSpec)
            .toList();
        return new InputType(name, location, fields);
    }

    private Optional<InputFieldRef> buildInputFieldRef(GraphQLInputObjectField field, TableRef resolvedTable) {
        String name = field.getName();
        GraphQLType type = field.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();
        boolean hasFieldDir = field.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDir
            ? argString(field, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        return catalog.findColumn(resolvedTable.tableName(), columnName)
            .map(e -> new InputFieldRef(name, typeName, nonNull, list, resolvedTable, e.javaName(), e.columnClass()));
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
    private GraphitronField classifyObjectReturnChildField(GraphQLFieldDefinition fieldDef, String parentTypeName, TableType parentTableType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);
        String rawTypeName = baseTypeName(fieldDef);

        // For connection types the element type is edges.node, not the connection wrapper type.
        String elementTypeName = isConnectionType(rawTypeName)
            ? connectionElementTypeName(rawTypeName)
            : rawTypeName;
        GraphitronType elementType = types.get(elementTypeName);

        if (elementType instanceof TableType tableType) {
            var returnType = (ReturnTypeRef.TableBoundReturnType) resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            var referencePath = parsePath(fieldDef);
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, referencePath.errorMessage());
            }
            var rt = returnType.table();
            var args = fieldDef.getArguments().stream()
                .map(arg -> classifyArgument(arg, rt, false))
                .toList();
            boolean hasSplitQuery = fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY);
            boolean hasLookupKey  = hasLookupKeyAnywhere(fieldDef);
            if (hasSplitQuery && hasLookupKey) {
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), args);
            }
            if (!hasSplitQuery && hasLookupKey) {
                return new no.sikt.graphitron.rewrite.model.ChildField.LookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), args);
            }
            if (hasSplitQuery) {
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitTableField(
                    parentTypeName, name, location, returnType,
                    referencePath.elements(), new FieldConditionRef.NoFieldCondition(), args);
            }
            return new TableField(parentTypeName, name, location,
                returnType, referencePath.elements(), new FieldConditionRef.NoFieldCondition(), args);
        }

        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            return new TableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), buildWrapper(fieldDef)));
        }

        if (elementType instanceof InterfaceType interfaceType) {
            return new InterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        if (elementType instanceof UnionType unionType) {
            return new UnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        // NestingField: a plain object type in the schema with no Graphitron classification.
        // Its fields are resolved from the same table context as the parent.
        if (schema.getType(elementTypeName) instanceof GraphQLObjectType graphQLObjectType && elementType == null) {
            return new NestingField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, parentTableType.table(), buildWrapper(fieldDef)));
        }

        // ConstructorField is intentionally not classified here — its directive and generation
        // semantics are not yet defined (planned future deliverable). Fields that would logically
        // map to ConstructorField fall through to UnclassifiedField, which the validator rejects
        // with a clear error, making the gap visible and enforced rather than silently ignored.
        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
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
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, conflict);
            }
        }

        if (fieldDef.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return new NotGeneratedField(parentTypeName, name, location);
        }
        if (fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE)) {
            return new MultitableReferenceField(parentTypeName, name, location);
        }

        if (parentType instanceof RootType rootType) {
            return classifyRootField(fieldDef, parentTypeName);
        }
        if (parentType instanceof TableType tableType) {
            return classifyChildFieldOnTableType(fieldDef, parentTypeName, tableType);
        }
        if (parentType instanceof ResultType resultType) {
            return classifyChildFieldOnResultType(fieldDef, parentTypeName);
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
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
        return new UnclassifiedField(parentTypeName, fieldDef.getName(), locationOf(fieldDef), fieldDef,
            "fields on '" + parentTypeName + "' (Subscription is not supported)");
    }

    private GraphitronField classifyQueryField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        String conflict = detectQueryFieldConflict(fieldDef);
        if (conflict != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, conflict);
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            ReturnTypeRef returnType = resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<String> contextArgs = parseContextArguments(fieldDef, DIR_SERVICE);
            Set<String> argNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            ServiceReflectionResult serviceReflection = reflectServiceMethod(serviceRef, argNames, new java.util.HashSet<>(contextArgs));
            if (serviceReflection.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "service method could not be resolved — " + serviceReflection.failureReason());
            }
            ServiceMethodRef serviceMethodRef = serviceReflection.ref();
            var args = fieldDef.getArguments().stream()
                .map(arg -> classifyArgument(arg, null, true))
                .toList();
            return switch (returnType) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new QueryField.QueryServiceTableField(parentTypeName, name, location, tb, serviceRef, args, contextArgs, serviceMethodRef);
                case ReturnTypeRef.OtherReturnType other ->
                    new QueryField.QueryServiceRecordField(parentTypeName, name, location, other, serviceRef, args, contextArgs);
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (name.equals("_entities")) {
            return new QueryField.QueryEntityField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (name.equals("node")) {
            return new QueryField.QueryNodeField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)));
        }

        if (hasLookupKeyAnywhere(fieldDef)) {
            var returnType = resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef));
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "@lookupKey requires a @table-annotated return type");
            }
            var arguments = fieldDef.getArguments().stream()
                .map(arg -> classifyArgument(arg, tb.table(), false))
                .toList();
            return new QueryField.QueryLookupTableField(parentTypeName, name, location, tb, arguments);
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            var returnType = resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "@tableMethod requires a @table-annotated return type");
            }
            var args = fieldDef.getArguments().stream()
                .map(arg -> classifyArgument(arg, null, true))
                .toList();
            return new QueryField.QueryTableMethodTableField(parentTypeName, name, location,
                tb,
                parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF),
                args,
                parseContextArguments(fieldDef, DIR_TABLE_METHOD));
        }

        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
        GraphitronType elementType = types.get(elementTypeName);

        if (elementType instanceof TableType tableType) {
            var returnType = (ReturnTypeRef.TableBoundReturnType) resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            var args = fieldDef.getArguments().stream()
                .map(arg -> classifyArgument(arg, returnType.table(), false))
                .toList();
            return new QueryField.QueryTableField(parentTypeName, name, location,
                returnType,
                args);
        }
        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            return new QueryField.QueryTableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), buildWrapper(fieldDef)));
        }
        if (elementType instanceof InterfaceType interfaceType) {
            return new QueryField.QueryInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }
        if (elementType instanceof UnionType unionType) {
            return new QueryField.QueryUnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)));
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
            "return type '" + elementTypeName + "' is not a @table, interface, or union Graphitron type; " +
            "@service, @lookupKey, and @tableMethod are all absent");
    }

    private GraphitronField classifyMutationField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE) && fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "@" + DIR_SERVICE + ", @" + DIR_MUTATION + " are mutually exclusive");
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            ReturnTypeRef returnType = resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef));
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<String> contextArgs = parseContextArguments(fieldDef, DIR_SERVICE);
            Set<String> argNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            ServiceReflectionResult serviceReflection = reflectServiceMethod(serviceRef, argNames, new java.util.HashSet<>(contextArgs));
            if (serviceReflection.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "service method could not be resolved — " + serviceReflection.failureReason());
            }
            ServiceMethodRef serviceMethodRef = serviceReflection.ref();
            var args = fieldDef.getArguments().stream()
                .map(arg -> classifyArgument(arg, null, true))
                .toList();
            return switch (returnType) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new MutationField.MutationServiceTableField(parentTypeName, name, location, tb, serviceRef, args, contextArgs, serviceMethodRef);
                case ReturnTypeRef.OtherReturnType other ->
                    new MutationField.MutationServiceRecordField(parentTypeName, name, location, other, serviceRef, args, contextArgs);
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            String typeName = getMutationTypeName(fieldDef);
            if (typeName != null) {
                String rawReturn = baseTypeName(fieldDef);
                ReturnTypeRef returnType = resolveReturnType(rawReturn, buildWrapper(fieldDef));
                var arguments = fieldDef.getArguments().stream()
                    .map(arg -> classifyArgument(arg, null, true))
                    .toList();
                return switch (typeName) {
                    case "INSERT" -> new MutationField.MutationInsertTableField(parentTypeName, name, location, returnType, arguments);
                    case "UPDATE" -> new MutationField.MutationUpdateTableField(parentTypeName, name, location, returnType, arguments);
                    case "DELETE" -> new MutationField.MutationDeleteTableField(parentTypeName, name, location, returnType, arguments);
                    case "UPSERT" -> new MutationField.MutationUpsertTableField(parentTypeName, name, location, returnType, arguments);
                    default       -> new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "unknown @mutation(typeName:) value '" + typeName + "'");
                };
            }
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
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
     * Classifies a single GraphQL argument into an {@link ArgumentRef} variant.
     *
     * <p>{@code rt} is the resolved table for column binding (may be {@code null} when the table
     * is unresolved or not applicable). {@code useParamForScalars} suppresses column binding:
     * when {@code true}, scalar arguments become {@link ArgumentRef.ScalarArg.ParamArg} (used for
     * service and method fields where scalars are forwarded as Java parameters rather than bound
     * to database columns).
     */
    private ArgumentRef classifyArgument(GraphQLArgument arg, TableRef rt, boolean useParamForScalars) {
        String name = arg.getName();
        GraphQLType type = arg.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();

        if (arg.hasAppliedDirective(DIR_CONDITION)) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@condition is only supported on field definitions, not on arguments");
        }
        if (arg.hasAppliedDirective(DIR_ORDER_BY)) {
            return resolveOrderByArg(arg, name, typeName, nonNull, list);
        }
        if (types.containsKey(typeName)) {
            resolveInputTypeImplicitly(typeName, rt);
            return types.get(typeName) instanceof TableInputType
                ? new ArgumentRef.InputTypeArg.TableInputTypeArg(name, typeName, nonNull, list)
                : new ArgumentRef.InputTypeArg.PlainInputTypeArg(name, typeName, nonNull, list);
        }
        // Scalar arg
        if (useParamForScalars) {
            return new ArgumentRef.ScalarArg.ParamArg(name, typeName, nonNull, list);
        }
        String columnName = argString(arg, DIR_FIELD, ARG_NAME).orElse(name);
        if (rt == null) {
            return new ArgumentRef.ScalarArg.UnboundScalarArg(name, typeName, nonNull, list, columnName);
        }
        return catalog.findColumn(rt.tableName(), columnName)
            .<ArgumentRef>map(e -> new ArgumentRef.ScalarArg.ColumnArg(name, typeName, nonNull, list, e.javaName(), e.columnClass()))
            .orElseGet(() -> new ArgumentRef.ScalarArg.UnboundScalarArg(name, typeName, nonNull, list, columnName));
    }

    /**
     * Resolves an {@code @orderBy} argument to an {@link ArgumentRef.InputTypeArg.OrderByArg}.
     *
     * <p>Looks up the argument's input type in the schema and expects it to contain exactly one
     * enum field whose values carry {@code @order} directives (the sort field) and exactly one
     * other enum field (the direction field). Returns an {@link ArgumentRef.UnclassifiedArg} with
     * a descriptive reason if the structure is invalid.
     */
    private ArgumentRef resolveOrderByArg(GraphQLArgument arg, String name, String typeName, boolean nonNull, boolean list) {
        var rawType = schema.getType(typeName);
        if (!(rawType instanceof GraphQLInputObjectType inputType)) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@orderBy argument type '" + typeName + "' is not an input type");
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
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        "@orderBy input type '" + typeName + "' must have exactly one sort enum field, but found multiple");
                }
                sortFieldName = field.getName();
            } else {
                if (directionFieldName != null) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        "@orderBy input type '" + typeName + "' must have exactly one direction field, but found multiple");
                }
                directionFieldName = field.getName();
            }
        }
        if (sortFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@orderBy input type '" + typeName + "' has no sort enum field (no enum values with @order)");
        }
        if (directionFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@orderBy input type '" + typeName + "' has no direction field");
        }
        return new ArgumentRef.InputTypeArg.OrderByArg(name, typeName, nonNull, list, sortFieldName, directionFieldName);
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
            List<String> contextArguments = parseContextArguments(fieldDef, DIR_SERVICE);
            Set<String> argNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            ServiceReflectionResult serviceReflection = reflectServiceMethod(serviceRef, argNames, new java.util.HashSet<>(contextArguments));
            if (serviceReflection.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "service method could not be resolved — " + serviceReflection.failureReason());
            }
            var arguments = fieldDef.getArguments().stream()
                .map(arg -> classifyArgument(arg, null, true))
                .toList();
            var servicePath = parsePath(fieldDef);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, servicePath.errorMessage());
            }
            ServiceMethodRef serviceMethodRef = serviceReflection.ref();
            return switch (resolveReturnType(elementTypeName, buildWrapper(fieldDef))) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new ServiceTableField(parentTypeName, name, location, tb,
                        servicePath.elements(), serviceRef, arguments, contextArguments, serviceMethodRef);
                case ReturnTypeRef.OtherReturnType other ->
                    new ServiceRecordField(parentTypeName, name, location, other,
                        servicePath.elements(), serviceRef, arguments, contextArguments, serviceMethodRef);
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
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
        String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        var objectPath = parsePath(fieldDef);
        if (objectPath.hasError()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, objectPath.errorMessage());
        }
        return switch (resolveReturnType(elementTypeName, buildWrapper(fieldDef))) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                var args = fieldDef.getArguments().stream()
                    .map(arg -> classifyArgument(arg, tb.table(), false))
                    .toList();
                boolean hasLookupKey = hasLookupKeyAnywhere(fieldDef);
                if (hasLookupKey) {
                    yield new RecordLookupTableField(parentTypeName, name, location, tb, objectPath.elements(), args);
                }
                yield new RecordTableField(parentTypeName, name, location, tb,
                    objectPath.elements(), new FieldConditionRef.NoFieldCondition(), args);
            }
            case ReturnTypeRef.OtherReturnType other ->
                new RecordField(parentTypeName, name, location, other, columnName);
            case ReturnTypeRef.PolymorphicReturnType p ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, "@record type returning a polymorphic type is not yet supported");
        };
    }

    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableType tableType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<String> contextArguments = parseContextArguments(fieldDef, DIR_SERVICE);
            Set<String> argNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            ServiceReflectionResult serviceReflection = reflectServiceMethod(serviceRef, argNames, new java.util.HashSet<>(contextArguments));
            if (serviceReflection.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "service method could not be resolved — " + serviceReflection.failureReason());
            }
            ServiceMethodRef serviceMethodRef = serviceReflection.ref();
            var arguments = fieldDef.getArguments().stream()
                .map(arg -> classifyArgument(arg, null, true))
                .toList();
            var servicePath = parsePath(fieldDef);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, servicePath.errorMessage());
            }
            return switch (resolveReturnType(elementTypeName, buildWrapper(fieldDef))) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new ServiceTableField(parentTypeName, name, location, tb,
                        servicePath.elements(), serviceRef, arguments, contextArguments, serviceMethodRef);
                case ReturnTypeRef.OtherReturnType other ->
                    new ServiceRecordField(parentTypeName, name, location, other,
                        servicePath.elements(), serviceRef, arguments, contextArguments, serviceMethodRef);
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) {
            var externalPath = parsePath(fieldDef);
            if (externalPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, externalPath.errorMessage());
            }
            return new ComputedField(parentTypeName, name, location,
                resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef)),
                externalPath.elements());
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            var returnType = resolveReturnType(elementTypeName, buildWrapper(fieldDef));
            var args = fieldDef.getArguments().stream()
                .map(arg -> classifyArgument(arg, null, true))
                .toList();
            var tableMethodPath = parsePath(fieldDef);
            if (tableMethodPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, tableMethodPath.errorMessage());
            }
            return new TableMethodField(parentTypeName, name, location,
                returnType,
                tableMethodPath.elements(),
                parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF),
                args,
                parseContextArguments(fieldDef, DIR_TABLE_METHOD));
        }

        if (!isScalarOrEnum(fieldDef)) {
            return classifyObjectReturnChildField(fieldDef, parentTypeName, tableType);
        }

        if (fieldDef.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<String> typeName = argString(fieldDef, DIR_NODE_ID, ARG_TYPE_NAME);
            if (typeName.isPresent()) {
                ReturnTypeRef targetType = resolveReturnType(typeName.get(), new FieldWrapper.Single(true));
                var targetGType = types.get(typeName.get());
                if (targetGType == null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not exist in the schema");
                }
                if (!(targetGType instanceof TableType targetTableType) || targetTableType.node() == null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not have @node");
                }
                NodeRef nodeRef = targetTableType.node();
                TableRef parentTable = tableType.table();
                var nodeRefPath = parsePath(fieldDef);
                if (nodeRefPath.hasError()) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, nodeRefPath.errorMessage());
                }
                return new NodeIdReferenceField(parentTypeName, name, location, typeName.get(), targetType, parentTable, nodeRef, nodeRefPath.elements());
            } else {
                NodeRef nodeRef = tableType.node();
                if (nodeRef == null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@nodeId requires the containing type to have @node");
                }
                return new NodeIdField(parentTypeName, name, location, nodeRef);
            }
        }

        boolean hasFieldDirective = fieldDef.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDirective
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        boolean javaNamePresent = hasFieldDirective
            && argString(fieldDef, DIR_FIELD, ARG_JAVA_NAME).isPresent();

        if (fieldDef.hasAppliedDirective(DIR_REFERENCE)) {
            var refPath = parsePath(fieldDef);
            if (refPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, refPath.errorMessage());
            }
            Optional<ColumnRef> column = resolveColumnForReference(columnName, refPath.elements(), tableType);
            if (column.isEmpty()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "column '" + columnName + "' could not be resolved in the jOOQ table");
            }
            return new ColumnReferenceField(parentTypeName, name, location, columnName, column.get(), refPath.elements(), javaNamePresent);
        }

        Optional<ColumnRef> column = resolveColumn(columnName, tableType);
        if (column.isEmpty()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "column '" + columnName + "' could not be resolved in the jOOQ table");
        }
        return new ColumnField(parentTypeName, name, location, columnName, column.get(), javaNamePresent);
    }

    private Optional<ColumnRef> resolveColumn(String columnName, TableType tableType) {
        return resolveColumnInTable(columnName, tableType.table().tableName());
    }

    private Optional<ColumnRef> resolveColumnForReference(String columnName, List<ReferencePathElementRef> path, TableType sourceType) {
        String currentTableSqlName = sourceType.table().tableName();
        for (var step : path) {
            if (step instanceof FkRef fk) {
                currentTableSqlName = fk.keyTableSqlName();
            } else {
                return Optional.empty();
            }
        }
        return resolveColumnInTable(columnName, currentTableSqlName);
    }

    private Optional<ColumnRef> resolveColumnInTable(String columnName, String tableSqlName) {
        return catalog.findColumn(tableSqlName, columnName)
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()));
    }

    private ReturnTypeRef resolveReturnType(String targetTypeName, FieldWrapper wrapper) {
        GraphitronType target = types.get(targetTypeName);
        if (target instanceof TableType tt)
            return new ReturnTypeRef.TableBoundReturnType(targetTypeName, tt.table(), wrapper);
        if (target instanceof TableInterfaceType tit)
            return new ReturnTypeRef.TableBoundReturnType(targetTypeName, tit.table(), wrapper);
        if (target instanceof InterfaceType interfaceType || target instanceof UnionType unionType)
            return new ReturnTypeRef.PolymorphicReturnType(targetTypeName, wrapper);
        // PojoReturnType covers ResultType (backing class not yet reflected), scalars, enums,
        // and directive-argument type names that don't match any schema type (@nodeId(typeName:)).
        // Downstream validators report errors when required type metadata is absent.
        return new ReturnTypeRef.OtherReturnType.PojoReturnType(targetTypeName, wrapper);
    }

    private boolean isScalarOrEnum(GraphQLFieldDefinition fieldDef) {
        var baseType = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
        return baseType instanceof GraphQLScalarType || baseType instanceof GraphQLEnumType;
    }

    private String baseTypeName(GraphQLFieldDefinition fieldDef) {
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
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

    /**
     * Parses the {@code @reference(path:)} directive on {@code fieldDef} into a {@link ParsedPath}.
     *
     * <p>Returns {@code ParsedPath(List.of(), null)} when no {@code @reference} directive is present.
     * Returns a {@code ParsedPath} with a non-null {@code errorMessage()} when any path element
     * cannot be resolved (FK not found in jOOQ catalog, condition unresolved, etc.).
     */
    private ParsedPath parsePath(GraphQLFieldDefinition fieldDef) {
        var directive = fieldDef.getAppliedDirective(DIR_REFERENCE);
        if (directive == null) return new ParsedPath(List.of(), null);

        var pathArg = directive.getArgument(ARG_PATH);
        if (pathArg == null) return new ParsedPath(List.of(), null);

        Object pathValue = pathArg.getValue();
        List<?> elements = pathValue instanceof List<?> l ? l : List.of(pathValue);

        var resolvedElements = new ArrayList<ReferencePathElementRef>();
        var errors = new ArrayList<String>();

        for (var v : elements) {
            if (v instanceof Map<?, ?>) {
                parsePathElement(asMap(v), resolvedElements, errors);
            }
        }

        if (!errors.isEmpty()) {
            return new ParsedPath(List.of(), String.join("; ", errors));
        }
        return new ParsedPath(List.copyOf(resolvedElements), null);
    }

    private void parsePathElement(Map<String, Object> element, List<ReferencePathElementRef> out, List<String> errors) {
        Object keyRaw = element.get(ARG_KEY);
        Object conditionRaw = element.get(ARG_CONDITION);

        Optional<String> keyName = Optional.ofNullable(keyRaw)
            .map(Object::toString)
            .filter(s -> !s.isBlank());
        boolean hasCondition = conditionRaw instanceof Map;

        if (keyName.isPresent() && !hasCondition) {
            Optional<ForeignKey<?, ?>> fk = catalog.findForeignKey(keyName.get());
            if (fk.isPresent()) {
                var f = fk.get();
                out.add(new FkRef(
                    f.getName(),
                    f.getKey().getTable().getName(),
                    f.getTable().getName(),
                    resolveFkColumns(f.getKey().getTable(), f.getKey().getFields()),
                    resolveFkColumns(f.getTable(), f.getFields())));
            } else {
                errors.add("key '" + keyName.get() + "' could not be resolved in the jOOQ catalog");
            }
            return;
        }
        if (keyName.isPresent()) {
            Optional<ForeignKey<?, ?>> fk = catalog.findForeignKey(keyName.get());
            Map<String, Object> condMap = asMap(conditionRaw);
            String condName = extractConditionQualifiedName(condMap);
            MethodRef resolved = resolveConditionRef(condMap);
            if (fk.isPresent() && resolved != null) {
                var f = fk.get();
                out.add(new FkWithConditionRef(
                    f.getName(),
                    f.getKey().getTable().getName(),
                    f.getTable().getName(),
                    resolved,
                    resolveFkColumns(f.getKey().getTable(), f.getKey().getFields()),
                    resolveFkColumns(f.getTable(), f.getFields())));
            } else {
                if (fk.isEmpty()) errors.add("key '" + keyName.get() + "' could not be resolved in the jOOQ catalog");
                if (resolved == null) errors.add("condition method '" + condName + "' could not be resolved");
            }
            return;
        }
        if (hasCondition) {
            Map<String, Object> condMap = asMap(conditionRaw);
            MethodRef resolved = resolveConditionRef(condMap);
            if (resolved != null) {
                out.add(new ConditionOnlyRef(resolved));
            } else {
                errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
            }
            return;
        }
        // A path element with neither 'key' nor 'condition' is structurally invalid.
        errors.add("path element has neither 'key' nor 'condition'");
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
     * <p>Returns a successful {@link ServiceReflectionResult} when the class and at least one
     * matching method are found and all parameters can be classified. Returns a failed result
     * otherwise. Parameters whose name matches a GraphQL argument become
     * {@link ServiceMethodRef.ServiceParam.ArgParam}, parameters whose name matches a context key
     * become {@link ServiceMethodRef.ServiceParam.ContextParam}, and all others become
     * {@link ServiceMethodRef.ServiceParam.SourcesParam} with the element type classified by
     * {@link #classifySourcesType}.
     */
    private ServiceReflectionResult reflectServiceMethod(ExternalRef serviceRef, Set<String> argNames, Set<String> ctxKeys) {
        if (serviceRef == null || serviceRef.className() == null || serviceRef.methodName() == null) {
            return new ServiceReflectionResult(null, "service reference is incomplete");
        }
        try {
            Class<?> cls = Class.forName(serviceRef.className());
            var methods = java.util.Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals(serviceRef.methodName()))
                .toList();
            if (methods.isEmpty()) {
                return new ServiceReflectionResult(null,
                    "method '" + serviceRef.methodName() + "' not found in class '" + serviceRef.className() + "'");
            }
            var method = methods.get(0);
            var params = new ArrayList<ServiceMethodRef.ServiceParam>();
            for (var p : method.getParameters()) {
                String pName = p.isNamePresent() ? p.getName() : null;
                String displayName = pName != null ? pName : p.getType().getSimpleName();
                if (pName != null && argNames.contains(pName)) {
                    params.add(new ServiceMethodRef.ServiceParam.ArgParam(
                        displayName, p.getParameterizedType().getTypeName()));
                } else if (pName != null && ctxKeys.contains(pName)) {
                    params.add(new ServiceMethodRef.ServiceParam.ContextParam(
                        displayName, p.getParameterizedType().getTypeName()));
                } else {
                    Optional<SourcesRef> sourcesRef = classifySourcesType(p.getParameterizedType());
                    if (sourcesRef.isEmpty()) {
                        return new ServiceReflectionResult(null,
                            "parameter '" + displayName + "' in method '" + serviceRef.methodName()
                            + "' has an unrecognized sources type: '" + p.getParameterizedType().getTypeName() + "'");
                    }
                    params.add(new ServiceMethodRef.ServiceParam.SourcesParam(displayName, sourcesRef.get()));
                }
            }
            return new ServiceReflectionResult(
                new ServiceMethodRef(List.copyOf(params), method.getReturnType().getName()),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, "class '" + serviceRef.className() + "' could not be loaded");
        }
    }

    /**
     * Classifies the element type of a {@code List<?>} SOURCES parameter into a {@link SourcesRef}
     * variant, or returns {@link Optional#empty()} when the type is not recognised.
     *
     * <ul>
     *   <li>{@code List<RowN<T1,...>>} → {@link SourcesRef.RowKeyed}</li>
     *   <li>{@code List<RecordN<T1,...>>} → {@link SourcesRef.RecordKeyed}</li>
     *   <li>{@code List<SomeTableRecord>} (a {@link org.jooq.TableRecord} subclass) →
     *       {@link SourcesRef.TableRecordKeyed}</li>
     *   <li>Anything else → {@link Optional#empty()}</li>
     * </ul>
     */
    private static Optional<SourcesRef> classifySourcesType(java.lang.reflect.Type paramType) {
        if (!(paramType instanceof java.lang.reflect.ParameterizedType pt)
                || pt.getRawType() != java.util.List.class) {
            return Optional.empty();
        }
        java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
        if (typeArgs.length != 1) {
            return Optional.empty();
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
                    return Optional.of(new SourcesRef.RowKeyed(pkTypes));
                }
            }
            if (rawName.startsWith("org.jooq.Record")) {
                String suffix = rawName.substring("org.jooq.Record".length());
                if (suffix.matches("\\d+")) {
                    List<String> pkTypes = java.util.Arrays.stream(ept.getActualTypeArguments())
                        .map(java.lang.reflect.Type::getTypeName)
                        .toList();
                    return Optional.of(new SourcesRef.RecordKeyed(pkTypes));
                }
            }
        } else if (elementType instanceof Class<?> elementClass
                && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
            return Optional.of(new SourcesRef.TableRecordKeyed(elementClass.getName()));
        }

        return Optional.empty();
    }

    private String extractConditionQualifiedName(Map<String, Object> conditionMap) {
        Object name = conditionMap.get(ARG_NAME);
        return name != null ? name.toString() : "unknown";
    }

    // ===== Builder-private result containers =====

    /**
     * Carries the result of {@link #reflectServiceMethod}: either a successfully resolved
     * {@link ServiceMethodRef} or a failure reason string.
     */
    private record ServiceReflectionResult(ServiceMethodRef ref, String failureReason) {
        boolean failed() { return failureReason != null; }
    }

    /**
     * Carries the result of {@link #parsePath}: either a fully resolved list of path elements or
     * an error message. When {@code errorMessage()} is non-null the {@code elements()} list is
     * empty and the containing field must be classified as
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}.
     */
    private record ParsedPath(List<ReferencePathElementRef> elements, String errorMessage) {
        boolean hasError() { return errorMessage != null; }
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
