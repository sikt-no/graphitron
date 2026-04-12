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
import no.sikt.graphitron.rewrite.model.FieldWrapper.ColumnOrder;
import no.sikt.graphitron.rewrite.model.FieldWrapper.ColumnOrder.ColumnOrderEntry;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.FieldConditionRef;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.rewrite.model.ArgumentRef;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.SourcesRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.JoinStep.ConditionJoin;
import no.sikt.graphitron.rewrite.model.JoinStep.FkJoin;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.jooq.ForeignKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
                objType.getFieldDefinitions().forEach(fieldDef ->
                    fields.put(
                        FieldCoordinates.coordinates(objType.getName(), fieldDef.getName()),
                        classifyField(fieldDef, objType.getName(), parentType)));
            });
        return new GraphitronSchema(types, Collections.unmodifiableMap(fields));
    }

    /**
     * Resolves a list of raw input fields against a {@link TableRef} into a {@link TableInputType}.
     * Returns {@link UnclassifiedType} when any field's column cannot be resolved.
     */
    private GraphitronType buildTableInputType(String name, SourceLocation location,
            List<GraphQLInputObjectField> fields, TableRef tableRef) {
        var errors = new ArrayList<String>();
        var resolvedFields = new ArrayList<InputField.ColumnField>();
        for (var f : fields) {
            var field = buildInputColumnField(f, name, tableRef);
            if (field.isEmpty()) {
                String colName = f.hasAppliedDirective(DIR_FIELD)
                    ? argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName()) : f.getName();
                errors.add("field '" + f.getName() + "' column '" + colName + "' could not be resolved in the jOOQ table"
                    + candidateHint(colName, catalog.columnSqlNamesOf(tableRef.tableName())));
            } else {
                resolvedFields.add(field.get());
            }
        }
        if (!errors.isEmpty()) {
            return new UnclassifiedType(name, location, String.join("; ", errors));
        }
        return new TableInputType(name, location, tableRef, List.copyOf(resolvedFields));
    }

    // ===== Type classification =====

    private Map<String, GraphitronType> buildTypes() {
        // First pass: classify every type. All types — including input types — are fully resolved
        // here: input types without @table are classified by inspecting field usages in the schema
        // (see buildInputType / findReturnTablesForInput). Interface and union participant lists
        // are left empty at this stage because participant lookup requires the full type map.
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
            case TableInterfaceType tit       -> enrichTableInterfaceType(tit, result);
            case InterfaceType it             -> enrichInterfaceType(it, result);
            case UnionType ut                 -> enrichUnionType(ut, result);
            case TableType ignored            -> type;
            case NodeType ignored             -> type;
            case ResultType ignored           -> type;
            case RootType ignored             -> type;
            case ErrorType ignored            -> type;
            case InputType ignored            -> type;
            case TableInputType ignored       -> type;
            case UnclassifiedType ignored     -> type;
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
            if (gt instanceof TableBackedType tbt && !(gt instanceof TableInterfaceType)) {
                String discriminatorValue = argString(schema.getObjectType(typeName), DIR_DISCRIMINATOR, ARG_VALUE).orElse(null);
                result.add(new ParticipantRef(typeName, tbt.table(), discriminatorValue));
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
                return new RootType(name, location, fieldCoordinatesOf(objType));
            }
            String typeConflict = detectTypeDirectiveConflict(objType);
            if (typeConflict != null) {
                return new GraphitronType.UnclassifiedType(name, location, typeConflict);
            }
            if (objType.hasAppliedDirective(DIR_TABLE)) {
                return buildTableType(objType);
            }
            if (objType.hasAppliedDirective(DIR_RECORD)) {
                return new GraphitronType.PojoResultType(name, location, fieldCoordinatesOf(objType));
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

    private static List<FieldCoordinates> fieldCoordinatesOf(GraphQLObjectType objType) {
        return objType.getFieldDefinitions().stream()
            .map(f -> FieldCoordinates.coordinates(objType.getName(), f.getName()))
            .toList();
    }

    private GraphitronType buildTableType(GraphQLObjectType objType) {
        String name = objType.getName();
        SourceLocation location = locationOf(objType);
        String tableName = argString(objType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog"
                + candidateHint(tableName, catalog.allTableSqlNames()));
        }
        TableRef tableRef = tableOpt.get();
        if (!objType.hasAppliedDirective(DIR_NODE)) {
            return new TableType(name, location, tableRef, fieldCoordinatesOf(objType));
        }
        // @node: resolve key columns; any failure → UnclassifiedType
        String typeId = argString(objType, DIR_NODE, ARG_TYPE_ID).orElse(null);
        List<String> keyColumnNames = argStringList(objType, DIR_NODE, ARG_KEY_COLUMNS);
        var keyColumnErrors = new ArrayList<String>();
        var keyColumns = new ArrayList<ColumnRef>();
        for (String colName : keyColumnNames) {
            Optional<ColumnRef> kc = resolveKeyColumn(colName, tableRef.tableName());
            if (kc.isEmpty()) {
                keyColumnErrors.add("key column '" + colName + "' in @node could not be resolved in the jOOQ table"
                    + candidateHint(colName, catalog.columnSqlNamesOf(tableRef.tableName())));
            } else {
                keyColumns.add(kc.get());
            }
        }
        if (!keyColumnErrors.isEmpty()) {
            return new UnclassifiedType(name, location, String.join("; ", keyColumnErrors));
        }
        return new NodeType(name, location, tableRef, typeId, List.copyOf(keyColumns), fieldCoordinatesOf(objType));
    }

    private GraphitronType buildTableInterfaceType(GraphQLInterfaceType iface) {
        String name = iface.getName();
        SourceLocation location = locationOf(iface);
        String tableName = argString(iface, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        Optional<TableRef> tableOpt = resolveTable(tableName);
        if (tableOpt.isEmpty()) {
            return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog"
                + candidateHint(tableName, catalog.allTableSqlNames()));
        }
        String discriminatorColumn = argString(iface, DIR_DISCRIMINATE, ARG_ON).orElse(null);
        return new TableInterfaceType(name, location, discriminatorColumn, tableOpt.get(), List.of());
    }

    private Optional<TableRef> resolveTable(String sqlName) {
        return catalog.findTable(sqlName)
            .map(e -> {
                var pk = e.table().getPrimaryKey();
                Optional<List<ColumnRef>> pkColumns = pk == null
                    ? Optional.empty()
                    : Optional.of(pk.getFields().stream()
                        .map(f -> catalog.findColumn(e.table(), f.getName()))
                        .<JooqCatalog.ColumnEntry>flatMap(Optional::stream)
                        .map(ce -> new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()))
                        .toList());
                return new TableRef(sqlName, e.javaFieldName(), e.table().getClass().getSimpleName(), pkColumns);
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
        List<ErrorType.Handler> handlers = items.stream()
            .filter(v -> v instanceof Map)
            .map(v -> parseErrorHandler(asMap(v)))
            .toList();
        return new ErrorType(name, location, handlers);
    }

    private GraphitronType buildInputType(GraphQLInputObjectType inputType) {
        String name = inputType.getName();
        SourceLocation location = locationOf(inputType);
        var filteredFields = inputType.getFieldDefinitions().stream()
            .filter(f -> !f.hasAppliedDirective(DIR_NOT_GENERATED))
            .toList();
        if (inputType.hasAppliedDirective(DIR_TABLE)) {
            String tableName = argString(inputType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
            Optional<TableRef> tableOpt = resolveTable(tableName);
            if (tableOpt.isEmpty()) {
                return new UnclassifiedType(name, location, "table '" + tableName + "' could not be resolved in the jOOQ catalog"
                    + candidateHint(tableName, catalog.allTableSqlNames()));
            }
            return buildTableInputType(name, location, filteredFields, tableOpt.get());
        }
        // No @table — inspect all field usages across the schema to decide the final classification.
        // Fields annotated with @service, @tableMethod, or @mutation forward their inputs as Java
        // parameters rather than binding them to a database table; they are excluded here so they
        // do not force implicit table promotion.
        var tables = findReturnTablesForInput(name);
        if (tables.isEmpty()) {
            return new InputType(name, location);
        }
        if (tables.size() > 1) {
            var tableNames = String.join("', '", tables.keySet());
            return new UnclassifiedType(name, location,
                "used as argument on fields with conflicting return tables: '" + tableNames + "'");
        }
        return buildTableInputType(name, location, filteredFields, tables.values().iterator().next());
    }

    /**
     * Walks all field definitions in the schema and returns a map from lowercase SQL table name to
     * {@link TableRef} for every distinct return table found on fields that:
     * <ol>
     *   <li>are defined on a {@link GraphQLObjectType},</li>
     *   <li>have at least one argument whose base type is {@code inputTypeName},</li>
     *   <li>are <em>not</em> annotated with {@code @service}, {@code @tableMethod}, or
     *       {@code @mutation} (those directives forward their inputs as Java parameters and do not
     *       imply a table binding), and</li>
     *   <li>have a return type that is a {@link GraphQLObjectType} with {@code @table}.</li>
     * </ol>
     *
     * <p>Used by {@link #buildInputType} to eagerly classify a non-{@code @table} input type
     * as {@link GraphitronType.TableInputType} (one table), {@link GraphitronType.UnclassifiedType}
     * (conflicting tables), or {@link GraphitronType.InputType} (no table usage) — entirely
     * within the type-classification pass, without any dependency on field classification.
     */
    private Map<String, TableRef> findReturnTablesForInput(String inputTypeName) {
        var tables = new LinkedHashMap<String, TableRef>();
        for (var namedType : schema.getAllTypesAsList()) {
            if (!(namedType instanceof GraphQLObjectType objType)) continue;
            if (namedType.getName().startsWith("__")) continue;
            for (var fieldDef : objType.getFieldDefinitions()) {
                if (fieldDef.hasAppliedDirective(DIR_SERVICE)
                        || fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)
                        || fieldDef.hasAppliedDirective(DIR_MUTATION)) continue;
                boolean usesInput = fieldDef.getArguments().stream()
                    .anyMatch(arg -> inputTypeName.equals(
                        ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(arg.getType())).getName()));
                if (!usesInput) continue;
                var returnBase = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
                if (!(returnBase instanceof GraphQLObjectType returnObj)) continue;
                if (!returnObj.hasAppliedDirective(DIR_TABLE)) continue;
                String tableName = argString(returnObj, DIR_TABLE, ARG_NAME)
                    .orElse(returnObj.getName().toLowerCase());
                if (!tables.containsKey(tableName.toLowerCase())) {
                    resolveTable(tableName).ifPresent(tr -> tables.put(tableName.toLowerCase(), tr));
                }
            }
        }
        return tables;
    }

    private Optional<InputField.ColumnField> buildInputColumnField(GraphQLInputObjectField field, String parentTypeName, TableRef resolvedTable) {
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
            .map(e -> new InputField.ColumnField(parentTypeName, name, locationOf(field), typeName, nonNull, list,
                new ColumnRef(e.sqlName(), e.javaName(), e.columnClass())));
    }

    private ErrorType.Handler parseErrorHandler(Map<String, Object> item) {
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
        return new ErrorType.Handler(handlerType, className, code, sqlState, matches, description);
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
    private GraphitronField classifyObjectReturnChildField(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType parentTableType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);
        String rawTypeName = baseTypeName(fieldDef);

        // For connection types the element type is edges.node, not the connection wrapper type.
        String elementTypeName = isConnectionType(rawTypeName)
            ? connectionElementTypeName(rawTypeName)
            : rawTypeName;
        GraphitronType elementType = types.get(elementTypeName);

        if (elementType instanceof TableBackedType tbt && !(elementType instanceof TableInterfaceType)) {
            var wrapper = buildWrapper(fieldDef, tbt.table().tableName());
            if (wrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns in table '" + tbt.table().tableName() + "'");
            var returnType = (ReturnTypeRef.TableBoundReturnType) resolveReturnType(elementTypeName, wrapper);
            var referencePath = parsePath(fieldDef, parentTableType.table().tableName());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, referencePath.errorMessage());
            }
            var rt = returnType.table();
            var argErrors1 = new ArrayList<String>();
            var args = classifyArgsList(fieldDef, rt, false, argErrors1);
            if (args == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors1));
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
            var wrapper = buildWrapper(fieldDef, tableInterfaceType.table().tableName());
            if (wrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns in table '" + tableInterfaceType.table().tableName() + "'");
            return new TableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper));
        }

        if (elementType instanceof InterfaceType interfaceType) {
            return new InterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef, null)));
        }

        if (elementType instanceof UnionType unionType) {
            return new UnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef, null)));
        }

        // NestingField: a plain object type in the schema with no Graphitron classification.
        // Its fields are resolved from the same table context as the parent.
        if (schema.getType(elementTypeName) instanceof GraphQLObjectType graphQLObjectType && elementType == null) {
            var wrapper = buildWrapper(fieldDef, parentTableType.table().tableName());
            if (wrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns in table '" + parentTableType.table().tableName() + "'");
            return new NestingField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, parentTableType.table(), wrapper));
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
     * Returns the SQL table name for a GraphQL type name when the type is table-backed
     * ({@link GraphitronType.TableType} or {@link GraphitronType.TableInterfaceType}), or
     * {@code null} when the type has no associated table.
     */
    private String getTableSqlNameForType(String typeName) {
        var type = types.get(typeName);
        if (type instanceof TableBackedType tbt) return tbt.table().tableName();
        return null;
    }

    /**
     * Builds a {@link FieldWrapper} from the return type shape of the field and any
     * {@code @defaultOrder} directive.
     *
     * <p>{@code tableSqlName} is the SQL name of the return type's table. When non-null,
     * {@code @defaultOrder} columns are resolved against that table via the jOOQ catalog. When
     * {@code null} (polymorphic or other return type), order resolution is skipped.
     *
     * <p>Returns {@code null} when a {@code @defaultOrder} directive is present but its column
     * lookup fails; the caller must classify the field as
     * {@link GraphitronField.UnclassifiedField} in that case.
     *
     * <p>Connection is detected structurally — the return type must be a {@link GraphQLObjectType}
     * that has an {@code edges} field whose element type in turn has a {@code node} field.
     *
     * <p>{@code @orderBy} enum value specs are not populated here — that is deferred to P4.
     */
    private FieldWrapper buildWrapper(GraphQLFieldDefinition fieldDef, String tableSqlName) {
        GraphQLType fieldType = fieldDef.getType();
        boolean outerNullable = !(fieldType instanceof GraphQLNonNull);
        GraphQLType unwrappedOnce = GraphQLTypeUtil.unwrapNonNull(fieldType);

        if (unwrappedOnce instanceof GraphQLList listType) {
            boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
            var order = resolveDefaultOrder(fieldDef, tableSqlName);
            if (order == null && tableSqlName != null) return null;
            return new FieldWrapper.List(outerNullable, itemNullable, order, List.of());
        }

        String typeName = baseTypeName(fieldDef);
        if (isConnectionType(typeName)) {
            boolean itemNullable = connectionItemNullable(typeName);
            var order = resolveDefaultOrder(fieldDef, tableSqlName);
            if (order == null && tableSqlName != null) return null;
            return new FieldWrapper.Connection(outerNullable, itemNullable, order, List.of());
        }

        return new FieldWrapper.Single(outerNullable);
    }

    /**
     * Resolves the effective default order for a list or connection field.
     *
     * <p>When an explicit {@code @defaultOrder} directive is present, delegates to
     * {@link #resolveColumnOrder}. When no directive is present and {@code tableSqlName} is
     * non-null, falls back to the table's primary-key columns with ascending direction, giving
     * every table-backed list field a deterministic ordering by default.
     *
     * <p>Returns {@code null} when:
     * <ul>
     *   <li>{@code tableSqlName} is {@code null} (non-table-bound field — ordering is opaque), or</li>
     *   <li>{@code @defaultOrder} is present but column/index resolution fails, or</li>
     *   <li>no {@code @defaultOrder} and the table has no primary key.</li>
     * </ul>
     * Callers that pass a non-null {@code tableSqlName} must treat a {@code null} return as a
     * build failure and produce an {@link GraphitronField.UnclassifiedField}.
     */
    private ColumnOrder resolveDefaultOrder(GraphQLFieldDefinition fieldDef, String tableSqlName) {
        if (tableSqlName == null) return null;
        if (fieldDef.hasAppliedDirective(DIR_DEFAULT_ORDER)) {
            return resolveColumnOrder(fieldDef, tableSqlName);
        }
        var pkCols = catalog.findPkColumns(tableSqlName);
        if (pkCols.isEmpty()) return null;
        return new ColumnOrder(
            pkCols.stream()
                .map(ce -> new ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
                .toList(),
            "ASC");
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
     * Resolves the {@code @defaultOrder} directive on a field into a fully-normalised
     * {@link ColumnOrder} against {@code tableSqlName}.
     *
     * <p>All three source variants are resolved at build time:
     * <ul>
     *   <li>{@code index:} — columns come from the named index via the jOOQ catalog.</li>
     *   <li>{@code primaryKey:} — columns come from the table's primary key.</li>
     *   <li>{@code fields:} — each column name is looked up in the table via the jOOQ catalog.</li>
     * </ul>
     * Returns {@code null} when any lookup fails (index not found, PK absent, or a column name is
     * unresolvable). The caller must treat {@code null} as a classification failure.
     *
     * <p>Only called when the directive is confirmed present.
     */
    private ColumnOrder resolveColumnOrder(GraphQLFieldDefinition fieldDef, String tableSqlName) {
        var dir = fieldDef.getAppliedDirective(DIR_DEFAULT_ORDER);

        // direction has a default of ASC in the directive; absent arg means ASC.
        String direction = "ASC";
        var dirArg = dir.getArgument(ARG_DIRECTION);
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
                var colsOpt = catalog.findIndexColumns(tableSqlName, indexName);
                if (colsOpt.isEmpty() || colsOpt.get().isEmpty()) return null;
                var entries = colsOpt.get().stream()
                    .map(ce -> new ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
                    .toList();
                return new ColumnOrder(entries, direction);
            }
        }

        var pkArg = dir.getArgument(ARG_PRIMARY_KEY);
        boolean primaryKey = pkArg != null && (
            pkArg.getValue() instanceof BooleanValue bv ? bv.isValue()
            : Boolean.TRUE.equals(pkArg.getValue()));
        if (primaryKey) {
            var pkCols = catalog.findPkColumns(tableSqlName);
            if (pkCols.isEmpty()) return null;
            var entries = pkCols.stream()
                .map(ce -> new ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
                .toList();
            return new ColumnOrder(entries, direction);
        }

        var fieldsArg = dir.getArgument(ARG_FIELDS);
        if (fieldsArg != null) {
            Object value = fieldsArg.getValue();
            List<?> items = value instanceof List<?> l ? l : List.of(value);
            var entries = new ArrayList<ColumnOrderEntry>();
            for (var item : items) {
                if (!(item instanceof Map)) continue;
                var map = asMap(item);
                Object nameRaw = map.get(ARG_NAME);
                if (nameRaw == null) return null;
                String colName = nameRaw.toString().strip();
                String collation = Optional.ofNullable(map.get(ARG_COLLATE)).map(Object::toString).map(String::strip).orElse(null);
                var ceOpt = catalog.findColumn(tableSqlName, colName);
                if (ceOpt.isEmpty()) return null;
                var ce = ceOpt.get();
                entries.add(new ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), collation));
            }
            return new ColumnOrder(entries, direction);
        }

        return null;
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
        if (parentType instanceof TableBackedType tbt && !(parentType instanceof TableInterfaceType)) {
            return classifyChildFieldOnTableType(fieldDef, parentTypeName, tbt);
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
            var wrapper = buildWrapper(fieldDef, getTableSqlNameForType(elementTypeName));
            if (wrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns for @service field");
            ReturnTypeRef returnType = resolveReturnType(elementTypeName, wrapper);
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<String> contextArgs = parseContextArguments(fieldDef, DIR_SERVICE);
            Set<String> argNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            ServiceReflectionResult serviceReflection = reflectServiceMethod(serviceRef.className(), serviceRef.methodName(), argNames, new java.util.HashSet<>(contextArgs));
            if (serviceReflection.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "service method could not be resolved — " + serviceReflection.failureReason());
            }
            MethodRef method = serviceReflection.ref();
            var argErrors2 = new ArrayList<String>();
            var args = classifyArgsList(fieldDef, null, true, argErrors2);
            if (args == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors2));
            return switch (returnType) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new QueryField.QueryServiceTableField(parentTypeName, name, location, tb, args, contextArgs, method);
                case ReturnTypeRef.ResultReturnType r ->
                    new QueryField.QueryServiceRecordField(parentTypeName, name, location, r, args, contextArgs, method);
                case ReturnTypeRef.ScalarReturnType s ->
                    new QueryField.QueryServiceRecordField(parentTypeName, name, location, s, args, contextArgs, method);
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (name.equals("_entities")) {
            return new QueryField.QueryEntityField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef, null)));
        }

        if (name.equals("node")) {
            return new QueryField.QueryNodeField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef, null)));
        }

        if (hasLookupKeyAnywhere(fieldDef)) {
            String lookupTypeName = baseTypeName(fieldDef);
            var wrapper = buildWrapper(fieldDef, getTableSqlNameForType(lookupTypeName));
            if (wrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns for @lookupKey field");
            var returnType = resolveReturnType(lookupTypeName, wrapper);
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "@lookupKey requires a @table-annotated return type");
            }
            var argErrors3 = new ArrayList<String>();
            var arguments = classifyArgsList(fieldDef, tb.table(), false, argErrors3);
            if (arguments == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors3));
            return new QueryField.QueryLookupTableField(parentTypeName, name, location, tb, arguments);
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            var wrapper = buildWrapper(fieldDef, getTableSqlNameForType(elementTypeName));
            if (wrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns for @tableMethod field");
            var returnType = resolveReturnType(elementTypeName, wrapper);
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tb)) {
                return new GraphitronField.UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "@tableMethod requires a @table-annotated return type");
            }
            var argErrors4 = new ArrayList<String>();
            var args = classifyArgsList(fieldDef, null, true, argErrors4);
            if (args == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors4));
            var qtmRef = parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF);
            return new QueryField.QueryTableMethodTableField(parentTypeName, name, location,
                tb,
                qtmRef != null ? qtmRef.className() : null,
                qtmRef != null ? qtmRef.methodName() : null,
                args,
                parseContextArguments(fieldDef, DIR_TABLE_METHOD));
        }

        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
        GraphitronType elementType = types.get(elementTypeName);

        if (elementType instanceof TableBackedType tbt && !(elementType instanceof TableInterfaceType)) {
            var wrapper = buildWrapper(fieldDef, tbt.table().tableName());
            if (wrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns in table '" + tbt.table().tableName() + "'");
            var returnType = (ReturnTypeRef.TableBoundReturnType) resolveReturnType(elementTypeName, wrapper);
            var argErrors5 = new ArrayList<String>();
            var args = classifyArgsList(fieldDef, returnType.table(), false, argErrors5);
            if (args == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors5));
            return new QueryField.QueryTableField(parentTypeName, name, location, returnType, args);
        }
        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef, tableInterfaceType.table().tableName());
            if (wrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns in table '" + tableInterfaceType.table().tableName() + "'");
            return new QueryField.QueryTableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper));
        }
        if (elementType instanceof InterfaceType interfaceType) {
            return new QueryField.QueryInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef, null)));
        }
        if (elementType instanceof UnionType unionType) {
            return new QueryField.QueryUnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef, null)));
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
            String mutSvcTypeName = baseTypeName(fieldDef);
            var wrapper = buildWrapper(fieldDef, getTableSqlNameForType(mutSvcTypeName));
            if (wrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns for @service mutation field");
            ReturnTypeRef returnType = resolveReturnType(mutSvcTypeName, wrapper);
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<String> contextArgs = parseContextArguments(fieldDef, DIR_SERVICE);
            Set<String> argNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            ServiceReflectionResult serviceReflection = reflectServiceMethod(serviceRef.className(), serviceRef.methodName(), argNames, new java.util.HashSet<>(contextArgs));
            if (serviceReflection.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "service method could not be resolved — " + serviceReflection.failureReason());
            }
            MethodRef method = serviceReflection.ref();
            var argErrors6 = new ArrayList<String>();
            var args = classifyArgsList(fieldDef, null, true, argErrors6);
            if (args == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors6));
            return switch (returnType) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new MutationField.MutationServiceTableField(parentTypeName, name, location, tb, args, contextArgs, method);
                case ReturnTypeRef.ResultReturnType r ->
                    new MutationField.MutationServiceRecordField(parentTypeName, name, location, r, args, contextArgs, method);
                case ReturnTypeRef.ScalarReturnType s ->
                    new MutationField.MutationServiceRecordField(parentTypeName, name, location, s, args, contextArgs, method);
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            String typeName = getMutationTypeName(fieldDef);
            if (typeName != null) {
                String rawReturn = baseTypeName(fieldDef);
                ReturnTypeRef returnType = resolveReturnType(rawReturn, buildWrapper(fieldDef, getTableSqlNameForType(rawReturn)));
                var argErrors7 = new ArrayList<String>();
                var arguments = classifyArgsList(fieldDef, null, true, argErrors7);
                if (arguments == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors7));
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
     * Classifies all arguments on {@code fieldDef} against the table context {@code rt}.
     * Returns the classified list, or {@code null} if any argument fails classification
     * (in which case each failure reason has been appended to {@code errors}).
     */
    private List<ArgumentRef> classifyArgsList(
            GraphQLFieldDefinition fieldDef, TableRef rt, boolean useParamForScalars, List<String> errors) {
        var result = new ArrayList<ArgumentRef>(fieldDef.getArguments().size());
        for (var arg : fieldDef.getArguments()) {
            var classified = classifyArgument(arg, rt, useParamForScalars, errors);
            if (classified != null) result.add(classified);
        }
        return errors.isEmpty() ? List.copyOf(result) : null;
    }

    /**
     * Classifies a single GraphQL argument into an {@link ArgumentRef} variant.
     *
     * <p>{@code rt} is the resolved return table of the enclosing field, used only for binding
     * scalar arguments to database columns (may be {@code null} when there is no table context).
     * {@code useParamForScalars} suppresses column binding: when {@code true}, scalar arguments
     * become {@link ArgumentRef.MethodParamArg.ScalarParamArg}. Returns {@code null} and appends to
     * {@code errors} when classification fails.
     */
    private ArgumentRef classifyArgument(GraphQLArgument arg, TableRef rt, boolean useParamForScalars, List<String> errors) {
        String name = arg.getName();
        GraphQLType type = arg.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();

        if (arg.hasAppliedDirective(DIR_CONDITION)) {
            errors.add("argument '" + name + "': @condition is only supported on field definitions, not on arguments");
            return null;
        }
        if (arg.hasAppliedDirective(DIR_ORDER_BY)) {
            return resolveOrderByArg(arg, name, typeName, nonNull, list, errors);
        }
        if (types.containsKey(typeName)) {
            return types.get(typeName) instanceof TableInputType
                ? new ArgumentRef.TableArg.InputFilterArg(name, typeName, nonNull, list)
                : new ArgumentRef.MethodParamArg.ObjectParamArg(name, typeName, nonNull, list);
        }
        // Scalar arg
        if (useParamForScalars) {
            return new ArgumentRef.MethodParamArg.ScalarParamArg(name, typeName, nonNull, list);
        }
        String columnName = argString(arg, DIR_FIELD, ARG_NAME).orElse(name);
        if (rt == null) {
            errors.add("argument '" + name + "': column '" + columnName + "' could not be resolved (no table context)");
            return null;
        }
        var col = catalog.findColumn(rt.tableName(), columnName);
        if (col.isEmpty()) {
            errors.add("argument '" + name + "': column '" + columnName + "' could not be resolved in table '"
                + rt.tableName() + "'" + candidateHint(columnName, catalog.columnSqlNamesOf(rt.tableName())));
            return null;
        }
        return new ArgumentRef.TableArg.ColumnFilterArg(name, typeName, nonNull, list, col.get().javaName(), col.get().columnClass());
    }

    /**
     * Resolves an {@code @orderBy} argument to an {@link ArgumentRef.TableArg.OrderByArg}.
     *
     * <p>Looks up the argument's input type in the schema and expects it to contain exactly one
     * enum field whose values carry {@code @order} directives (the sort field) and exactly one
     * other enum field (the direction field). Appends to {@code errors} and returns {@code null}
     * if the structure is invalid.
     */
    private ArgumentRef resolveOrderByArg(GraphQLArgument arg, String name, String typeName, boolean nonNull, boolean list, List<String> errors) {
        var rawType = schema.getType(typeName);
        if (!(rawType instanceof GraphQLInputObjectType inputType)) {
            errors.add("argument '" + name + "': @orderBy argument type '" + typeName + "' is not an input type");
            return null;
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
                    errors.add("argument '" + name + "': @orderBy input type '" + typeName + "' must have exactly one sort enum field, but found multiple");
                    return null;
                }
                sortFieldName = field.getName();
            } else {
                if (directionFieldName != null) {
                    errors.add("argument '" + name + "': @orderBy input type '" + typeName + "' must have exactly one direction field, but found multiple");
                    return null;
                }
                directionFieldName = field.getName();
            }
        }
        if (sortFieldName == null) {
            errors.add("argument '" + name + "': @orderBy input type '" + typeName + "' has no sort enum field (no enum values with @order)");
            return null;
        }
        if (directionFieldName == null) {
            errors.add("argument '" + name + "': @orderBy input type '" + typeName + "' has no direction field");
            return null;
        }
        return new ArgumentRef.TableArg.OrderByArg(name, typeName, nonNull, list, sortFieldName, directionFieldName);
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
            ServiceReflectionResult serviceReflection = reflectServiceMethod(serviceRef.className(), serviceRef.methodName(), argNames, new java.util.HashSet<>(contextArguments));
            if (serviceReflection.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "service method could not be resolved — " + serviceReflection.failureReason());
            }
            var argErrors8 = new ArrayList<String>();
            var arguments = classifyArgsList(fieldDef, null, true, argErrors8);
            if (arguments == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors8));
            var servicePath = parsePath(fieldDef);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, servicePath.errorMessage());
            }
            MethodRef method = serviceReflection.ref();
            var rsvcWrapper = buildWrapper(fieldDef, getTableSqlNameForType(elementTypeName));
            if (rsvcWrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns for @service child field on result type");
            return switch (resolveReturnType(elementTypeName, rsvcWrapper)) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new ServiceTableField(parentTypeName, name, location, tb,
                        servicePath.elements(), arguments, contextArguments, method);
                case ReturnTypeRef.ResultReturnType r ->
                    new ServiceRecordField(parentTypeName, name, location, r,
                        servicePath.elements(), arguments, contextArguments, method);
                case ReturnTypeRef.ScalarReturnType s ->
                    new ServiceRecordField(parentTypeName, name, location, s,
                        servicePath.elements(), arguments, contextArguments, method);
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
        return switch (resolveReturnType(elementTypeName, buildWrapper(fieldDef, getTableSqlNameForType(elementTypeName)))) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                var argErrors9 = new ArrayList<String>();
                var args = classifyArgsList(fieldDef, tb.table(), false, argErrors9);
                if (args == null) yield new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors9));
                boolean hasLookupKey = hasLookupKeyAnywhere(fieldDef);
                if (hasLookupKey) {
                    yield new RecordLookupTableField(parentTypeName, name, location, tb, objectPath.elements(), args);
                }
                yield new RecordTableField(parentTypeName, name, location, tb,
                    objectPath.elements(), new FieldConditionRef.NoFieldCondition(), args);
            }
            case ReturnTypeRef.ResultReturnType r ->
                new RecordField(parentTypeName, name, location, r, columnName);
            case ReturnTypeRef.ScalarReturnType s ->
                new RecordField(parentTypeName, name, location, s, columnName);
            case ReturnTypeRef.PolymorphicReturnType p ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, "@record type returning a polymorphic type is not yet supported");
        };
    }

    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType tableType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            ExternalRef serviceRef = parseExternalRef(fieldDef, DIR_SERVICE, ARG_SERVICE_REF);
            List<String> contextArguments = parseContextArguments(fieldDef, DIR_SERVICE);
            Set<String> argNames = fieldDef.getArguments().stream().map(GraphQLArgument::getName).collect(Collectors.toSet());
            ServiceReflectionResult serviceReflection = reflectServiceMethod(serviceRef.className(), serviceRef.methodName(), argNames, new java.util.HashSet<>(contextArguments));
            if (serviceReflection.failed()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "service method could not be resolved — " + serviceReflection.failureReason());
            }
            MethodRef method = serviceReflection.ref();
            var argErrors10 = new ArrayList<String>();
            var arguments = classifyArgsList(fieldDef, null, true, argErrors10);
            if (arguments == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors10));
            // Service reconnect path: starts from the service return type's table (not the parent).
            var servicePath = parsePath(fieldDef);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, servicePath.errorMessage());
            }
            var svcWrapper = buildWrapper(fieldDef, getTableSqlNameForType(elementTypeName));
            if (svcWrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns for @service child field");
            return switch (resolveReturnType(elementTypeName, svcWrapper)) {
                case ReturnTypeRef.TableBoundReturnType tb ->
                    new ServiceTableField(parentTypeName, name, location, tb,
                        servicePath.elements(), arguments, contextArguments, method);
                case ReturnTypeRef.ResultReturnType r ->
                    new ServiceRecordField(parentTypeName, name, location, r,
                        servicePath.elements(), arguments, contextArguments, method);
                case ReturnTypeRef.ScalarReturnType s ->
                    new ServiceRecordField(parentTypeName, name, location, s,
                        servicePath.elements(), arguments, contextArguments, method);
                case ReturnTypeRef.PolymorphicReturnType p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, "@service returning a polymorphic type is not yet supported");
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) {
            var externalPath = parsePath(fieldDef, tableType.table().tableName());
            if (externalPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, externalPath.errorMessage());
            }
            String extTypeName = baseTypeName(fieldDef);
            return new ComputedField(parentTypeName, name, location,
                resolveReturnType(extTypeName, buildWrapper(fieldDef, getTableSqlNameForType(extTypeName))),
                externalPath.elements());
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = isConnectionType(rawTypeName) ? connectionElementTypeName(rawTypeName) : rawTypeName;
            var tmWrapper = buildWrapper(fieldDef, getTableSqlNameForType(elementTypeName));
            if (tmWrapper == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "could not resolve @defaultOrder columns for @tableMethod child field");
            var returnType = resolveReturnType(elementTypeName, tmWrapper);
            var argErrors11 = new ArrayList<String>();
            var args = classifyArgsList(fieldDef, null, true, argErrors11);
            if (args == null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, String.join("; ", argErrors11));
            var tableMethodPath = parsePath(fieldDef, tableType.table().tableName());
            if (tableMethodPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, tableMethodPath.errorMessage());
            }
            var tmRef = parseExternalRef(fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF);
            return new TableMethodField(parentTypeName, name, location,
                returnType,
                tableMethodPath.elements(),
                tmRef != null ? tmRef.className() : null,
                tmRef != null ? tmRef.methodName() : null,
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
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not exist in the schema"
                        + candidateHint(typeName.get(), new ArrayList<>(types.keySet())));
                }
                if (!(targetGType instanceof NodeType targetNodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not have @node");
                }
                TableRef parentTable = tableType.table();
                var nodeRefPath = parsePath(fieldDef, tableType.table().tableName());
                if (nodeRefPath.hasError()) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, nodeRefPath.errorMessage());
                }
                return new NodeIdReferenceField(parentTypeName, name, location, typeName.get(), targetType, parentTable,
                    targetNodeType.typeId(), targetNodeType.nodeKeyColumns(), nodeRefPath.elements());
            } else {
                if (!(tableType instanceof NodeType nodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        "@nodeId requires the containing type to have @node");
                }
                return new NodeIdField(parentTypeName, name, location, nodeType.typeId(), nodeType.nodeKeyColumns());
            }
        }

        boolean hasFieldDirective = fieldDef.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDirective
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        boolean javaNamePresent = hasFieldDirective
            && argString(fieldDef, DIR_FIELD, ARG_JAVA_NAME).isPresent();

        if (fieldDef.hasAppliedDirective(DIR_REFERENCE)) {
            var refPath = parsePath(fieldDef, tableType.table().tableName());
            if (refPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, refPath.errorMessage());
            }
            Optional<ColumnRef> column = resolveColumnForReference(columnName, refPath.elements(), tableType);
            if (column.isEmpty()) {
                String terminalTable = terminalTableSqlNameForReference(refPath.elements(), tableType);
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    "column '" + columnName + "' could not be resolved in the jOOQ table"
                    + (terminalTable != null ? candidateHint(columnName, catalog.columnSqlNamesOf(terminalTable)) : ""));
            }
            return new ColumnReferenceField(parentTypeName, name, location, columnName, column.get(), refPath.elements(), javaNamePresent);
        }

        Optional<ColumnRef> column = resolveColumn(columnName, tableType);
        if (column.isEmpty()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                "column '" + columnName + "' could not be resolved in the jOOQ table"
                + candidateHint(columnName, catalog.columnSqlNamesOf(tableType.table().tableName())));
        }
        return new ColumnField(parentTypeName, name, location, columnName, column.get(), javaNamePresent);
    }

    private Optional<ColumnRef> resolveColumn(String columnName, TableBackedType tableType) {
        return resolveColumnInTable(columnName, tableType.table().tableName());
    }

    private Optional<ColumnRef> resolveColumnForReference(String columnName, List<JoinStep> path, TableBackedType sourceType) {
        String terminal = terminalTableSqlNameForReference(path, sourceType);
        if (terminal == null) return Optional.empty();
        return resolveColumnInTable(columnName, terminal);
    }

    /**
     * Walks the FK join path to compute the terminal table SQL name. Returns {@code null} when any
     * path step is not a {@link FkJoin} (i.e. the path contains a condition-only step, which
     * does not imply a specific table).
     */
    private String terminalTableSqlNameForReference(List<JoinStep> path, TableBackedType sourceType) {
        String current = sourceType.table().tableName();
        for (var step : path) {
            if (!(step instanceof FkJoin fk)) return null;
            current = fk.targetTableSqlName();
        }
        return current;
    }

    private Optional<ColumnRef> resolveColumnInTable(String columnName, String tableSqlName) {
        return catalog.findColumn(tableSqlName, columnName)
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()));
    }

    private ReturnTypeRef resolveReturnType(String targetTypeName, FieldWrapper wrapper) {
        GraphitronType target = types.get(targetTypeName);
        if (target instanceof TableBackedType tbt)
            return new ReturnTypeRef.TableBoundReturnType(targetTypeName, tbt.table(), wrapper);
        if (target instanceof InterfaceType || target instanceof UnionType)
            return new ReturnTypeRef.PolymorphicReturnType(targetTypeName, wrapper);
        if (target instanceof ResultType)
            return new ReturnTypeRef.ResultReturnType(targetTypeName, wrapper);
        // ScalarReturnType covers scalars, enums, and directive-argument type names that
        // don't match any schema type (@nodeId(typeName:)).
        // Downstream validators report errors when required type metadata is absent.
        return new ReturnTypeRef.ScalarReturnType(targetTypeName, wrapper);
    }

    private boolean isScalarOrEnum(GraphQLFieldDefinition fieldDef) {
        var baseType = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
        return baseType instanceof GraphQLScalarType || baseType instanceof GraphQLEnumType;
    }

    private String baseTypeName(GraphQLFieldDefinition fieldDef) {
        return ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
    }

    /**
     * Parses the {@code ExternalCodeReference} input object at argument {@code argName} of the
     * given directive on {@code fieldDef} and returns a builder-private {@code ExternalRef} holding
     * the {@code className} and {@code method} strings. Returns {@code null} when the directive or
     * argument is absent.
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
    /**
     * Parses the {@code @reference(path:)} directive on {@code fieldDef} into a {@link ParsedPath}.
     * Delegates to {@link #parsePath(GraphQLFieldDefinition, String)} with no known source table.
     *
     * <p>Returns {@code ParsedPath(List.of(), null)} when no {@code @reference} directive is present.
     * Returns a {@code ParsedPath} with a non-null {@code errorMessage()} when any path element
     * cannot be resolved.
     */
    private ParsedPath parsePath(GraphQLFieldDefinition fieldDef) {
        return parsePath(fieldDef, null);
    }

    /**
     * Parses the {@code @reference(path:)} directive on {@code fieldDef} into a {@link ParsedPath},
     * threading {@code startSqlTableName} through the chain to validate connectivity and determine
     * the correct traversal direction for each FK step.
     *
     * <p>When {@code startSqlTableName} is {@code null} (no table-backed source context), direction
     * is inferred as forward (FK-side → key-side) and connectivity is not validated.
     */
    private ParsedPath parsePath(GraphQLFieldDefinition fieldDef, String startSqlTableName) {
        var directive = fieldDef.getAppliedDirective(DIR_REFERENCE);
        if (directive == null) return new ParsedPath(List.of(), null);

        var pathArg = directive.getArgument(ARG_PATH);
        if (pathArg == null) return new ParsedPath(List.of(), null);

        Object pathValue = pathArg.getValue();
        List<?> elements = pathValue instanceof List<?> l ? l : List.of(pathValue);

        var resolvedElements = new ArrayList<JoinStep>();
        var errors = new ArrayList<String>();
        String currentSource = startSqlTableName;

        for (var v : elements) {
            if (v instanceof Map<?, ?>) {
                parsePathElement(asMap(v), currentSource, resolvedElements, errors);
                // Propagate the target of the last resolved step as the source for the next step.
                // Condition joins don't carry table information, so source tracking is suspended.
                if (!resolvedElements.isEmpty()) {
                    var last = resolvedElements.getLast();
                    currentSource = last instanceof FkJoin fk ? fk.targetTableSqlName() : null;
                }
            }
        }

        if (!errors.isEmpty()) {
            return new ParsedPath(List.of(), String.join("; ", errors));
        }
        return new ParsedPath(List.copyOf(resolvedElements), null);
    }

    /**
     * Resolves one {@code @reference} path element into a {@link JoinStep} and appends it to
     * {@code out}. Errors are accumulated in {@code errors}.
     *
     * <p>{@code currentSourceSqlName} is the SQL table name at the current position in the chain,
     * or {@code null} when the source is not a table-backed type. When non-null, FK connectivity is
     * validated (the FK must touch the current source table) and traversal direction is determined
     * precisely. When null, forward traversal (FK-side → key-side) is assumed without validation.
     */
    private void parsePathElement(Map<String, Object> element, String currentSourceSqlName, List<JoinStep> out, List<String> errors) {
        Object keyRaw = element.get(ARG_KEY);
        Object conditionRaw = element.get(ARG_CONDITION);

        Optional<String> keyName = Optional.ofNullable(keyRaw)
            .map(Object::toString)
            .filter(s -> !s.isBlank());
        boolean hasCondition = conditionRaw instanceof Map;

        if (keyName.isPresent()) {
            Optional<ForeignKey<?, ?>> fk = catalog.findForeignKey(keyName.get());
            if (fk.isEmpty()) {
                errors.add("key '" + keyName.get() + "' could not be resolved in the jOOQ catalog"
                    + candidateHint(keyName.get(), catalog.allForeignKeySqlNames()));
                return;
            }
            var f = fk.get();
            String fkSideTable  = f.getTable().getName();         // table carrying the FK column
            String keySideTable = f.getKey().getTable().getName(); // table with the PK
            String targetSqlName;
            if (currentSourceSqlName == null) {
                // No table context: assume forward direction (FK-side → key-side), no validation.
                targetSqlName = keySideTable;
            } else if (currentSourceSqlName.equalsIgnoreCase(fkSideTable)) {
                targetSqlName = keySideTable;  // forward: FK-side → key-side
            } else if (currentSourceSqlName.equalsIgnoreCase(keySideTable)) {
                targetSqlName = fkSideTable;   // reverse: key-side → FK-side
            } else {
                errors.add("key '" + f.getName() + "' does not connect to table '" + currentSourceSqlName + "'"
                    + candidateHint(currentSourceSqlName, List.of(fkSideTable, keySideTable)));
                return;
            }
            // key + condition on the same element: FK join + WHERE filter.
            MethodRef whereFilter = hasCondition ? resolveConditionRef(asMap(conditionRaw)) : null;
            out.add(new FkJoin(f.getName(), targetSqlName, whereFilter));
            return;
        }
        if (hasCondition) {
            Map<String, Object> condMap = asMap(conditionRaw);
            MethodRef resolved = resolveConditionRef(condMap);
            if (resolved != null) {
                out.add(new ConditionJoin(resolved));
            } else {
                errors.add("condition method '" + extractConditionQualifiedName(condMap) + "' could not be resolved");
            }
            return;
        }
        // A path element with neither 'key' nor 'condition' is structurally invalid.
        errors.add("path element has neither 'key' nor 'condition'");
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
     * otherwise. Parameters whose name matches a GraphQL argument get {@link ParamSource.Arg},
     * parameters whose name matches a context key get {@link ParamSource.Context}, and all others
     * get {@link ParamSource.Sources} with the element type classified by {@link #classifySourcesType}.
     */
    private ServiceReflectionResult reflectServiceMethod(String className, String methodName, Set<String> argNames, Set<String> ctxKeys) {
        if (className == null || methodName == null) {
            return new ServiceReflectionResult(null, "service reference is incomplete");
        }
        try {
            Class<?> cls = Class.forName(className);
            var methods = java.util.Arrays.stream(cls.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
            if (methods.isEmpty()) {
                var declaredMethodNames = java.util.Arrays.stream(cls.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .distinct()
                    .toList();
                return new ServiceReflectionResult(null,
                    "method '" + methodName + "' not found in class '" + className + "'"
                    + candidateHint(methodName, declaredMethodNames));
            }
            var javaMethod = methods.get(0);
            var params = new ArrayList<MethodRef.Param>();
            for (var p : javaMethod.getParameters()) {
                String pName = p.isNamePresent() ? p.getName() : null;
                String displayName = pName != null ? pName : p.getType().getSimpleName();
                String typeName = p.getParameterizedType().getTypeName();
                if (pName != null && argNames.contains(pName)) {
                    params.add(new MethodRef.Param(displayName, typeName, new ParamSource.Arg()));
                } else if (pName != null && ctxKeys.contains(pName)) {
                    params.add(new MethodRef.Param(displayName, typeName, new ParamSource.Context()));
                } else {
                    Optional<SourcesRef> sourcesRef = classifySourcesType(p.getParameterizedType());
                    if (sourcesRef.isEmpty()) {
                        return new ServiceReflectionResult(null,
                            "parameter '" + displayName + "' in method '" + methodName
                            + "' has an unrecognized sources type: '" + typeName + "'");
                    }
                    params.add(new MethodRef.Param(displayName, typeName, new ParamSource.Sources(sourcesRef.get())));
                }
            }
            return new ServiceReflectionResult(
                new MethodRef(className, methodName, javaMethod.getReturnType().getName(), List.copyOf(params)),
                null);
        } catch (ClassNotFoundException e) {
            return new ServiceReflectionResult(null, "class '" + className + "' could not be loaded");
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
     * {@link MethodRef} or a failure reason string.
     */
    private record ServiceReflectionResult(MethodRef ref, String failureReason) {
        boolean failed() { return failureReason != null; }
    }

    /**
     * Builder-private holder for the raw {@code className} and {@code methodName} parsed from an
     * {@code ExternalCodeReference} input object before reflection is performed.
     */
    private record ExternalRef(String className, String methodName) {}

    /**
     * Carries the result of {@link #parsePath}: either a fully resolved list of path elements or
     * an error message. When {@code errorMessage()} is non-null the {@code elements()} list is
     * empty and the containing field must be classified as
     * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}.
     */
    private record ParsedPath(List<JoinStep> elements, String errorMessage) {
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

    private static SourceLocation locationOf(GraphQLInputObjectField field) {
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

    /**
     * Levenshtein distance between two strings: the minimum number of single-character insertions,
     * deletions, or substitutions needed to transform {@code a} into {@code b}. Uses two-row DP,
     * O(m*n) time and O(n) space. Used to sort candidate name lists in error messages by similarity
     * to the attempted name.
     */
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

    /**
     * Builds a {@code "; available: X, Y, Z"} hint string for error messages, listing
     * {@code candidates} sorted by case-insensitive Levenshtein distance from {@code attempt}.
     * Returns an empty string when {@code candidates} is empty.
     */
    private String candidateHint(String attempt, List<String> candidates) {
        if (candidates.isEmpty()) return "";
        String lc = attempt.toLowerCase();
        return "; available: " + candidates.stream()
            .sorted(Comparator.comparingInt(c -> levenshteinDistance(lc, c.toLowerCase())))
            .collect(Collectors.joining(", "));
    }
}
