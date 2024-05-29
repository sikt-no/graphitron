package no.fellesstudentsystem.graphitron.validation;

import no.fellesstudentsystem.graphitron.configuration.ExceptionToErrorMapping;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.fields.*;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.AbstractObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.RecordObjectDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.EnumDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ExceptionDefinition;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLCondition;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphitron.mojo.GraphQLGenerator;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.Validate;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;

/**
 * Class for producing warnings related to potential issues in the defined schema.
 * This is only used before running generation, but generally does not prohibit further execution.
 * The intention is that the warnings should provide information on potential issues should an issue occur later.
 */
public class ProcessedDefinitionsValidator {
    protected final ProcessedSchema schema;
    private final List<ObjectField> allFields;
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLGenerator.class);
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
     * Validate the various mappings set in the schema towards jOOQ tables and keys.
     */
    public void validateThatProcessedDefinitionsConformToJOOQNaming() {
        schema.getObjects().values().forEach(it -> checkPaginationSpecs(it.getFields()));

        validateTableAndFieldUsage();
        validateInterfaces();
        validateInputFields();
        validateExternalMappingReferences();
        validateMutationRequiredFields();
        validateMutationRecursiveRecordInputs();
        validateSelfReferenceHasSplitQuery();

        if (!GeneratorConfig.getExceptionToErrorMappings().isEmpty() && schema.getMutationType() != null) {
            validateExceptionToErrorMappings(GeneratorConfig.getExceptionToErrorMappings());
        }
    }

    private void validateTableAndFieldUsage() {
        var tableMethodsRequired = getUsedTablesWithRequiredMethods();
        tableMethodsRequired
                .keySet()
                .stream()
                .filter(it -> !TableReflection.tableOrKeyExists(it))
                .forEach(it ->
                        LOGGER.warn(
                                "No table or key with name '{}' found in {} or {}",
                                it,
                                GeneratorConfig.getGeneratedJooqTablesClass().getName(),
                                GeneratorConfig.getGeneratedJooqKeysClass().getName()
                        )
                );

        tableMethodsRequired
                .entrySet()
                .stream()
                .filter(it -> TableReflection.tableOrKeyExists(it.getKey()))
                .forEach(entry -> {
                    var tableName = entry.getKey();
                    var allFieldNames = TableReflection.getFieldNamesForTable(tableName);
                    var nonFieldElements = entry.getValue().stream().filter(it -> !allFieldNames.contains(it)).collect(Collectors.toList());
                    var missingElements = nonFieldElements
                            .stream()
                            .filter(it -> TableReflection.searchTableForMethodWithName(tableName, it).isEmpty())
                            .collect(Collectors.toList());

                    if (!missingElements.isEmpty()) {
                        LOGGER.warn("No field(s) or method(s) with name(s) '{}' found in table '{}'", missingElements.stream().sorted().collect(Collectors.joining(", ")), tableName);
                    }
                });
    }

    @NotNull
    private HashMap<String, HashSet<String>> getUsedTablesWithRequiredMethods() {
        var tableMethodsRequired = new HashMap<String, HashSet<String>>();
        schema.getObjects().values().stream().filter(it -> it.hasTable() || it.isRoot()).forEach(object -> {
            var flattenedFields = flattenObjectFields(object.isRoot() ? null : object.getTable().getMappingName(), object.getFields());
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
            if (schema.isObject(field) && !schema.isInterface(field)) {
                var table = lastTableIsNonNull
                        ? lastTable
                        : (schema.isTableObject(field) ? schema.getObjectOrConnectionNode(field).getTable().getMappingName() : null);
                var object = schema.getObjectOrConnectionNode(field);
                if (!object.hasTable()) {
                    flattenObjectFields(table, object.getFields()).forEach((key, value) -> flatFields.computeIfAbsent(key, k -> new ArrayList<>()).addAll(value));
                }
            }

            var hasCondition = field.hasOverridingCondition();
            if (field.hasNonReservedInputFields() && field.isGenerated()) {
                var targetTable = schema.isTableObject(field) ? schema.getObjectOrConnectionNode(field).getTable().getMappingName() : lastTable;
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
                var fieldIsTableObject = schema.isTableObject(field);
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
                if (!TableReflection.keyExists(key.getMappingName())) {
                    requiredJOOQTypesAndMethods.put(key.getMappingName(), new HashSet<>());
                }
                var nextTable = reference.hasTable()
                        ? reference.getTable().getMappingName()
                        : (schema.isTableType(field) ? schema.getObjectOrConnectionNode(field).getTable().getMappingName() : lastTable);
                var reverseKeyFound = TableReflection.searchTableForMethodWithName(nextTable, key.getMappingName()).isPresent(); // In joins the key can also be used in reverse.
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
                .map(CodeReference::getSchemaClassReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> LOGGER.warn("No enum with name '{}' found.", e));

        allFields
                .stream()
                .filter(ObjectField::hasServiceReference)
                .map(ObjectField::getServiceReference)
                .map(CodeReference::getSchemaClassReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> LOGGER.warn("No service with name '{}' found.", e));

        allFields
                .stream()
                .filter(ObjectField::hasCondition)
                .map(ObjectField::getCondition)
                .map(SQLCondition::getConditionReference)
                .map(CodeReference::getSchemaClassReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> LOGGER.warn("No condition with name '{}' found.", e));

        schema
                .getExceptions()
                .values()
                .stream()
                .filter(ExceptionDefinition::hasExceptionReference)
                .map(ExceptionDefinition::getExceptionReference)
                .map(CodeReference::getSchemaClassReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> LOGGER.warn("No exception with name '{}' found.", e));
    }

    private void validateInterfaces() {
        for (var field : allFields.stream().filter(ObjectField::isGenerated).collect(Collectors.toList())) {
            var typeName = field.getTypeName();
            var name = Optional
                    .ofNullable(schema.getObjectOrConnectionNode(typeName))
                    .map(AbstractObjectDefinition::getName)
                    .orElse(typeName);

            if (schema.isInterface(name)) {
                Validate.isTrue(
                        field.getArguments().size() == 1,
                        "Only exactly one input field is currently supported for fields returning interfaces. " +
                                "'%s' has %s input fields", field.getName(), field.getArguments().size()
                );
                Validate.isTrue(
                        !field.isIterableWrapped(),
                        "Generating fields returning collections/lists of interfaces is not supported. " +
                                "'%s' must return only one %s", field.getName(), field.getTypeName()
                );
                if (!(field.isRootField())) {
                    LOGGER.warn("interface ({}) returned in non root object. This is not fully supported. Use with care", name);
                }
            }
        }
    }

    private void validateInputFields() {
        var oneLayerFlattenedFields = allFields
                .stream()
                .filter(schema::isTableObject)
                .flatMap(it -> it.getNonReservedArguments().stream())
                .filter(AbstractField::isIterableWrapped)
                .filter(schema::isInputType)
                .collect(Collectors.toList());
        for (var field : oneLayerFlattenedFields) {
            var messageStart = String.format("Argument '%s' is of collection of InputFields ('%s') type.", field.getName(), field.getTypeName());

            var inputDefinitionFields = schema.getInputType(field).getFields();
            inputDefinitionFields.stream().filter(AbstractField::isIterableWrapped).findFirst().ifPresent(it -> {
                throw new IllegalArgumentException(
                        String.format(
                                "%s Fields returning collections: '%s' are not supported on such types (used for generating condition tuples)",
                                messageStart,
                                it.getName()
                        )
                );
            });

            var optionalFields = inputDefinitionFields
                    .stream()
                    .filter(AbstractField::isNullable)
                    .map(AbstractField::getName)
                    .collect(Collectors.toList());
            if (!optionalFields.isEmpty()) {
                LOGGER.warn(
                        "{} Optional fields on such types are not supported. The following fields will be treated as mandatory in the resulting, generated condition tuple: '{}'",
                        messageStart,
                        String.join("', '", optionalFields)
                );
            }
        }
    }

    public void validateObjectFieldTypes() {
        var errorMessages = schema
                .getTableTypes()
                .values()
                .stream()
                .map(this::validateFieldTypes)
                .filter(it -> !it.isEmpty())
                .collect(Collectors.joining("\n\n"));
        if (!errorMessages.isEmpty()) {
            throw new IllegalArgumentException("Problems have been found that prevent code generation:\n" + errorMessages);
        }
    }

    private String validateFieldTypes(RecordObjectDefinition<?, ? extends GenerationField> object) {
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
            var hasConnectionSuffix = field.getTypeName().endsWith(GraphQLReservedName.SCHEMA_CONNECTION_SUFFIX.getName());
            if (hasConnectionSuffix && !field.hasRequiredPaginationFields()) {
                LOGGER.warn("Type {} ending with the reserved suffix 'Connection' must have either " +
                        "forward(first and after fields) or backwards(last and before fields) pagination, " +
                        "yet neither was found. No pagination was generated for this type.", field.getTypeName()
                );
            }
        }
    }

    private void validateMutationRequiredFields() {
        var mutation = schema.getMutationType();
        if (mutation != null) {

            mutation
                    .getFields()
                    .stream()
                    .filter(ObjectField::isGenerated)
                    .filter(ObjectField::hasMutationType)
                    .forEach(target -> {
                        validateRecordRequiredFields(target);
                        new UpdateContext(target, schema).getTableInputs().values().forEach(inputField -> checkMutationIOFields(inputField, target));
                    });
        }
    }

    private void validateMutationRecursiveRecordInputs() {
        var mutation = schema.getMutationType();
        if (mutation != null) {
            mutation
                    .getFields()
                    .stream()
                    .filter(ObjectField::hasServiceReference)
                    .flatMap(it -> it.getArguments().stream())
                    .filter(schema::isInputType)
                    .forEach(it -> validateMutationRecursiveRecordInputs(it, false, 0));
        }
    }

    private void validateMutationRecursiveRecordInputs(InputField field, boolean wasRecord, int recursion) {
        recursionCheck(recursion);

        var input = schema.getInputType(field);
        if (input == null) {
            return;
        }

        var hasTableOrRecordReference = input.hasTable() || input.hasJavaRecordReference();

        if (field.isIterableWrapped() && wasRecord && !hasTableOrRecordReference) {
            LOGGER.warn(
                    String.format(
                            "Field %s with Input type %s is iterable, but has no record mapping set. Iterable Input types within records without record mapping can not be mapped to a single field in the surrounding record.",
                            field.getName(),
                            input.getName()
                    )
            );
        }

        input.getFields().forEach(it -> validateMutationRecursiveRecordInputs(it, wasRecord || hasTableOrRecordReference, recursion + 1));
    }

    //Check input and payload("output") fields
    private void checkMutationIOFields(InputField inputField, ObjectField objectField) {
        if(!schema.isObject(objectField))
            return;

        var objectFieldErrors = new UpdateContext(objectField, schema).getAllErrors();
        var payloadContainsIterableField = objectField.isIterableWrapped() ||
                schema.getObject(objectField)
                        .getFields()
                        .stream()
                        .filter(field -> !objectFieldErrors.contains(field))
                        .anyMatch(AbstractField::isIterableWrapped);

        if (!inputField.isIterableWrapped() && payloadContainsIterableField) {
            LOGGER.warn(
                    String.format("Mutation %s with Input %s is not defined as a list while Payload type %s contains a list",
                            objectField.getName(), inputField.getTypeName(), objectField.getTypeName()));
        } else if (inputField.isIterableWrapped() && !payloadContainsIterableField) {
            LOGGER.warn(
                    String.format("Mutation %s with Input %s is defined as a list while Payload type %s does not contain a list",
                            objectField.getName(), inputField.getTypeName(), objectField.getTypeName()));
        }
    }

    private void validateRecordRequiredFields(ObjectField target) {
        var mutationType = target.getMutationType();
        if (mutationType.equals(MutationType.INSERT) || mutationType.equals(MutationType.UPSERT)) {
            var recordInputs = new UpdateContext(target, schema).getTableInputs().values();
            if (recordInputs.isEmpty()) {
                throw new IllegalArgumentException(
                        "Mutation "
                                + target.getName()
                                + " is set as an insert operation, but does not link any input to tables."
                );
            }

            recordInputs.forEach(this::checkRequiredFields);
        }
    }

    protected void checkRequiredFields(InputField recordInput) {
        var inputObject = schema.getInputType(recordInput);
        var tableName = inputObject.getTable().getMappingName();

        var requiredDBFields = TableReflection.getRequiredFields(tableName)
                .stream()
                .map(String::toUpperCase)
                .filter(it -> !TableReflection.tableFieldHasDefaultValue(tableName, it)) // No need to complain when it has a default set. Note that this does not work for views.
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
            LOGGER.warn(
                    String.format(
                            message,
                            recordInput.getTypeName(),
                            schema.getInputType(recordInput).getTable().getMappingName(),
                            missingFields
                    )
            );
        }
    }

    private void validateExceptionToErrorMappings(List<ExceptionToErrorMapping> exceptionToErrorMappings) {
        var mutationFields = schema.getMutationType().getFields();
        var mutationNames = mutationFields.stream()
                .map(AbstractField::getName)
                .collect(Collectors.toList());

        exceptionToErrorMappings.forEach(mapping -> {
            var mutationName = mapping.getMutationName();
            Validate.isTrue(mutationNames.contains(mutationName),
                    "Mutation '%s' defined in exceptionToErrorMappings is not found in the GraphQL schema.", mutationName);

            var objectField = mutationFields.stream()
                    .filter(it -> it.getName().equals(mutationName))
                    .findFirst()
                    .orElseThrow();

            List<String> errorTypes = new UpdateContext(objectField, schema)
                    .getAllErrors()
                    .stream()
                    .map(AbstractField::getTypeName)
                    .collect(Collectors.toList());

            validateExceptionToErrorMappingErrorType(mapping, errorTypes);
        });
    }

    private void validateExceptionToErrorMappingErrorType(ExceptionToErrorMapping mapping, List<String> errorTypes) {
        var errorTypeName = mapping.getErrorTypeName();
        for (String type : errorTypes) {

            Validate.isTrue(schema.isUnion(type)
                            ? schema.getUnion(type).getFieldTypeNames().contains(errorTypeName)
                            : errorTypes.contains(errorTypeName),
                    "Mutation '%s' does not return any errors of type '%s' as defined in exceptionToErrorMappings",
                    mapping.getMutationName(), errorTypeName);
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
        schema.getObjects().values().forEach(object -> {
            object.getFields().forEach(field -> {
                if (Objects.equals(field.getTypeName(), object.getName()) && !field.isResolver()) {
                    throw new IllegalArgumentException("Self reference must have splitQuery, field \""+ field.getName() + "\" in object \"" + object.getName() + "\"");
                }

            });
        });
    }
}
