package no.sikt.graphitron.record;

import graphql.language.ArrayValue;
import graphql.language.EnumTypeDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectField;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectValue;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SourceLocation;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.schema.FieldCoordinates;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.record.field.ChildField.ColumnField;
import no.sikt.graphitron.record.field.ChildField.ColumnReferenceField;
import no.sikt.graphitron.record.field.ColumnStep;
import no.sikt.graphitron.record.field.ReferencePathElement.ConditionOnlyStep;
import no.sikt.graphitron.record.field.ReferencePathElement.FkStep;
import no.sikt.graphitron.record.field.ReferencePathElement.FkWithConditionStep;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.MethodRef;
import no.sikt.graphitron.record.field.ChildField.MultitableReferenceField;
import no.sikt.graphitron.record.field.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.record.field.ReferencePathElement;
import no.sikt.graphitron.record.field.ColumnStep.ResolvedColumn;
import no.sikt.graphitron.record.field.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.record.field.ColumnStep.UnresolvedColumn;
import no.sikt.graphitron.record.field.ReferencePathElement.UnresolvedConditionStep;
import no.sikt.graphitron.record.field.ReferencePathElement.UnresolvedKeyAndConditionStep;
import no.sikt.graphitron.record.field.ReferencePathElement.UnresolvedKeyStep;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.GraphitronType.InterfaceType;
import no.sikt.graphitron.record.type.KeyColumnStep;
import no.sikt.graphitron.record.type.NodeStep.NoNode;
import no.sikt.graphitron.record.type.NodeStep.NodeDirective;
import no.sikt.graphitron.record.type.NodeStep;
import no.sikt.graphitron.record.type.KeyColumnStep.ResolvedKeyColumn;
import no.sikt.graphitron.record.type.TableStep.ResolvedTable;
import no.sikt.graphitron.record.type.GraphitronType.ResultType;
import no.sikt.graphitron.record.type.GraphitronType.RootType;
import no.sikt.graphitron.record.type.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.record.type.TableStep;
import no.sikt.graphitron.record.type.GraphitronType.TableType;
import no.sikt.graphitron.record.type.GraphitronType.UnionType;
import no.sikt.graphitron.record.type.KeyColumnStep.UnresolvedKeyColumn;
import no.sikt.graphitron.record.type.TableStep.UnresolvedTable;
import org.jooq.ForeignKey;
import org.jooq.Table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentStringList;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalObjectFieldByName;
import static no.sikt.graphql.directives.DirectiveHelpers.stringValueOf;
import static no.sikt.graphql.directives.GenerationDirective.DISCRIMINATE;
import static no.sikt.graphql.directives.GenerationDirective.FIELD;
import static no.sikt.graphql.directives.GenerationDirective.MULTITABLE_REFERENCE;
import static no.sikt.graphql.directives.GenerationDirective.NODE;
import static no.sikt.graphql.directives.GenerationDirective.NODE_ID;
import static no.sikt.graphql.directives.GenerationDirective.NOT_GENERATED;
import static no.sikt.graphql.directives.GenerationDirective.RECORD;
import static no.sikt.graphql.directives.GenerationDirective.REFERENCE;
import static no.sikt.graphql.directives.GenerationDirective.TABLE;
import static no.sikt.graphql.directives.GenerationDirectiveParam.CONDITION;
import static no.sikt.graphql.directives.GenerationDirectiveParam.DIRECTION;
import static no.sikt.graphql.directives.GenerationDirectiveParam.JAVA_NAME;
import static no.sikt.graphql.directives.GenerationDirectiveParam.KEY;
import static no.sikt.graphql.directives.GenerationDirectiveParam.KEY_COLUMNS;
import static no.sikt.graphql.directives.GenerationDirectiveParam.NAME;
import static no.sikt.graphql.directives.GenerationDirectiveParam.ON;
import static no.sikt.graphql.directives.GenerationDirectiveParam.PATH;
import static no.sikt.graphql.directives.GenerationDirectiveParam.TYPE_ID;

/**
 * Builds a {@link GraphitronSchema} from a {@link TypeDefinitionRegistry} by classifying every
 * named type and every field into the sealed {@link GraphitronType} and {@link GraphitronField}
 * hierarchies.
 *
 * <p>The Maven plugin calls {@link #build(TypeDefinitionRegistry)} before running
 * {@link GraphitronSchemaValidator#validate(GraphitronSchema)}.
 *
 * <h2>Parsing stream</h2>
 * <p>This class is the <em>parsing stream</em>: it reads schema definitions and produces
 * {@code GraphitronField} instances. It contains no JavaPoet code and no code-generation logic.
 * Tests assert which concrete type is produced for representative schema fragments.
 *
 * <h2>Incremental classification</h2>
 * <p>Fields that are not yet handled by any classification rule are classified as
 * {@link UnclassifiedField}. The {@link GraphitronSchemaValidator} reports an error for every
 * {@code UnclassifiedField}, so the schema cannot be used for code generation until all fields
 * are handled.
 */
public class FieldsSpecBuilder {

    private static final Set<String> BUILTIN_SCALARS = Set.of("String", "Int", "Float", "Boolean", "ID");
    private static final Set<String> ROOT_TYPE_NAMES = Set.of("Query", "Mutation", "Subscription");

    private final TypeDefinitionRegistry registry;
    private final JooqCatalog catalog;

    private FieldsSpecBuilder(TypeDefinitionRegistry registry, JooqCatalog catalog) {
        this.registry = registry;
        this.catalog = catalog;
    }

    /**
     * Classifies all types and fields in {@code registry} and returns the resulting
     * {@link GraphitronSchema}. The registry must already include the Graphitron directive
     * definitions.
     */
    public static GraphitronSchema build(TypeDefinitionRegistry registry) {
        return new FieldsSpecBuilder(registry, new JooqCatalog(GeneratorConfig.getGeneratedJooqPackage())).buildSchema();
    }

    private GraphitronSchema buildSchema() {
        var types = new LinkedHashMap<String, GraphitronType>();
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();

        registry.types().values().forEach(typeDef -> {
            var gType = classifyType(typeDef);
            if (gType != null) {
                types.put(typeDef.getName(), gType);
            }
        });

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
            if (objType.hasDirective(TABLE.getName())) {
                return buildTableType(objType);
            }
            if (objType.hasDirective(RECORD.getName())) {
                return new ResultType(name, location);
            }
            return null;
        }
        if (typeDef instanceof InterfaceTypeDefinition iface) {
            if (iface.hasDirective(TABLE.getName()) && iface.hasDirective(DISCRIMINATE.getName())) {
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
        String tableName = getOptionalDirectiveArgumentString(objType, TABLE, NAME).orElse(name.toLowerCase());
        TableStep tableStep = resolveTable(tableName);
        NodeStep nodeStep = buildNodeStep(objType, tableStep);
        return new TableType(name, location, tableName, tableStep, nodeStep);
    }

    private TableInterfaceType buildTableInterfaceType(InterfaceTypeDefinition iface) {
        String name = iface.getName();
        SourceLocation location = iface.getSourceLocation();
        String tableName = getOptionalDirectiveArgumentString(iface, TABLE, NAME).orElse(name.toLowerCase());
        String discriminatorColumn = getOptionalDirectiveArgumentString(iface, DISCRIMINATE, ON).orElse(null);
        TableStep tableStep = resolveTable(tableName);
        return new TableInterfaceType(name, location, discriminatorColumn, tableName, tableStep);
    }

    private TableStep resolveTable(String sqlName) {
        return catalog.findTable(sqlName)
            .<TableStep>map(e -> new ResolvedTable(e.javaFieldName(), e.table()))
            .orElseGet(UnresolvedTable::new);
    }

    private NodeStep buildNodeStep(ObjectTypeDefinition objType, TableStep tableStep) {
        if (!objType.hasDirective(NODE.getName())) {
            return new NoNode();
        }
        String typeId = getOptionalDirectiveArgumentString(objType, NODE, TYPE_ID).orElse(null);
        List<String> keyColumnNames = getOptionalDirectiveArgumentStringList(objType, NODE, KEY_COLUMNS);
        Table<?> resolvedTable = tableStep instanceof ResolvedTable rt ? rt.table() : null;
        List<KeyColumnStep> keyColumns = keyColumnNames.stream()
            .map(colName -> resolveKeyColumn(colName, resolvedTable))
            .toList();
        return new NodeDirective(typeId, keyColumns);
    }

    private KeyColumnStep resolveKeyColumn(String colName, Table<?> table) {
        if (table == null) {
            return new UnresolvedKeyColumn(colName);
        }
        return catalog.findColumn(table, colName)
            .<KeyColumnStep>map(e -> new ResolvedKeyColumn(colName, e.javaName()))
            .orElseGet(() -> new UnresolvedKeyColumn(colName));
    }

    // ===== Field classification =====

    private GraphitronField classifyField(FieldDefinition fieldDef, String parentTypeName, GraphitronType parentType) {
        String name = fieldDef.getName();
        SourceLocation location = fieldDef.getSourceLocation();

        if (fieldDef.hasDirective(NOT_GENERATED.getName())) {
            return new NotGeneratedField(parentTypeName, name, location);
        }
        if (fieldDef.hasDirective(MULTITABLE_REFERENCE.getName())) {
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

        boolean hasFieldDirective = fieldDef.hasDirective(FIELD.getName());
        String columnName = hasFieldDirective
            ? getOptionalDirectiveArgumentString(fieldDef, FIELD, NAME).orElse(name)
            : name;
        boolean javaNamePresent = hasFieldDirective
            && getOptionalDirectiveArgumentString(fieldDef, FIELD, JAVA_NAME).isPresent();

        if (fieldDef.hasDirective(REFERENCE.getName())) {
            List<ReferencePathElement> path = parseReferencePath(fieldDef);
            ColumnStep column = resolveColumnForReference(columnName, path, tableType);
            return new ColumnReferenceField(parentTypeName, name, location, columnName, column, path, javaNamePresent);
        }

        ColumnStep column = resolveColumn(columnName, tableType);
        return new ColumnField(parentTypeName, name, location, columnName, column, javaNamePresent);
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

    private ColumnStep resolveColumn(String columnName, TableType tableType) {
        if (!(tableType.table() instanceof ResolvedTable resolvedTable)) {
            return new UnresolvedColumn();
        }
        return resolveColumnInTable(columnName, resolvedTable.table());
    }

    private ColumnStep resolveColumnForReference(String columnName, List<ReferencePathElement> path, TableType sourceType) {
        if (!(sourceType.table() instanceof ResolvedTable rt)) {
            return new UnresolvedColumn();
        }
        var current = rt.table();
        for (var step : path) {
            if (step instanceof FkStep fk) {
                current = fk.key().getKey().getTable();
            } else {
                return new UnresolvedColumn();
            }
        }
        return resolveColumnInTable(columnName, current);
    }

    private ColumnStep resolveColumnInTable(String columnName, Table<?> table) {
        return catalog.findColumn(table, columnName)
            .<ColumnStep>map(e -> new ResolvedColumn(e.javaName(), e.column()))
            .orElseGet(UnresolvedColumn::new);
    }

    // ===== Reference path parsing =====

    private List<ReferencePathElement> parseReferencePath(FieldDefinition fieldDef) {
        var directive = fieldDef.getDirectives(REFERENCE.getName()).stream().findFirst().orElse(null);
        if (directive == null) return List.of();

        var pathArg = directive.getArgument(PATH.getName());
        if (pathArg == null) return List.of();

        var pathValue = pathArg.getValue();
        var elements = pathValue instanceof ArrayValue av ? av.getValues() : List.of(pathValue);

        return elements.stream()
            .filter(v -> v instanceof ObjectValue)
            .map(v -> parsePathElement((ObjectValue) v))
            .toList();
    }

    private ReferencePathElement parsePathElement(ObjectValue element) {
        Optional<ObjectField> keyField = getOptionalObjectFieldByName(element.getObjectFields(), KEY);
        Optional<ObjectField> conditionField = getOptionalObjectFieldByName(element.getObjectFields(), CONDITION);

        Optional<String> keyName = keyField.map(f -> stringValueOf(f));
        boolean hasCondition = conditionField.isPresent();

        if (keyName.isPresent() && !hasCondition) {
            return resolveKey(keyName.get());
        }
        if (keyName.isPresent()) {
            Optional<ForeignKey<?, ?>> fk = catalog.findForeignKey(keyName.get());
            String condName = extractConditionQualifiedName(conditionField.get());
            MethodRef resolved = resolveConditionRef(conditionField.get());
            if (fk.isPresent() && resolved != null) {
                return new FkWithConditionStep(fk.get(), resolved);
            }
            if (fk.isPresent()) {
                return new UnresolvedConditionStep(condName);
            }
            if (resolved != null) {
                return new UnresolvedKeyStep(keyName.get());
            }
            return new UnresolvedKeyAndConditionStep(keyName.get(), condName);
        }
        if (hasCondition) {
            MethodRef resolved = resolveConditionRef(conditionField.get());
            if (resolved != null) {
                return new ConditionOnlyStep(resolved);
            }
            return new UnresolvedConditionStep(extractConditionQualifiedName(conditionField.get()));
        }
        return new UnresolvedKeyStep("");
    }

    private ReferencePathElement resolveKey(String keyName) {
        return catalog.findForeignKey(keyName)
            .<ReferencePathElement>map(FkStep::new)
            .orElseGet(() -> new UnresolvedKeyStep(keyName));
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
            return getOptionalObjectFieldByName(ov.getObjectFields(), NAME)
                .map(f -> stringValueOf(f))
                .orElse("unknown");
        }
        return "unknown";
    }
}
