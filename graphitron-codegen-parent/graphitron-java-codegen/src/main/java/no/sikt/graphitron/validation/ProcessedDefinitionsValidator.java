package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.*;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.helpers.ServiceWrapper;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.EnumDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.definitions.objects.RecordObjectDefinition;
import no.sikt.graphitron.definitions.sql.SQLCondition;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.naming.GraphQLReservedName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.jetbrains.annotations.NotNull;
import org.jooq.Field;
import org.jooq.Key;
import org.jooq.UniqueKey;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.context.NodeIdReferenceHelpers.getForeignKeyForNodeIdReference;
import static no.sikt.graphitron.mappings.JavaPoetClassName.STRING;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphitron.validation.ValidationHandler.*;
import static no.sikt.graphql.directives.GenerationDirective.*;
import static no.sikt.graphql.directives.GenerationDirective.NODE_ID;
import static no.sikt.graphql.naming.GraphQLReservedName.*;
import static org.apache.commons.lang3.StringUtils.*;

/**
 * Class for producing warnings related to potential issues in the defined schema.
 * This is only used before running generation, but generally does not prohibit further execution.
 * The intention is that the warnings should provide information on potential issues should an issue occur later.
 */
public class ProcessedDefinitionsValidator {
    protected final ProcessedSchema schema;
    private final List<ObjectField> allFields;
    protected static final String
            ERROR_MISSING_FIELD = "Input type %s referencing table %s does not map all fields required by the database. Missing required fields: %s",
            ERROR_MISSING_NON_NULLABLE = "Input type %s referencing table %s does not map all fields required by the database as non-nullable. Nullable required fields: %s";

    public ProcessedDefinitionsValidator(ProcessedSchema schema) {
        this.schema = schema;
        allFields = schema
                .getObjects()
                .values()
                .stream()
                .flatMap(it -> it.getFields().stream())
                .collect(Collectors.toList());
    }

    /**
     * Validate the directive usage in the schema.
     */
    public void validateDirectiveUsage() {
        schema.getObjects().values().forEach(it -> checkPaginationSpecs(it.getFields()));

        validateTablesAndKeys();
        validateRequiredMethodCalls();
        validateUnionFieldsTable();
        validateSingleTableInterfaceDefinitions();
        validateInterfacesReturnedInFields();
        validateMultitableFieldsOutsideRoot();
        validateTypesUsingNodeInterfaceWithoutNodeDirective();
        validateInputFields();
        validateExternalMappingReferences();
        validateServiceMethods();
        validateMutationDirectives();
        validateMutationRequiredFields();
        validateRecursiveRecordInputs();
        validateOnlyOneInputRecordInputWhenNoTypeTableIsPresent();
        validateSelfReferenceHasSplitQuery();
        validateNotUsingBothExternalFieldAndField();
        validateExternalField();
        validateNodeDirective();
        validateUnionAndInterfaceSubTypes();
        validateNodeId();
        validateNodeIdReferenceInJooqRecordInput();
        validateSplitQueryFieldsInJavaRecords();
        validateImplicitNodeTypeForInputFields();

        logWarnings();
        throwIfErrors();
    }

    /*
     * This is a temporary validation until GGG-104 has been fixed.
     */
    private void validateUnionAndInterfaceSubTypes() {
        schema.getObjects()
                .values().stream()
                .flatMap(o -> o.getFields().stream())
                .filter(schema::isMultiTableField)
                .filter(field -> !field.getTypeName().equals(NODE_TYPE.getName()))
                .filter(field -> !field.getTypeName().equals(FEDERATION_SERVICE_TYPE.getName()))
                .filter(field -> !field.getTypeName().equals(FEDERATION_ENTITY_UNION.getName()))
                .forEach(field -> {
                    var subTypes = schema.getTypesFromInterfaceOrUnion(field.getTypeName());
                    if (subTypes.size() < 2 && subTypes.stream().noneMatch(type -> type.implementsInterface(ERROR_TYPE.getName()))) {
                        addErrorMessage(
                                "Multitable queries is currently only supported for interface and unions with more than one implementing type. \n" +
                                        "The field %s's type %s has %d implementing type(s).", field.getName(), field.getTypeName(), subTypes.size());
                    }
                });
    }

    private void validateNodeDirective() {
        schema.getObjects().values().stream()
                .filter(ObjectDefinition::hasNodeDirective)
                .forEach(objectDefinition -> {
                    if (!objectDefinition.hasTable()) {
                        addErrorMessage("Type %s has the %s directive, but is missing the %s directive.",
                                objectDefinition.getName(), NODE.getName(), TABLE.getName());
                    } else {
                        var tableFields = getJavaFieldNamesForTable(objectDefinition.getTable().getName());
                        objectDefinition.getKeyColumns().stream()
                                .filter(col -> tableFields.stream().noneMatch(it -> it.equalsIgnoreCase(col)))
                                .forEach(col -> addErrorMessage(
                                        " Key column '%s' in node ID for type '%s' does not exist in table '%s'",
                                                col,
                                                objectDefinition.getName(),
                                                objectDefinition.getTable().getName())
                                );

                        if (getPrimaryOrUniqueKeyMatchingIdFields(objectDefinition).isEmpty()) {
                            addErrorMessage(
                                    "Key columns in node ID for type '%s' does not match a PK/UK for table '%s'",
                                            objectDefinition.getName(),
                                            objectDefinition.getTable().getName());
                        }
                    }
                    if (!objectDefinition.implementsInterface(NODE_TYPE.getName())) {
                        addErrorMessage("Type %s has the %s directive, but does not implement the %s interface.",
                                objectDefinition.getName(), NODE.getName(), NODE_TYPE.getName());
                    }
                });
    }

    private Optional<? extends UniqueKey<?>> getPrimaryOrUniqueKeyMatchingIdFields(ObjectDefinition target) {
        return !target.hasCustomKeyColumns() ?
                getPrimaryKeyForTable(target.getTable().getName())
                : getPrimaryOrUniqueKeyMatchingFields(target.getTable().getName(), target.getKeyColumns());
    }

    private void validateNodeId() {
        Stream<? extends GenerationSourceField<?>> inputStream =
                Stream.concat(
                        schema.getInputTypes().values().stream().flatMap(it -> it.getFields().stream()),
                        allFields.stream().flatMap(it -> it.getNonReservedArguments().stream())
                );
        Stream.concat(allFields.stream(), inputStream)
                .filter(GenerationSourceField::hasNodeID)
                .forEach(this::checkNodeId);
    }

    private void checkNodeId(GenerationField field) {
        var fieldName = field instanceof ArgumentField
                ? String.format("argument '%s' on a field in type '%s'", field.getName(), field.getContainerTypeName())
                : String.format("field %s", field.formatPath());

        if (!(field.isID() || field.getTypeName().equals(STRING.className.simpleName()))) {
            addErrorMessage(
                    "%s has %s directive, but is not an ID or String field.",
                    capitalize(fieldName),
                    NODE_ID.getName()
            );
        }

        var referencedType = schema.getObject(field.getNodeIdTypeName());

        if (referencedType == null) {
            addErrorMessage(
                    "Type with name '%s' referenced in the %s directive for %s does not exist.",
                    field.getNodeIdTypeName(),
                    NODE_ID.getName(),
                    fieldName
            );
        } else if (!referencedType.hasNodeDirective()) {
            addErrorMessage(
                    "Referenced type '%s' referenced in the %s directive for %s is missing the necessary %s directive.",
                    field.getNodeIdTypeName(),
                    NODE_ID.getName(),
                    fieldName,
                    NODE.getName()
            );
        } else if (field instanceof ObjectField && (!field.getNodeIdTypeName().equals(field.getContainerTypeName()) || field.hasFieldReferences())) {
            // Only filter object fields because we currently don't have reference validation on input (GGG-209)
            var recordType = Optional
                    .ofNullable(schema.getRecordType(field.getContainerTypeName()))
                    .flatMap(it -> Optional.ofNullable(it.getTable()));

            var referenceTable = referencedType.getTable();
            recordType.ifPresent(it -> validateReferencePath(field, it.getMappingName(), referenceTable.getMappingName()));
            if (recordType.isEmpty()) {
                var inputMapping = schema.findInputTables(field).stream().findFirst();
                if (inputMapping.isPresent() && !inputMapping.get().equals(referenceTable)) {
                    validateReferencePath(field, inputMapping.get().getMappingName(), referenceTable.getMappingName());
                }
            }
        }

        if (field.hasFieldDirective()) {
            addErrorMessage(
                    "%s has both the '%s' and '%s' directives, which is not supported.",
                    capitalize(fieldName),
                    NODE_ID.getName(),
                    FIELD.getName()
            );
        }
        if (field.isExternalField()) {
            addErrorMessage(
                    "%s has both the '%s' and '%s' directives, which is not supported.",
                    capitalize(fieldName),
                    NODE_ID.getName(),
                    EXTERNAL_FIELD.getName()
            );
        }
    }

    private void validateNodeIdReferenceInJooqRecordInput() {
        schema.getInputTypes()
                .values()
                .stream()
                .filter(it -> schema.isRecordType(it.getName()))
                .map(it -> schema.getRecordType(it.getName()))
                .filter(it -> it.getFields().stream().anyMatch(FieldSpecification::hasNodeID))
                .filter(it -> it.hasTable() && getTable(it.getTable().getName()).isPresent()) // No need to continue validation here if table does not exist. Table names is validated in validateTablesAndKeys
                .forEach(jooqRecordInput ->
                        jooqRecordInput.getFields()
                                .stream()
                                .filter(FieldSpecification::hasNodeID)
                                .filter(it -> schema.isObject(it.getNodeIdTypeName())) // This is validated in checkNodeId
                                .filter(it -> !schema.getRecordType(it.getNodeIdTypeName()).getTable().equals(jooqRecordInput.getTable()))
                                .forEach(field -> {
                                    var foreignKeyOptional = getForeignKeyForNodeIdReference(field, schema);

                                    if (foreignKeyOptional.isEmpty()) {
                                        addErrorMessage("Cannot find foreign key for node ID field '%s' in jOOQ record input '%s'.",
                                                field.getName(),
                                                field.getContainerTypeName());
                                        return;
                                    }

                                    var foreignKey = foreignKeyOptional.get();

                                    if (!foreignKey.getTable().getName().equalsIgnoreCase(jooqRecordInput.getTable().getName())) {
                                        addErrorMessage(
                                                "Node ID field '%s' in jOOQ record input '%s' references a table with an inverse key which is not supported.",
                                                field.getName(),
                                                field.getContainerTypeName()
                                        );
                                        return;
                                    }

                                    var nodeType = schema.getObject(field.getNodeIdTypeName());
                                    var firstForeignKeyReferencesTargetTable = foreignKey.getInverseKey().getTable().getName().equalsIgnoreCase(nodeType.getTable().getName());

                                    if (field.getFieldReferences().size() > 1 || (field.hasFieldReferences() && !firstForeignKeyReferencesTargetTable)) {
                                        addErrorMessage(
                                                "Node ID field '%s' in jOOQ record input '%s' has a reference via table(s) which is not supported on jOOQ record inputs.",
                                                field.getName(),
                                                field.getContainerTypeName()
                                        );
                                        return;
                                    }

                                    var targetKey = getPrimaryOrUniqueKeyMatchingIdFields(nodeType);

                                    if (targetKey.isPresent() && !targetKey.get().equals(foreignKey.getKey())) {
                                        addErrorMessage(
                                                "Node ID field '%s' in jOOQ record input '%s' uses foreign key '%s' which does not reference the same primary/unique key used for type '%s's node ID. This is not supported.",
                                                field.getName(),
                                                field.getContainerTypeName(),
                                                foreignKey.getName(),
                                                field.getNodeIdTypeName()
                                        );
                                    }

                                    if (isUsedInUpdateMutation(jooqRecordInput)) {
                                        getPrimaryKeyForTable(jooqRecordInput.getTable().getName())
                                                .map(Key::getFields)
                                                .filter(it -> it.stream().anyMatch(pkF -> foreignKey.getFields().stream().anyMatch(pkF::equals)))
                                                .stream().findFirst()
                                                .ifPresent((a) -> addErrorMessage(
                                                        "Foreign key used for node ID field '%s' in jOOQ record input '%s' overlaps with the primary key of the jOOQ record table. This is not supported for update/upsert mutations .",
                                                        field.getName(),
                                                        field.getContainerTypeName()
                                                ));
                                    }
                                })
                );
    }

    private boolean isUsedInUpdateMutation(RecordObjectSpecification<?> jooqRecordInput) {
        var mutation = schema.getMutationType();
        if (mutation == null) {
            return false;
        }

        var usages = mutation.getFields().stream()
                .filter(ObjectField::hasMutationType)
                .flatMap(objectField -> {
                    var inputs = new InputParser(objectField, schema).getJOOQRecords().values();
                    return inputs.stream()
                            .map(schema::getInputType)
                            .filter(it -> it != null && it.getName().equals(jooqRecordInput.getName()))
                            .map(it -> objectField.getMutationType());
                }).collect(Collectors.toSet());

        if (usages.isEmpty()) {
            return false;
        }
        return usages.stream().anyMatch(mt -> mt == MutationType.UPDATE || mt == MutationType.UPSERT);
    }

    private void validateTablesAndKeys() {
        var recordTypes = schema.getRecordTypes();
        recordTypes
                .values()
                .stream()
                .filter(RecordObjectSpecification::hasTable)
                .map(RecordObjectSpecification::getTable)
                .map(JOOQMapping::getMappingName)
                .filter(it -> !tableExists(it))
                .forEach(it -> addErrorMessage("No table with name \"%s\" found.", it));

        var allReferences = recordTypes
                .values()
                .stream()
                .flatMap(it -> it.getFields().stream())
                .flatMap(it -> it.isInput() ? Stream.of(it) : Stream.concat(Stream.of(it), ((ObjectField) it).getArguments().stream()))
                .flatMap(it -> it.getFieldReferences().stream())
                .toList();

        allReferences
                .stream()
                .filter(FieldReference::hasTable)
                .map(FieldReference::getTable)
                .map(JOOQMapping::getMappingName)
                .filter(it -> !tableExists(it))
                .forEach(it -> addErrorMessage("No table with name \"%s\" found.", it));

        allReferences
                .stream()
                .filter(FieldReference::hasKey)
                .map(FieldReference::getKey)
                .map(JOOQMapping::getMappingName)
                .filter(it -> !keyExists(it))
                .forEach(it -> addErrorMessage("No key with name \"%s\" found.", it));

        recordTypes
                .values()
                .stream()
                .filter(schema::hasTableObjectForObject)
                .filter(it -> !schema.isMultiTableInterface(it.getName())) // Don't validate fields on the interface definition since the interface itself has no table
                .flatMap(it -> it.getFields().stream())
                .flatMap(it -> schema.isMultiTableField(it)
                        ? schema.getTypesFromInterfaceOrUnion(it.getTypeName()).stream().map(o -> new VirtualSourceField(o.getName(), (ObjectField) it))
                        : Stream.of(it))
                .filter(field -> schema.isRecordType(field.getTypeName()) || field.hasFieldReferences() || field.isResolver())
                .forEach(this::validateReferencePath);
    }

    private void validateReferencePath(GenerationField field) {
        var targetTable = getTargetTableForField(field);

        if (targetTable == null) return; // Not a reference

        var sourceTable = schema.getPreviousTableObjectForObject(schema.getRecordTypes().get(field.getContainerTypeName())).getTable().getName();
        validateReferencePath(field, sourceTable, targetTable);
    }

    private void validateReferencePath(GenerationField field, String sourceTable, String targetTable) {
        if (sourceTable.equals(targetTable) && !field.isResolver() && !field.hasFieldReferences() && !field.hasNodeID()) {
            return;
        }
        for (FieldReference fieldReference : field.getFieldReferences()) {
            if (fieldReference.hasTableCondition() && !fieldReference.hasKey()) {
                return;
            } else if (fieldReference.hasKey()) {
                String nextTable;
                String keyName = fieldReference.getKey().getName();
                var keyTarget = getKeyTargetTable(keyName).orElse("");
                if (!keyTarget.equals(sourceTable)) {
                    nextTable = keyTarget;
                } else {
                    nextTable = getKeySourceTable(keyName).orElse("");
                }
                if (nextTable.equals(targetTable)) {
                    return;
                } else {
                    sourceTable = nextTable;
                }
            } else if (fieldReference.hasTable()) {
                String nextTable = fieldReference.getTable().getName();
                addErrorMessageAndThrowIfNoImplicitPath(field, sourceTable, nextTable);
                if (nextTable.equals(targetTable)) {
                    return;
                } else {
                    sourceTable = nextTable;
                }
            }
        }

        // Because scalar fields have the whole reference path in the reference directive, validation has completed at this point
        if (schema.isScalar(field)) {
            return;
        }

        addErrorMessageAndThrowIfNoImplicitPath(field, sourceTable, targetTable);
    }

    private void addErrorMessageAndThrowIfNoImplicitPath(GenerationField field, String leftTable, String rightTable) {
        var possibleKeys = getNumberOfForeignKeysBetweenTables(leftTable, rightTable);
        if (possibleKeys == 1) return;

        var isMultitableField = field instanceof VirtualSourceField;

        addErrorMessageAndThrow("Error on field \"%s\" in type \"%s\": " +
                        "%s found between tables \"%s\" and \"%s\"%s. Please specify path with the @%s directive.",
                isMultitableField ? ((VirtualSourceField) field).getOriginalFieldName() : field.getName(),
                field.getContainerTypeName(),
                possibleKeys == 0 ? "No foreign key" : "Multiple foreign keys",
                leftTable,
                rightTable,
                isMultitableField ? String.format(" in reference path for type '%s'", field.getTypeName()) : "",
                isMultitableField ? MULTITABLE_REFERENCE.getName() : REFERENCE.getName()
        );
    }

    /**
     * Returns target table for field determined by target type's table, nodeId directive or 'table' in last item provided in reference directive.
     * Does not find target table from key only.
     * @return Name of the target table
     */
    private String getTargetTableForField(GenerationField field) {
        if (schema.isNodeIdField(field)) {
            return schema.getNodeTypeForNodeIdField(field).getTable().getName();
        }
        if (schema.hasTableObject(field)) {
            return schema.getObjectOrConnectionNode(field).getTable().getName();
        }

        if (schema.isScalar(field)) {
            if (!field.hasFieldReferences()) {
                return null;
            }
            var references = field.getFieldReferences();

            return Optional.ofNullable(references.get(references.size() - 1))
                    .filter(FieldReference::hasTable)
                    .map(it -> it.getTable().getName()).orElse(null);
        }
        return null;
    }

    private void validateRequiredMethodCalls() {
        getUsedTablesWithRequiredMethods()
                .entrySet()
                .stream()
                .filter(it -> tableOrKeyExists(it.getKey()))
                .forEach(entry -> {
                    var tableName = entry.getKey();
                    var allFieldNames = getJavaFieldNamesForTable(tableName);
                    var nonFieldElements = entry.getValue().stream().filter(it -> !allFieldNames.contains(it)).toList();
                    var missingElements = nonFieldElements
                            .stream()
                            .filter(it -> searchTableForMethodWithName(tableName, it).isEmpty())
                            .toList();

                    if (!missingElements.isEmpty()) {
                        addWarningMessage("No field(s) or method(s) with name(s) '%s' found in table '%s'",
                                missingElements.stream().sorted().collect(Collectors.joining(", ")), tableName);
                    }
                });
    }

    @NotNull
    private HashMap<String, HashSet<String>> getUsedTablesWithRequiredMethods() {
        var tableMethodsRequired = new HashMap<String, HashSet<String>>();
        schema.getObjects().values().stream().filter(it -> it.hasTable() || it.isOperationRoot()).forEach(object -> {
            var flattenedFields = flattenObjectFields(object.isOperationRoot() ? null : object.getTable().getMappingName(), object.getFields());
            unpackReferences(flattenedFields).forEach((key, value) -> tableMethodsRequired.computeIfAbsent(key, k -> new HashSet<>()).addAll(value));
        });
        schema.getInputTypes().values().stream().filter(RecordObjectDefinition::hasTable).forEach(input -> {
            var flattenedFields = flattenInputFields(input.getTable().getMappingName(), input.getFields(), false);

            unpackReferences(flattenedFields).forEach((key, value) -> tableMethodsRequired.computeIfAbsent(key, k -> new HashSet<>()).addAll(value));
        });
        return tableMethodsRequired;
    }

    private Map<String, ArrayList<FieldWithOverrideStatus>> flattenObjectFields(String lastTable, Collection<ObjectField> fields) {
        var flatFields = new HashMap<String, ArrayList<FieldWithOverrideStatus>>();
        var lastTableIsNonNull = lastTable != null;
        for (var field : fields) {
            if (schema.isObject(field) && !schema.isInterface(field) && !schema.isUnion(field)) {
                var table = lastTableIsNonNull
                        ? lastTable
                        : (schema.hasTableObject(field) ? schema.getObjectOrConnectionNode(field).getTable().getMappingName() : null);
                var object = schema.getObjectOrConnectionNode(field);
                if (!object.hasTable()) {
                    flattenObjectFields(table, object.getFields()).forEach((key, value) -> flatFields.computeIfAbsent(key, k -> new ArrayList<>()).addAll(value));
                }
            }

            var hasCondition = field.hasOverridingCondition();
            if (field.hasNonReservedInputFields() && field.isGeneratedWithResolver()) {
                var targetTable = schema.hasTableObject(field) ? schema.getObjectOrConnectionNode(field).getTable().getMappingName() : lastTable;
                flattenInputFields(targetTable, field.getNonReservedArguments(), field.hasOverridingCondition())
                        .forEach((key, value) -> flatFields.computeIfAbsent(key, k -> new ArrayList<>()).addAll(value));
            }

            if (lastTableIsNonNull) {
                flatFields.computeIfAbsent(lastTable, k -> new ArrayList<>()).add(new FieldWithOverrideStatus(field, hasCondition));
            }
        }
        return flatFields;
    }

    private Map<String, ArrayList<FieldWithOverrideStatus>> flattenInputFields(String lastTable, Collection<? extends InputField> fields, boolean hasOverridingCondition) {
        var flatFields = new HashMap<String, ArrayList<FieldWithOverrideStatus>>();
        for (var field : fields) {
            var hasCondition = hasOverridingCondition || field.hasOverridingCondition();
            if (schema.isInputType(field)) {
                var object = schema.getInputType(field);
                if (!object.hasTable()) {
                    flattenInputFields(lastTable, object.getFields(), hasCondition)
                            .forEach((key, value) -> flatFields.computeIfAbsent(key, k -> new ArrayList<>()).addAll(value));
                }
            } else if (field.isGenerated() && !ObjectField.RESERVED_PAGINATION_NAMES.contains(field.getName())) {
                flatFields.computeIfAbsent(lastTable, k -> new ArrayList<>()).add(new FieldWithOverrideStatus(field, hasCondition));
            }
        }
        return flatFields;
    }

    private HashMap<String, HashSet<String>> unpackReferences(Map<String, ArrayList<FieldWithOverrideStatus>> flattenedFields) {
        var requiredJOOQTypesAndMethods = new HashMap<String, HashSet<String>>();
        for (var fieldEntry : flattenedFields.entrySet()) {
            var table = fieldEntry.getKey();
            var fields = fieldEntry.getValue();
            for (var fieldWithConditionStatus : fields) {
                var field = fieldWithConditionStatus.field;
                var fieldIsTableObject = schema.hasTableObject(field);
                var lastTable = table;
                if (field.hasFieldReferences()) {
                    lastTable = findForReferences(field, lastTable, requiredJOOQTypesAndMethods);
                } else if (fieldIsTableObject && !(field.isRootField()) && !field.isResolver()) {
                    requiredJOOQTypesAndMethods
                            .computeIfAbsent(lastTable, (k) -> new HashSet<>())
                            .add(schema.getObjectOrConnectionNode(field).getTable().getCodeName());
                }

                if (fieldIsTableObject) {
                    lastTable = schema.getObjectOrConnectionNode(field).getTable().getMappingName();
                }

                var fieldIsDBMapped = !schema.isObject(field) // TODO: If it has a condition on the reference and no other path, it should also be excluded.
                        && !schema.isInputType(field)
                        && !schema.isJavaMappedEnum(field)
                        && !schema.isUnion(field)
                        && !field.hasOverridingCondition()
                        && !fieldWithConditionStatus.hasOverrideCondition;
                if (lastTable != null && fieldIsDBMapped) {
                    // New set is added anyway to be able to check tables only.
                    var listToAddTo = requiredJOOQTypesAndMethods.computeIfAbsent(lastTable, (k) -> new HashSet<>());
                    if (!field.isID()) {
                        listToAddTo.add(field.getUpperCaseName());
                    }
                }
            }
        }
        return requiredJOOQTypesAndMethods;
    }

    private String findForReferences(GenerationField field, String lastTable, HashMap<String, HashSet<String>> requiredJOOQTypesAndMethods) {
        for (var reference : field.getFieldReferences()) {
            if (reference.hasKey() && lastTable != null) {
                var key = reference.getKey();
                var nextTable = reference.hasTable()
                        ? reference.getTable().getMappingName()
                        : (schema.isRecordType(field) ? schema.getObjectOrConnectionNode(field).getTable().getMappingName() : lastTable);
                var reverseKeyFound = searchTableForMethodWithName(nextTable, key.getMappingName()).isPresent(); // In joins the key can also be used in reverse.
                requiredJOOQTypesAndMethods
                        .computeIfAbsent(reverseKeyFound ? nextTable : lastTable, (k) -> new HashSet<>())
                        .add(key.getMappingName());
            } else if (reference.hasTable() && lastTable != null) {
                requiredJOOQTypesAndMethods
                        .computeIfAbsent(lastTable, (k) -> new HashSet<>())
                        .add(reference.getTable().getCodeName());
            }

            if (reference.hasTable()) {
                lastTable = reference.getTable().getMappingName();
            }
        }
        return lastTable;
    }

    private void validateExternalMappingReferences() {
        var referenceSet = GeneratorConfig.getExternalReferences();
        schema
                .getEnums()
                .values()
                .stream()
                .filter(EnumDefinition::hasJavaEnumMapping)
                .map(EnumDefinition::getEnumReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> addErrorMessage("No enum with name '%s' found.", e.getSchemaClassReference()));

        allFields
                .stream()
                .filter(ObjectField::isGenerated)
                .filter(ObjectField::hasServiceReference)
                .map(ObjectField::getExternalMethod)
                .map(ServiceWrapper::getReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> addErrorMessage("No service with name '%s' found.", e.getSchemaClassReference()));

        allFields
                .stream()
                .filter(ObjectField::isGenerated)
                .filter(ObjectField::hasCondition)
                .map(ObjectField::getCondition)
                .map(SQLCondition::getReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> addErrorMessage("No condition with name '%s' found.", e.getSchemaClassReference()));
    }

    private void validateServiceMethods() {
        var referenceSet = GeneratorConfig.getExternalReferences();
        allFields
                .stream()
                .filter(ObjectField::isGenerated)
                .filter(ObjectField::hasServiceReference)
                .map(GenerationSourceField::getExternalMethod)
                .map(ServiceWrapper::getReference)
                .filter(referenceSet::contains)
                .filter(it -> referenceSet.getMethodsFrom(it).stream().findFirst().isEmpty())
                .forEach(it -> addErrorMessage("Service reference with name '%s' does not contain a method named '%s'.", referenceSet.getClassFrom(it), it.getMethodName()));
    }

    private void validateUnionFieldsTable() {
        if (schema.getObjects().containsKey("Query")) {
            for (ObjectField field : schema.getObject("Query").getFields()) {
                if (field.getName().equals(FEDERATION_ENTITIES_FIELD.getName())) {
                    continue;
                }

                if (schema.isUnion(field)) {
                    for (ObjectDefinition subType : schema.getUnionSubTypes(field.getTypeName())) {
                        if (!subType.hasTable()) {
                            addErrorMessage("Type %s in Union '%s' in Query has no table.", subType.getName(), schema.getUnion(field).getName());
                        }
                    }
                }
            }
        }
    }

    private void validateMultitableFieldsOutsideRoot() {
        allFields.stream()
                .filter(GenerationSourceField::isGenerated)
                .filter(it -> !it.isRootField())
                .filter(it -> schema.isObjectWithPreviousTableObject(it.getContainerTypeName()))
                .filter(schema::isMultiTableField)
                .forEach(field -> {
                    if (!field.isResolver()) {
                        addErrorMessage("%s is a multitable field outside root, but is missing the %s directive. " +
                                        "Multitable queries outside root is only supported for resolver fields.",
                                field.formatPath(), SPLIT_QUERY.getName()
                        );
                    }

                    if (field.hasFieldReferences()) {
                        addErrorMessage("%s has the %s directive which is not supported on multitable queries. Use %s directive instead.",
                                field.formatPath(), REFERENCE.getName(), MULTITABLE_REFERENCE.getName()
                        );
                    }
                });
    }

    private void validateSingleTableInterfaceDefinitions() {
        schema.getInterfaces().forEach((name, interfaceDefinition) -> {
                    if (name.equalsIgnoreCase(NODE_TYPE.getName()) || name.equalsIgnoreCase(ERROR_TYPE.getName())) return;

                    var implementations = schema.getObjects()
                            .values()
                            .stream()
                            .filter(it -> it.implementsInterface(schema.getInterface(name).getName())).toList();

                    if (interfaceDefinition.hasDiscriminator() == interfaceDefinition.isMultiTableInterface()) {
                        addErrorMessage(
                                String.format("'%s' and '%s' directives on interfaces must be used together. " +
                                                "Interface '%s' is missing '%s' directive.",
                                        DISCRIMINATE.getName(), TABLE.getName(), name,
                                        interfaceDefinition.hasTable() ? DISCRIMINATE.getName() : TABLE.getName()));
                    }

                    if (!interfaceDefinition.isMultiTableInterface()) { // Single table interface with discriminator
                        Optional<?> discriminatorField = getField(
                                interfaceDefinition.getTable().getName(),
                                interfaceDefinition.getDiscriminatorFieldName()
                        );
                        if (discriminatorField.isEmpty()) {
                            addErrorMessage(
                                    String.format("Interface '%s' has discriminating field set as '%s', but the field " +
                                                    "does not exist in table '%s'.",
                                            name, interfaceDefinition.getDiscriminatorFieldName(), interfaceDefinition.getTable().getName()));
                        } else {
                            Optional<Class<?>> fieldType = getFieldType(
                                    interfaceDefinition.getTable().getName(),
                                    interfaceDefinition.getDiscriminatorFieldName()
                            );
                            if (fieldType.isEmpty() || !fieldType.get().equals(String.class)) {
                                addErrorMessage(
                                        String.format("Interface '%s' has discriminating field set as '%s', but the field " +
                                                        "does not return a string type, which is not supported.",
                                                name, interfaceDefinition.getDiscriminatorFieldName()));
                            }
                        }

                        implementations.forEach(impl -> {
                            if (!impl.hasDiscriminator()) {
                                addErrorMessage(
                                        String.format("Type '%s' is missing '%s' directive in order to implement interface '%s'.",
                                                impl.getName(), DISCRIMINATOR.getName(), name));
                            }
                            if (impl.hasTable() && interfaceDefinition.hasTable() && !impl.getTable().equals(interfaceDefinition.getTable())) {
                                addErrorMessage(
                                        String.format("Interface '%s' requires implementing types to have table '%s', " +
                                                        "but type '%s' has table '%s'.",
                                                name, interfaceDefinition.getTable().getName(), impl.getName(), impl.getTable().getName()));
                            }

                            impl.getFields()
                                    .stream()
                                    .filter(it -> interfaceDefinition.hasField(it.getName()))
                                    .forEach(it -> {
                                        var fieldInInterface = interfaceDefinition.getFieldByName(it.getName());
                                        String sharedErrorMessage = "Overriding '%s' configuration in types implementing " +
                                                "a single table interface is not currently supported, and must be identical " +
                                                "with interface. Type '%s' has a configuration mismatch on field '%s' from the interface '%s'.";

                                        if (it.hasCondition() != fieldInInterface.hasCondition()
                                                || (it.hasCondition() && !it.getCondition().equals(fieldInInterface.getCondition()))) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    CONDITION.getName(), impl.getName(), it.getName(), name
                                            );
                                        }

                                        if (!it.getFieldReferences().equals(fieldInInterface.getFieldReferences())) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    REFERENCE.getName(), impl.getName(), it.getName(), name
                                            );
                                        }
                                    });
                        });

                        // Check for conflicts in "shared" fields which are not in the interface definition
                        implementations.stream()
                                .map(AbstractObjectDefinition::getFields)
                                .flatMap(Collection::stream)
                                .filter(it -> !interfaceDefinition.hasField(it.getName()))
                                .collect(groupingBy(ObjectField::getName))
                                .entrySet()
                                .stream()
                                .filter(it -> it.getValue().size() > 1)
                                .forEach(entry -> {
                                    var fields = entry.getValue();
                                    var first = fields.get(0);

                                    fields.stream().skip(1).forEach(field -> {
                                        String sharedErrorMessage = "Different configuration on fields in types implementing the same single table interface is currently not supported. " +
                                                "Field '%s' occurs in two or more types implementing interface '%s', but there is a mismatch between the configuration of the '%s' directive.";
                                        if (!field.getUpperCaseName().equals(first.getUpperCaseName())) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    entry.getKey(), interfaceDefinition.getName(), FIELD.getName()
                                            );
                                        }

                                        if (!field.getFieldReferences().equals(first.getFieldReferences())) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    entry.getKey(), interfaceDefinition.getName(), REFERENCE.getName());
                                        }

                                        if (!(field.hasCondition() == first.hasCondition()
                                                && (!field.hasCondition() || field.getCondition().equals(first.getCondition())))) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    entry.getKey(), interfaceDefinition.getName(), CONDITION.getName());
                                        }
                                    });
                                });
                    }
                }
        );

        schema.getObjects().values().stream()
                .filter(objectDefinition -> !objectDefinition.getImplementedInterfaces().isEmpty() || objectDefinition.hasDiscriminator())
                .forEach(objectDefinition -> {
                    var implementedInterfaces = objectDefinition.getImplementedInterfaces();

                    var singleTableInterfaces = implementedInterfaces.stream()
                            .filter(it -> !it.equals(NODE_TYPE.getName()))
                            .filter(schema::isSingleTableInterface)
                            .toList();

                    if (singleTableInterfaces.isEmpty() && objectDefinition.hasDiscriminator()) {
                        addErrorMessage(
                                String.format("Type '%s' has discriminator, but doesn't implement any interfaces requiring it.", objectDefinition.getName())
                        );
                    }
                });
    }

    private void validateInterfacesReturnedInFields() {
        allFields.stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(schema::isInterface)
                .forEach(field -> {
                            var typeName = field.getTypeName();
                            var name = Optional
                                    .ofNullable(schema.getObjectOrConnectionNode(typeName))
                                    .map(AbstractObjectDefinition::getName)
                                    .orElse(typeName);
                            if (!field.isRootField() && (schema.isSingleTableInterface(field) || name.equals(NODE_TYPE.getName()))) {
                                addErrorMessage("interface (%s) returned in non root object. This is not fully " +
                                        "supported. Use with care", name);
                            }

                            if (name.equalsIgnoreCase(NODE_TYPE.getName())) {
                                isTrue(
                                        field.getArguments().size() == 1,
                                        "Only exactly one input field is currently supported for fields returning the '%s' interface. " +
                                                "%s has %s input fields", NODE_TYPE.getName(), field.formatPath(), field.getArguments().size()
                                );
                                isTrue(
                                        !field.isIterableWrapped(),
                                        "Generating fields returning a list of '%s' is not supported. " +
                                                "'%s' must return only one %s", name, field.getName(), field.getTypeName()
                                );
                            } else {
                                schema.getObjects()
                                        .values()
                                        .stream()
                                        .filter(it -> it.implementsInterface(schema.getInterface(name).getName()))
                                        .forEach(implementation -> {
                                            if (!implementation.hasTable()) {
                                                addErrorMessage("Interface '%s' is returned in field '%s', but type '%s' " +
                                                        "implementing '%s' does not have table set. This is not supported.", name, field.getName(), implementation.getName(), name);
                                            } else if (!tableHasPrimaryKey(implementation.getTable().getName())) {
                                                addErrorMessage("Interface '%s' is returned in field '%s', but implementing type '%s' " +
                                                        "has table '%s' which does not have a primary key. This is not supported.", name, field.getName(), implementation.getName(), implementation.getTable().getName());
                                            }
                                        });
                            }

                        }
                );
    }

    private void validateTypesUsingNodeInterfaceWithoutNodeDirective() {
        if (!schema.nodeExists() ||
                schema.getQueryType() == null ||
                schema.getQueryType().getFieldByName(uncapitalize(NODE_TYPE.getName())) == null ||
                schema.getQueryType().getFieldByName(uncapitalize(NODE_TYPE.getName())).isExplicitlyNotGenerated()) {
            return;
        }

        var records = schema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(NODE_TYPE.getName()) && it.hasTable() && !it.hasNodeDirective())
                .collect(groupingBy(
                        it -> it.getTable().getName(), Collectors.mapping(ObjectDefinition::getName, Collectors.toSet())));

        records.forEach((tableName, schemaTypes) -> {
            if (schemaTypes.size() > 1) {
                addErrorMessage(
                        "Multiple types (%s) implement the %s interface and refer to the same table %s. This is not supported.",
                        String.join(", ", schemaTypes), NODE_TYPE.getName(), tableName);
            }
        });
    }

    // TODO: It seems we are now handling/validating lists that are directly below parent lists. But what about
    //  validation of nested lists where a list is not directly below parent list, but are deeper down the hierarchy?
    private void validateInputFields() {
        var oneLayerFlattenedFields = allFields
                .stream()
                .filter(schema::hasTableObject)
                .flatMap(it -> it.getNonReservedArguments().stream())
                .filter(it -> !schema.hasRecord(it) && !it.hasOverridingCondition())
                .filter(AbstractField::isIterableWrapped)
                .filter(schema::isInputType)
                .toList();

        for (var field : oneLayerFlattenedFields) {
            var messageStart = String.format("Argument '%s' is a collection of InputFields ('%s') type.", field.getName(), field.getTypeName());
            var inputDefinitionFields = schema.getInputType(field).getFields();

            inputDefinitionFields.stream().filter(AbstractField::isIterableWrapped).findFirst().ifPresent(it -> addErrorMessageAndThrow(
                    "%s Fields returning collections: '%s' are not supported on such types (used for generating condition tuples)",
                    messageStart,
                    it.getName()
            ));

            var optionalFields = inputDefinitionFields
                    .stream()
                    .filter(AbstractField::isNullable)
                    .map(AbstractField::getName)
                    .collect(Collectors.toList());
            if (!optionalFields.isEmpty()) {
                addErrorMessage(
                        String.format(
                                "%s Optional fields on such types are not supported. The following fields will be " +
                                        "treated as mandatory in the resulting, generated condition tuple: '%s'",
                                messageStart,
                                String.join("', '", optionalFields)
                        )
                );
            }
        }
    }

    public void validateObjectFieldTypes() {
        schema.getRecordTypes()
                .values()
                .stream()
                .map(this::validateFieldTypes)
                .filter(it -> !it.isEmpty())
                .forEach(ValidationHandler::addErrorMessage);

        throwIfErrors();

    }

    private String validateFieldTypes(RecordObjectSpecification<? extends GenerationField> object) {
        var objectName = object.getName();
        return object
                .getFields()
                .stream()
                .flatMap(it -> validateFieldType(objectName, it).stream())
                .filter(it -> !it.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private List<String> validateFieldType(String objectName, GenerationField field) {
        return checkTypeExists(field.getTypeName(), Set.of()) // May contain more than one if type is union.
                .entrySet()
                .stream()
                .map(it -> String.format("Field \"%s\" within schema type \"%s\" has invalid type \"%s\" (or an union containing it). Closest type matches found by levenshtein distance are:\n%s", field.getName(), objectName, it.getKey(), it.getValue()))
                .collect(Collectors.toList());
    }

    private Map<String, String> checkTypeExists(String typeName, Set<String> seenTypes) {
        if (seenTypes.contains(typeName) || schema.isScalar(typeName)) { // Check seen types in case there are some strange Union recursions.
            return Map.of();
        }

        if (schema.isUnion(typeName)) {
            return schema
                    .getUnion(typeName)
                    .getFieldTypeNames()
                    .stream()
                    .flatMap(it -> checkTypeExists(it, Stream.concat(seenTypes.stream(), Stream.of(typeName)).collect(Collectors.toSet())).entrySet().stream())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        if (schema.isType(typeName)) {
            return Map.of();
        }

        // If we make it here, no valid type could be identified.
        var levenshtein = new LevenshteinDistance(12); // Limit for better performance, but these names are probably not that long anyway. Higher values are converted to -1.
        var distances = schema.getAllValidFieldTypeNames().stream().collect(Collectors.toMap(Function.identity(), it -> levenshtein.apply(typeName, it)));
        var distanceThreshold = distances.values().stream().filter(it -> it > -1).min(Integer::compare).orElse(0); // Could potentially allow a wider match by picking a higher number.

        return Map.of(typeName, distances.entrySet().stream().filter(it -> -1 < it.getValue() && it.getValue() <= distanceThreshold).map(it -> it.getKey() + " - " + it.getValue()).collect(Collectors.joining(", ")));
    }

    private void checkPaginationSpecs(List<ObjectField> fields) {
        for (var field : fields) {
            var hasConnectionSuffix = field.getTypeName().endsWith(SCHEMA_CONNECTION_SUFFIX.getName());
            if (hasConnectionSuffix && !field.hasRequiredPaginationFields()) {
                addErrorMessage(
                        "Type %s ending with the reserved suffix 'Connection' must have either " +
                                "forward(first and after fields) or backwards(last and before fields) pagination, " +
                                "yet neither was found.", field.getTypeName()
                );
            }
        }
    }

    private void validateMutationDirectives() {
        var mutation = schema.getMutationType();
        if (mutation == null) {
            return;
        }

        var mutations = mutation
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .toList();
        if (mutations.isEmpty()) {
            return;
        }

        mutations
                .stream()
                .filter(it -> !it.hasMutationType() && !it.hasServiceReference())
                .forEach(it -> addErrorMessage("Mutation '%s' is set to generate, but has neither a service nor mutation type set.", it.getName()));
        mutations
                .stream()
                .filter(it -> it.hasMutationType() && !new InputParser(it, schema).hasJOOQRecords())
                .forEach(it -> addErrorMessage("Mutations must have at least one table attached when generating resolvers with queries. Mutation '%s' has no tables attached.", it.getName()));
    }

    private void validateMutationRequiredFields() {
        var mutation = schema.getMutationType();
        if (mutation == null) {
            return;
        }

        mutation
                .getFields()
                .stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(ObjectField::hasMutationType)
                .forEach(target -> {
                    validateRecordRequiredFields(target);
                    validateMutationWithReturning(target);
                    new InputParser(target, schema).getJOOQRecords().values().forEach(inputField -> checkMutationIOFields(inputField, target));
                });
    }

    private void validateRecursiveRecordInputs() {
        var mutation = schema.getMutationType();
        var mutations = mutation != null ? mutation.getFields().stream() : Stream.<ObjectField>of();
        var query = schema.getQueryType();
        var queries = query != null ? query.getFields().stream() : Stream.<ObjectField>of();

        Stream
                .concat(queries, mutations)
                .filter(ObjectField::hasServiceReference)
                .flatMap(it -> it.getArguments().stream())
                .filter(schema::isInputType)
                .forEach(it -> validateRecursiveRecordInputs(it, false, 0));
    }

    private void validateOnlyOneInputRecordInputWhenNoTypeTableIsPresent() {
        var mutation = schema.getMutationType();
        var mutations = mutation != null ? mutation.getFields().stream() : Stream.<ObjectField>of();
        var query = schema.getQueryType();
        var queries = query != null ? query.getFields().stream() : Stream.<ObjectField>of();

        Stream
                .concat(queries, mutations)
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .filter(it -> !it.getTypeName().equals(FEDERATION_SERVICE_TYPE.getName()))
                .filter(it -> !it.getTypeName().equals(FEDERATION_ENTITY_UNION.getName()))
                .filter(it -> !schema.isInterface(it))
                .filter(it -> !schema.isUnion(it))
                .filter(it -> schema.isScalar(it) || schema.isRecordType(it))
                .filter(it -> !schema.nextTypeTableExists(it, new HashSet<>()))
                .collect(Collectors.toMap(it -> it.getContainerTypeName() + "." + it.getName(), it -> schema.findInputTables(it).size()))
                .entrySet()
                .stream()
                .filter(it -> it.getValue() != 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                .forEach((key, value) -> addErrorMessage(
                        "%s is a field of a type without a table, and has %s potential input records to use as a source for the table in queries. In such cases, there must be exactly one input table so that it can be resolved unambiguously.",
                        key,
                        value
                ));
    }

    private void validateRecursiveRecordInputs(InputField field, boolean wasRecord, int recursion) {
        recursionCheck(recursion);

        var input = schema.getInputType(field);
        if (input == null) {
            return;
        }

        var hasTableOrRecordReference = input.hasTable() || input.hasJavaRecordReference();

        if (field.isIterableWrapped() && wasRecord && !hasTableOrRecordReference) {
            addErrorMessage(
                    String.format(
                            "Field %s with Input type %s is iterable, but has no record mapping set. Iterable Input types within records without record mapping can not be mapped to a single field in the surrounding record.",
                            field.getName(),
                            input.getName()
                    )
            );
        }

        input.getFields().forEach(it -> validateRecursiveRecordInputs(it, wasRecord || hasTableOrRecordReference, recursion + 1));
    }

    //Check input and payload("output") fields
    private void checkMutationIOFields(InputField inputField, ObjectField objectField) {
        if(!schema.isObject(objectField))
            return;

        var objectFieldErrors = new InputParser(objectField, schema).getAllErrors();
        var payloadContainsIterableField = objectField.isIterableWrapped() ||
                schema.getObject(objectField)
                        .getFields()
                        .stream()
                        .filter(field -> !objectFieldErrors.contains(field))
                        .anyMatch(AbstractField::isIterableWrapped);

        if (!inputField.isIterableWrapped() && payloadContainsIterableField) {
            addWarningMessage(
                    "Mutation %s with Input %s is not defined as a list while Payload type %s contains " +
                                    "a list",
                            objectField.getName(), inputField.getTypeName(), objectField.getTypeName());
        } else if (inputField.isIterableWrapped() && !payloadContainsIterableField) {
            addWarningMessage(
                    "Mutation %s with Input %s is defined as a list while Payload type %s does not " +
                                    "contain a list",
                            objectField.getName(), inputField.getTypeName(), objectField.getTypeName());
        }
    }

    private void validateRecordRequiredFields(ObjectField target) {
        var mutationType = target.getMutationType();
        if (mutationType.equals(MutationType.INSERT) || mutationType.equals(MutationType.UPSERT)) {
            var recordInputs = new InputParser(target, schema).getJOOQRecords().values();
            if (recordInputs.isEmpty()) {
                addErrorMessage("Mutation "
                        + target.getName()
                        + " is set as an " + lowerCase(mutationType.name()) + " operation, but does not link any input to tables.");
            }

            recordInputs.forEach(this::checkRequiredFields);
        }
    }

    private void validateMutationWithReturning(ObjectField field) {
        boolean isDeleteWithReturning = schema.isDeleteMutationWithReturning(field);
        boolean isInsertWithReturning = schema.isInsertMutationWithReturning(field);

        if (!isDeleteWithReturning && !isInsertWithReturning) {
            return;
        }
        /* Validate output */
        var dataField = schema.inferDataTargetForMutation(field);

        if (dataField.isEmpty()) {
            addErrorMessage("Cannot find correct field to output data to after mutation for field %s.", field.formatPath());
            return;
        }

        /* Validate input */
        var recordInputs = new InputParser(field, schema).getJOOQRecords().values(); // TODO: support non-jOOQ record inputs
        if (recordInputs.isEmpty()) {
            addErrorMessage("Field %s is a generated %s mutation, but does not link any input to tables.", field.formatPath(), field.getMutationType());
            return;
        } else if (recordInputs.size() != 1) {
            addErrorMessage("Field %s is a generated %s mutation, but has multiple input records. This is not supported.", field.formatPath(), field.getMutationType());
            return;
        }

        var input = recordInputs.stream().findFirst().orElseThrow();

        if (isInsertWithReturning && !shouldMakeNodeStrategy() && schema.getInputType(input).getFields().stream().anyMatch(AbstractField::isID)) {
            addErrorMessage("%s is a generated %s field with ID input, but this is only supported with node ID strategy enabled.",
                    field.formatPath(),
                    field.getMutationType()
            );
            return;
        }

        var inputTable = schema.getRecordType(input).getTable();
        var outputTable = schema.isScalar(dataField.get()) ? inputTable : schema.getRecordType(dataField.get()).getTable();

        if (!inputTable.equals(outputTable)) {
            addErrorMessage("Mutation field %s has a mismatch between input and output tables. Input table is '%s', and output table is '%s'.",
                    field.formatPath(), inputTable.getMappingName(), outputTable.getMappingName());
            return;
        }

        if (input.isNullable() || input.isIterableWrappedWithNullableElement()) {
            addErrorMessage("Field %s is a generated %s mutation, but has nullable input. This is not supported. Consider changing the input type from '%s' to '%s'.",
                    field.formatPath(), field.getMutationType(),
                    input.formatGraphQLSchemaType(),
                    input.isIterableWrapped() ? "[" + input.getTypeName() + "!]!" : input.getTypeName() + "!");
        }

        if (isDeleteWithReturning) {
            validateDeleteMutation(field, dataField.orElse(null), input);
        }
    }

    private void validateDeleteMutation(ObjectField field, ObjectField dataField, InputField input) {
        var inputType = schema.getInputType(input);
        var targetTable = inputType.getTable();

        /* Validate that there are no references in output */
        if (dataField != null && schema.isRecordType(dataField)){
            var subqueryReferenceFields = findSubqueryReferenceFieldsForTableObject(dataField, targetTable, 0);
            if (!subqueryReferenceFields.isEmpty()) {
                addErrorMessage(
                        "Mutation field %s has references returned from the data field. " +
                                "This is not supported for %s mutations. Found reference fields are: %s",
                        field.formatPath(), MutationType.DELETE, String.join(", ", subqueryReferenceFields.stream().map(GenerationField::formatPath).toList()));
            }
        }

        /* Validate that there is non-nullable input matching a PK/UK */
        var idFields = inputType.getFields().stream()
                .filter(it -> it.isID() || schema.isNodeIdField(it))
                .filter(it -> !it.hasFieldReferences())
                .filter(it -> !schema.isNodeIdField(it) || schema.getNodeTypeForNodeIdField(it).getTable().equals(targetTable))
                .filter(it -> schema.isNodeIdField(it) || it.getUpperCaseName().equalsIgnoreCase(GraphQLReservedName.NODE_ID.getName()))
                .toList();

        var requiredIdOrNodeIdFields = idFields.stream()
                .filter(AbstractField::isNonNullable)
                .toList();

        if (requiredIdOrNodeIdFields.isEmpty()) {
            var possibleFixes = new ArrayList<>(idFields.stream().map(GenerationSourceField::formatPath).map(it -> "Make " + it + " non-nullable.").toList());
            if (schema.getNodeTypesWithTable(targetTable).size() == 1) {
                possibleFixes.add(String.format("Add non-nullable node ID input field for type '%s'.",
                        schema.getNodeTypesWithTable(targetTable).stream().findFirst().get().getName())
                );
            }

            var keys = getPrimaryAndUniqueKeysForTable(targetTable.getName());
            var nonNullableInputFields = inputType.getFields().stream()
                    .filter(AbstractField::isNonNullable)
                    .toList();

            for (var key : keys) {
                var keyFields = getJavaFieldNamesForKey(targetTable.getName(), key);
                var keyFieldToInputFieldMatches = keyFields.stream()
                        .collect(Collectors.toMap(
                                kf -> kf,
                                kf -> nonNullableInputFields.stream()
                                        .filter(f -> f.getUpperCaseName().equalsIgnoreCase(kf))
                                        .toList()
                        ));

                if (keyFieldToInputFieldMatches.entrySet().stream().anyMatch(e -> e.getValue().isEmpty())) {
                    possibleFixes.add(String.format("Add non-nullable input fields for %s to match PK/UK '%s'.",
                            String.join(", ", keyFieldToInputFieldMatches.entrySet().stream().filter(it -> it.getValue().isEmpty()).map(Map.Entry::getKey).toList()),
                            key.getName())
                    );
                } else {
                    return;
                }
            }

            addErrorMessage("Mutation field %s is a generated delete mutation, but does not have input with non-nullable fields corresponding to a PK/UK of the table. %s%s",
                    field.formatPath(),
                    !possibleFixes.isEmpty() ? "\nPossible fix(es):\n* " : "",
                    String.join("\n* ", possibleFixes)
            );
        }

    }

    private Set<GenerationField> findSubqueryReferenceFieldsForTableObject(GenerationField target, JOOQMapping targetTable, int recursion) {
        recursionCheck(recursion);

        if (target.hasFieldReferences() || schema.isNodeIdReferenceField(target)) {
            return Set.of(target);
        }

        var result = new HashSet<GenerationField>();

        if (schema.isRecordType(target)) {
            var recordType = schema.getRecordType(target);

            // Implicit reference
            if (recordType.hasTable() && !recordType.getTable().equals(targetTable)) {
                return Set.of(target);
            }

            recordType.getFields()
                    .stream()
                    .filter(GenerationTarget::isGenerated)
                    .filter(it -> !it.isResolver())
                    .forEach(f -> {
                        if (f.hasFieldReferences()) {
                            result.add(f);
                        } else {
                            result.addAll(findSubqueryReferenceFieldsForTableObject(f, targetTable, recursion + 1));
                        }
                    });
        }
        return result;
    }

    protected void checkRequiredFields(InputField recordInput) {
        var inputObject = schema.getInputType(recordInput);
        var tableName = inputObject.getTable().getMappingName();

        var requiredDBFields = getRequiredFields(tableName)
                .stream()
                .map(String::toUpperCase)
                .filter(it -> !tableFieldHasDefaultValue(tableName, it)) // No need to complain when it has a default set. Note that this does not work for views.
                .collect(Collectors.toList());
        var recordFieldNames =
                inputObject.getFields()
                        .stream()
                        .map(InputField::getUpperCaseName)
                        .collect(Collectors.toSet());
        checkRequiredFieldsExist(recordFieldNames, requiredDBFields, recordInput, ERROR_MISSING_FIELD);

        var requiredRecordFieldNames =
                inputObject.getFields()
                        .stream()
                        .filter(AbstractField::isNonNullable)
                        .map(InputField::getUpperCaseName)
                        .collect(Collectors.toSet());
        checkRequiredFieldsExist(requiredRecordFieldNames, requiredDBFields, recordInput, ERROR_MISSING_NON_NULLABLE);
    }

    protected void checkRequiredFieldsExist(Set<String> actualFields, List<String> requiredFields, InputField recordInput, String message) {
        if (!actualFields.containsAll(requiredFields)) {
            var missingFields = requiredFields.stream().filter(it -> !actualFields.contains(it)).collect(Collectors.joining(", "));
            addWarningMessage(
                message,
                recordInput.getTypeName(),
                schema.getInputType(recordInput).getTable().getMappingName(),
                missingFields
            );
        }
    }

    private final static class FieldWithOverrideStatus {
        GenerationSourceField<?> field;
        boolean hasOverrideCondition;

        public FieldWithOverrideStatus(GenerationSourceField<?> field, boolean hasOverrideCondition) {
            this.field = field;
            this.hasOverrideCondition = hasOverrideCondition;
        }
    }

    private void validateSelfReferenceHasSplitQuery() {
        schema.getObjects().values()
                .forEach(object -> object.getFields()
                        .forEach(field -> {
                            if (Objects.equals(field.getTypeName(), object.getName()) && !field.isResolver()) {
                                addErrorMessage("Self reference must have splitQuery, field \"" + field.getName() + "\" in object \"" + object.getName() + "\"");
                            }
                        })
                );
    }

    private void validateNotUsingBothExternalFieldAndField() {
        schema.getObjects().values()
                .forEach(object -> object.getFields().stream()
                        .filter(it -> it.isExternalField() && it.hasFieldDirective())
                        .forEach(it -> addErrorMessage(
                                        "Field " + it.getName() + " in type " + it.getContainerTypeName() + " cannot have both the field and externalField directives."
                                )
                        )
                );
    }

    private void validateExternalField() {
        this.schema.getObjects().values()
                .forEach(object -> object.getFields().stream()
                        .filter(GenerationSourceField::isExternalField)
                        .forEach(field -> {
                            String typeName = field.getContainerTypeName();
                            JOOQMapping table = schema.getObject(typeName).getTable();

                            if (table == null) {
                                addErrorMessage("No table found for field " + field.getName() + "in type " + typeName);
                                return;
                            }

                            Set<String> referenceImports = GeneratorConfig.getExternalReferenceImports();
                            List<Method> methods = referenceImports.stream()
                                    .map(it -> getMethodFromReference(it, table.getName(), field.getName()))
                                    .flatMap(Optional::stream)
                                    .toList();

                            if (methods.isEmpty()) {
                                addErrorMessage("No method found for field " + field.getName() + "in type " + typeName);
                                return;
                            }

                            if (methods.size() > 1) {
                                addErrorMessage("Multiple methods found for field " + field.getName() + "in type " + typeName);
                                return;
                            }

                            Method method = methods.get(0);

                            if (!method.getReturnType().equals(Field.class)) {
                                addErrorMessage("Return type of method needs to be generic type Field for field " + field.getName() + "in type " + typeName);
                            }

                            Type type = method.getGenericReturnType();

                            if (type instanceof ParameterizedType paramType) {
                                Type actualType = paramType.getActualTypeArguments()[0];

                                if (!actualType.getTypeName().equals(field.getTypeClass().toString())) {
                                    addErrorMessage("Type parameter of generic type Field in method needs to match scalar type of field " + field.getName() + "in type " + typeName);
                                }
                            }
                        })
                );
    }

    private void validateSplitQueryFieldsInJavaRecords() {
        allFields.stream()
                .filter(schema::hasJavaRecord)
                .map(schema::getObject)
                .map(AbstractObjectDefinition::getFields)
                .flatMap(Collection::stream)
                .filter(GenerationSourceField::isResolver)
                .forEach(field -> {
                    var errorMessageStart = String.format("%s in a java record has %s directive, but",
                            field.formatPath(),
                            SPLIT_QUERY.getName()
                    );

                    if (!schema.isObject(field) || !schema.getObject(field.getTypeName()).hasTable()) {
                        addErrorMessage(errorMessageStart + " does not return a type with table. This is not supported.");
                    }

                    if (field.hasForwardPagination()) {
                        addErrorMessage(errorMessageStart + " is paginated. This is not supported.");
                    }
                });
    }

    private void validateImplicitNodeTypeForInputFields() {
        if (!GeneratorConfig.shouldMakeNodeStrategy()) return;
        schema.getInputTypes().values()
                .stream()
                .filter(RecordObjectDefinition::hasTable)
                .flatMap(it -> it.getFields().stream())
                .filter(it -> it.isID() && !it.hasNodeID() && it.getName().equals(GraphQLReservedName.NODE_ID.getName()))
                .forEach(it -> {
                    var implicitNodeTypes = schema.getNodeTypesWithTable(schema.getRecordType(it.getContainerTypeName()).getTable());
                    if (implicitNodeTypes != null && implicitNodeTypes.size() > 1) {
                        addWarningMessage("Input type '%s' has an '%s' field " +
                                        "that may represent a node ID. " +
                                        "However, the node type cannot be " +
                                        "automatically determined because " +
                                        "multiple node types " +
                                        "(%s) have the same table as the " +
                                        "input type. To enable node ID " +
                                        "resolution, specify @nodeId" +
                                        "(typeName: \"\") on the field.",
                                it.getContainerTypeName(),
                                it.getName(),
                                implicitNodeTypes.stream()
                                        .map(type -> String.format("'%s'",
                                                type.getName()))
                                        .collect(Collectors.joining(", ")));
                    }
                });
    }
}
