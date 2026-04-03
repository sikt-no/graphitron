package no.sikt.graphitron.record;

import graphql.language.ArrayValue;
import graphql.language.DirectiveDefinition;
import graphql.language.DirectivesContainer;
import graphql.language.EnumTypeDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectValue;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.schema.FieldCoordinates;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.record.field.ChildField.ColumnField;
import no.sikt.graphitron.record.field.ChildField.ColumnReferenceField;
import no.sikt.graphitron.record.field.ChildField.MultitableReferenceField;
import no.sikt.graphitron.record.field.ChildField.NodeIdField;
import no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField;
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
import no.sikt.graphitron.record.field.ReferencePathElementRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkWithConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyAndConditionRef;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyRef;
import no.sikt.graphitron.record.type.GraphitronType;
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
    private static final Set<String> BUILTIN_SCALARS = Set.of("String", "Int", "Float", "Boolean", "ID");

    // Directive names — these are the ground truth for what this builder reads from the schema.
    // They are validated against the TypeDefinitionRegistry at build time (see validateDirectiveSchema).
    private static final String DIR_TABLE = "table";
    private static final String DIR_RECORD = "record";
    private static final String DIR_DISCRIMINATE = "discriminate";
    private static final String DIR_NODE = "node";
    private static final String DIR_NOT_GENERATED = "notGenerated";
    private static final String DIR_MULTITABLE_REFERENCE = "multitableReference";
    private static final String DIR_NODE_ID = "nodeId";
    private static final String DIR_FIELD = "field";
    private static final String DIR_REFERENCE = "reference";

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

    private final TypeDefinitionRegistry registry;
    private final JooqCatalog catalog;
    private Map<String, GraphitronType> types;

    private GraphitronSchemaBuilder(TypeDefinitionRegistry registry, JooqCatalog catalog) {
        this.registry = registry;
        this.catalog = catalog;
    }

    /**
     * Classifies all types and fields in {@code registry} and returns the resulting
     * {@link GraphitronSchema}. The registry must already include the Graphitron directive
     * definitions.
     */
    public static GraphitronSchema build(TypeDefinitionRegistry registry) {
        return new GraphitronSchemaBuilder(registry, new JooqCatalog(GeneratorConfig.getGeneratedJooqPackage())).buildSchema();
    }

    private GraphitronSchema buildSchema() {
        validateDirectiveSchema();
        types = buildTypes();
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();

        registry.types().values().stream()
            .filter(t -> t instanceof ObjectTypeDefinition)
            .map(t -> (ObjectTypeDefinition) t)
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
        var result = new LinkedHashMap<String, GraphitronType>();
        registry.types().values().forEach(typeDef -> {
            var gType = classifyType(typeDef);
            if (gType != null) {
                result.put(typeDef.getName(), gType);
            }
        });
        return result;
    }

    private GraphitronType classifyType(TypeDefinition<?> typeDef) {
        if (typeDef instanceof ScalarTypeDefinition
                || typeDef instanceof EnumTypeDefinition
                || typeDef instanceof InputObjectTypeDefinition) {
            return null;
        }

        String name = typeDef.getName();
        SourceLocation location = typeDef.getSourceLocation();

        if (typeDef instanceof ObjectTypeDefinition objType) {
            if (ROOT_TYPE_NAMES.contains(name)) {
                return new RootType(name, location);
            }
            if (objType.hasDirective(DIR_TABLE)) {
                return buildTableType(objType);
            }
            if (objType.hasDirective(DIR_RECORD)) {
                return new ResultType(name, location);
            }
            return null;
        }
        if (typeDef instanceof InterfaceTypeDefinition iface) {
            if (iface.hasDirective(DIR_TABLE) && iface.hasDirective(DIR_DISCRIMINATE)) {
                return buildTableInterfaceType(iface);
            }
            return new InterfaceType(name, location);
        }
        if (typeDef instanceof UnionTypeDefinition) {
            return new UnionType(name, location);
        }
        return null;
    }

    private TableType buildTableType(ObjectTypeDefinition objType) {
        String name = objType.getName();
        SourceLocation location = objType.getSourceLocation();
        String tableName = argString(objType, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        TableRef tableRef = resolveTable(tableName);
        NodeRef nodeRef = buildNodeRef(objType, tableRef);
        return new TableType(name, location, tableName, tableRef, nodeRef);
    }

    private TableInterfaceType buildTableInterfaceType(InterfaceTypeDefinition iface) {
        String name = iface.getName();
        SourceLocation location = iface.getSourceLocation();
        String tableName = argString(iface, DIR_TABLE, ARG_NAME).orElse(name.toLowerCase());
        String discriminatorColumn = argString(iface, DIR_DISCRIMINATE, ARG_ON).orElse(null);
        TableRef tableRef = resolveTable(tableName);
        return new TableInterfaceType(name, location, discriminatorColumn, tableName, tableRef);
    }

    private TableRef resolveTable(String sqlName) {
        return catalog.findTable(sqlName)
            .<TableRef>map(e -> new ResolvedTable(e.javaFieldName(), e.table()))
            .orElseGet(UnresolvedTable::new);
    }

    private NodeRef buildNodeRef(ObjectTypeDefinition objType, TableRef tableRef) {
        if (!objType.hasDirective(DIR_NODE)) {
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

    private KeyColumnRef resolveKeyColumn(String colName, Table<?> table) {
        if (table == null) {
            return new UnresolvedKeyColumn(colName);
        }
        return catalog.findColumn(table, colName)
            .<KeyColumnRef>map(e -> new ResolvedKeyColumn(colName, e.javaName()))
            .orElseGet(() -> new UnresolvedKeyColumn(colName));
    }

    // ===== Field classification =====

    private GraphitronField classifyField(FieldDefinition fieldDef, String parentTypeName, GraphitronType parentType) {
        String name = fieldDef.getName();
        SourceLocation location = fieldDef.getSourceLocation();

        if (fieldDef.hasDirective(DIR_NOT_GENERATED)) {
            return new NotGeneratedField(parentTypeName, name, location);
        }
        if (fieldDef.hasDirective(DIR_MULTITABLE_REFERENCE)) {
            return new MultitableReferenceField(parentTypeName, name, location);
        }

        if (parentType instanceof TableType tableType) {
            return classifyChildFieldOnTableType(fieldDef, parentTypeName, tableType);
        }

        return new UnclassifiedField(parentTypeName, name, location);
    }

    private GraphitronField classifyChildFieldOnTableType(FieldDefinition fieldDef, String parentTypeName, TableType tableType) {
        String name = fieldDef.getName();
        SourceLocation location = fieldDef.getSourceLocation();

        if (!isScalarOrEnum(fieldDef.getType())) {
            return new UnclassifiedField(parentTypeName, name, location);
        }

        if (fieldDef.hasDirective(DIR_NODE_ID)) {
            Optional<String> typeName = argString(fieldDef, DIR_NODE_ID, ARG_TYPE_NAME);
            if (typeName.isPresent()) {
                NodeTypeRef nodeType = resolveNodeType(typeName.get());
                List<ReferencePathElementRef> path = parseReferencePath(fieldDef);
                return new NodeIdReferenceField(parentTypeName, name, location, typeName.get(), nodeType, path);
            } else {
                return new NodeIdField(parentTypeName, name, location, tableType.node());
            }
        }

        boolean hasFieldDirective = fieldDef.hasDirective(DIR_FIELD);
        String columnName = hasFieldDirective
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        boolean javaNamePresent = hasFieldDirective
            && argString(fieldDef, DIR_FIELD, ARG_JAVA_NAME).isPresent();

        if (fieldDef.hasDirective(DIR_REFERENCE)) {
            List<ReferencePathElementRef> path = parseReferencePath(fieldDef);
            ColumnRef column = resolveColumnForReference(columnName, path, tableType);
            return new ColumnReferenceField(parentTypeName, name, location, columnName, column, path, javaNamePresent);
        }

        ColumnRef column = resolveColumn(columnName, tableType);
        return new ColumnField(parentTypeName, name, location, columnName, column, javaNamePresent);
    }

    private NodeTypeRef resolveNodeType(String targetTypeName) {
        GraphitronType target = types.get(targetTypeName);
        if (target instanceof TableType tt && tt.node() instanceof NodeDirective) {
            return new ResolvedNodeType();
        }
        return new UnresolvedNodeType();
    }

    private boolean isScalarOrEnum(Type<?> type) {
        String typeName = getBaseTypeName(type);
        return BUILTIN_SCALARS.contains(typeName)
            || registry.scalars().containsKey(typeName)
            || registry.types().get(typeName) instanceof EnumTypeDefinition;
    }

    private String getBaseTypeName(Type<?> type) {
        return switch (type) {
            case TypeName t -> t.getName();
            case NonNullType t -> getBaseTypeName(t.getType());
            case ListType t -> getBaseTypeName(t.getType());
            default -> "";
        };
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

    private List<ReferencePathElementRef> parseReferencePath(FieldDefinition fieldDef) {
        var directive = fieldDef.getDirectives(DIR_REFERENCE).stream().findFirst().orElse(null);
        if (directive == null) return List.of();

        var pathArg = directive.getArgument(ARG_PATH);
        if (pathArg == null) return List.of();

        var pathValue = pathArg.getValue();
        var elements = pathValue instanceof ArrayValue av ? av.getValues() : List.of(pathValue);

        return elements.stream()
            .filter(v -> v instanceof ObjectValue)
            .map(v -> parsePathElement((ObjectValue) v))
            .toList();
    }

    private ReferencePathElementRef parsePathElement(ObjectValue element) {
        Optional<ObjectField> keyField = objectFieldByName(element.getObjectFields(), ARG_KEY);
        Optional<ObjectField> conditionField = objectFieldByName(element.getObjectFields(), ARG_CONDITION);

        Optional<String> keyName = keyField.map(f -> ((StringValue) f.getValue()).getValue());
        boolean hasCondition = conditionField.isPresent();

        if (keyName.isPresent() && !hasCondition) {
            return resolveKey(keyName.get());
        }
        if (keyName.isPresent()) {
            Optional<ForeignKey<?, ?>> fk = catalog.findForeignKey(keyName.get());
            String condName = extractConditionQualifiedName(conditionField.get());
            MethodRef resolved = resolveConditionRef(conditionField.get());
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
            MethodRef resolved = resolveConditionRef(conditionField.get());
            if (resolved != null) {
                return new ConditionOnlyRef(resolved);
            }
            return new UnresolvedConditionRef(extractConditionQualifiedName(conditionField.get()));
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
    private MethodRef resolveConditionRef(ObjectField conditionField) {
        return null;
    }

    private String extractConditionQualifiedName(ObjectField conditionField) {
        if (conditionField.getValue() instanceof ObjectValue ov) {
            return objectFieldByName(ov.getObjectFields(), ARG_NAME)
                .map(f -> ((StringValue) f.getValue()).getValue())
                .orElse("unknown");
        }
        return "unknown";
    }

    // ===== Directive reading helpers =====

    /**
     * Returns the stripped String value of a directive argument, if present.
     * This is the internal replacement for the enum-based {@code DirectiveHelpers} methods.
     */
    private Optional<String> argString(DirectivesContainer<?> container, String directive, String arg) {
        var dirs = container.getDirectives(directive);
        if (dirs == null || dirs.isEmpty()) return Optional.empty();
        var argument = dirs.get(0).getArgument(arg);
        if (argument == null) return Optional.empty();
        return argument.getValue() instanceof StringValue sv
            ? Optional.of(sv.getValue().strip())
            : Optional.empty();
    }

    /**
     * Returns the String values of a list directive argument, or an empty list if absent.
     */
    private List<String> argStringList(DirectivesContainer<?> container, String directive, String arg) {
        var dirs = container.getDirectives(directive);
        if (dirs == null || dirs.isEmpty()) return List.of();
        var argument = dirs.get(0).getArgument(arg);
        if (argument == null) return List.of();
        var value = argument.getValue();
        if (value instanceof StringValue sv) return List.of(sv.getValue().strip());
        if (value instanceof ArrayValue av) {
            return av.getValues().stream()
                .map(v -> v instanceof NullValue ? null : ((StringValue) v).getValue().strip())
                .collect(Collectors.toList());
        }
        return List.of();
    }

    /**
     * Finds a named field in a list of object fields.
     */
    private Optional<ObjectField> objectFieldByName(List<ObjectField> fields, String name) {
        return fields.stream().filter(f -> f.getName().equals(name)).findFirst();
    }

    // ===== Registry validation =====

    /**
     * Validates that every directive name and argument name used by this builder actually exists
     * in the loaded {@link TypeDefinitionRegistry}. Throws {@link IllegalStateException} if the
     * registry is out of sync with the constants declared in this class.
     */
    private void validateDirectiveSchema() {
        var defs = registry.getDirectiveDefinitions();
        assertDirective(defs, DIR_TABLE, ARG_NAME);
        assertDirective(defs, DIR_RECORD);
        assertDirective(defs, DIR_DISCRIMINATE, ARG_ON);
        assertDirective(defs, DIR_NODE, ARG_TYPE_ID, ARG_KEY_COLUMNS);
        assertDirective(defs, DIR_NOT_GENERATED);
        assertDirective(defs, DIR_MULTITABLE_REFERENCE);
        assertDirective(defs, DIR_NODE_ID, ARG_TYPE_NAME);
        assertDirective(defs, DIR_FIELD, ARG_NAME, ARG_JAVA_NAME);
        assertDirective(defs, DIR_REFERENCE, ARG_PATH);
    }

    private void assertDirective(Map<String, DirectiveDefinition> defs, String name, String... args) {
        var def = defs.get(name);
        if (def == null) {
            throw new IllegalStateException("Expected directive @" + name + " in schema but it was not found.");
        }
        var argNames = def.getInputValueDefinitions().stream()
            .map(InputValueDefinition::getName)
            .collect(Collectors.toSet());
        for (var arg : args) {
            if (!argNames.contains(arg)) {
                throw new IllegalStateException(
                    "Expected argument '" + arg + "' on directive @" + name + " but it was not found.");
            }
        }
    }
}
