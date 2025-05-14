package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.objects.*;
import no.sikt.graphitron.definitions.sql.SQLCondition;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.naming.GraphQLReservedName;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.Validate;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.jetbrains.annotations.NotNull;
import org.jooq.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphql.directives.GenerationDirective.*;
import static no.sikt.graphql.naming.GraphQLReservedName.ERROR_TYPE;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Class for producing warnings related to potential issues in the defined schema.
 * This is only used before running generation, but generally does not prohibit further execution.
 * The intention is that the warnings should provide information on potential issues should an issue occur later.
 */
public class ProcessedDefinitionsValidator {
    protected final ProcessedSchema schema;
    private final List<ObjectField> allFields;
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLGenerator.class);
    private final Set<String> warningMessages = new LinkedHashSet<>();
    private final Set<String> errorMessages = new LinkedHashSet<>();
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

        validateTablesAndKeys();
        validateRequiredMethodCalls();
        validateUnionFieldsTable();
        validateInterfaceDefinitions();
        validateInterfacesReturnedInFields();
        validateTypesUsingNodeInterface();
        validateInputFields();
        validateExternalMappingReferences();
        validateMutationRequiredFields();
        validateMutationRecursiveRecordInputs();
        validateSelfReferenceHasSplitQuery();
        validateNotUsingBothExternalFieldAndField();
        validateExternalField(schema);

        if (!warningMessages.isEmpty()) {
            LOGGER.warn("Problems have been found that MAY prevent code generation:\n{}", String.join("\n", warningMessages));
        }

        if (!errorMessages.isEmpty()) {
            throw new IllegalArgumentException("Problems have been found that prevent code generation:\n" + String.join("\n", errorMessages));
        }
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
                .forEach(it -> errorMessages.add(String.format("No table with name \"%s\" found.", it)));

        var allReferences = recordTypes
                .values()
                .stream()
                .flatMap(it -> it.getFields().stream())
                .flatMap(it -> it.isInput() ? Stream.of(it) : Stream.concat(Stream.of(it), ((ObjectField) it).getArguments().stream()))
                .flatMap(it -> it.getFieldReferences().stream())
                .collect(Collectors.toList());

        allReferences
                .stream()
                .filter(FieldReference::hasTable)
                .map(FieldReference::getTable)
                .map(JOOQMapping::getMappingName)
                .filter(it -> !tableExists(it))
                .forEach(it -> errorMessages.add(String.format("No table with name \"%s\" found.", it)));

        allReferences
                .stream()
                .filter(FieldReference::hasKey)
                .map(FieldReference::getKey)
                .map(JOOQMapping::getMappingName)
                .filter(it -> !keyExists(it))
                .forEach(it -> errorMessages.add(String.format("No key with name \"%s\" found.", it)));


        recordTypes
                .values()
                .stream()
                .flatMap(it -> it.getFields().stream())
                .filter(field -> recordTypes.containsKey(field.getTypeName()))
                .filter(field -> recordTypes.get(field.getTypeName()).hasTable() || field.hasFieldReferences() || field.isResolver())
                .filter(field -> schema.hasTableObjectForObject(recordTypes.get(field.getContainerTypeName())))
                .filter(field -> schema.hasTableObjectForObject(recordTypes.get(field.getTypeName())))
                .forEach((field) -> {
                            var targetTable = schema.getPreviousTableObjectForObject(recordTypes.get(field.getTypeName())).getTable().getName();
                            var sourceTable = schema.getPreviousTableObjectForObject(recordTypes.get(field.getContainerTypeName())).getTable().getName();
                            validateReferencePath(field, sourceTable, targetTable);
                        }
                );
    }

    private void validateReferencePath(GenerationField field, String sourceTable, String targetTable) {
        if (sourceTable.equals(targetTable) && !field.isResolver() && !field.hasFieldReferences()) { return; }
        for(FieldReference fieldReference : field.getFieldReferences()) {
            if (fieldReference.hasTableCondition() && !fieldReference.hasKey()){
                return;
            } else if (fieldReference.hasKey()) {
                String nextTable = "";
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
            }
            else if (fieldReference.hasTable()) {
                String nextTable = fieldReference.getTable().getName();
                if (getNumberOfForeignKeysBetweenTables(sourceTable, nextTable) > 1) {
                    errorMessages.add(String.format(
                            "Error on field \"%s\" in type \"%s\": Multiple foreign keys found between tables \"%s\" and \"%s\". Please specify which key to use in the @reference directive instead."
                            , field.getName(), field.getContainerTypeName(), sourceTable, nextTable
                    ));
                    return;
                }
                if (getNumberOfForeignKeysBetweenTables(sourceTable, nextTable) == 0) {
                    errorMessages.add(String.format(
                            "\"Error on field \"%s\" in type \"%s\": No foreign key found between tables \"%s\" and \"%s\". Please specify a valid path with the @reference directive."
                            , field.getName(), field.getContainerTypeName(), sourceTable, nextTable
                    ));
                    return;
                }
                if (nextTable.equals(targetTable)) {
                    return;
                } else {
                    sourceTable = nextTable;
                }
            }
        }

        if (getNumberOfForeignKeysBetweenTables(sourceTable, targetTable) > 1) {
            errorMessages.add(String.format("Error on field \"%s\" in type \"%s\": Multiple foreign keys found between tables \"%s\" and \"%s\". Please specify which key to use with the @reference directive.", field.getName(), field.getContainerTypeName(), sourceTable, targetTable));
        } else if (getNumberOfForeignKeysBetweenTables(sourceTable, targetTable) == 0) {
            errorMessages.add(String.format("Error on field \"%s\" in type \"%s\": No foreign key found between tables \"%s\" and \"%s\". Please specify path with the @reference directive.", field.getName(), field.getContainerTypeName(), sourceTable, targetTable));
        }
    }

    private void validateRequiredMethodCalls() {
        getUsedTablesWithRequiredMethods()
                .entrySet()
                .stream()
                .filter(it -> tableOrKeyExists(it.getKey()))
                .forEach(entry -> {
                    var tableName = entry.getKey();
                    var allFieldNames = getJavaFieldNamesForTable(tableName);
                    var nonFieldElements = entry.getValue().stream().filter(it -> !allFieldNames.contains(it)).collect(Collectors.toList());
                    var missingElements = nonFieldElements
                            .stream()
                            .filter(it -> searchTableForMethodWithName(tableName, it).isEmpty())
                            .collect(Collectors.toList());

                    if (!missingElements.isEmpty()) {
                        warningMessages.add(String.format("No field(s) or method(s) with name(s) '%s' found in table '%s'",
                                missingElements.stream().sorted().collect(Collectors.joining(", ")), tableName));
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
                .forEach(e -> errorMessages.add(String.format("No enum with name '%s' found.", e.getSchemaClassReference())));

        allFields
                .stream()
                .filter(ObjectField::hasServiceReference)
                .map(ObjectField::getServiceReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> errorMessages.add(String.format("No service with name '%s' found.", e.getSchemaClassReference())));

        allFields
                .stream()
                .filter(ObjectField::hasCondition)
                .map(ObjectField::getCondition)
                .map(SQLCondition::getConditionReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> errorMessages.add(String.format("No condition with name '%s' found.", e.getSchemaClassReference())));
    }

    private void validateUnionFieldsTable() {
        if (schema.getObjects().containsKey("Query")) {
            for (ObjectField field : schema.getObject("Query").getFields()) {
                if (field.getName().equals(GraphQLReservedName.FEDERATION_ENTITIES_FIELD.getName())) { continue; }

                if (schema.isUnion(field)) {
                    for (ObjectDefinition subType : schema.getUnionSubTypes(field.getTypeName())) {
                        if (!subType.hasTable()) {
                            errorMessages.add(String.format("Type %s in Union '%s' in Query has no table.", subType.getName(), schema.getUnion(field).getName()));
                        }
                    }
                }
            }
        }
    }

    private void validateInterfaceDefinitions() {
        schema.getInterfaces().forEach((name, interfaceDefinition) -> {
                    if (name.equalsIgnoreCase(NODE_TYPE.getName()) || name.equalsIgnoreCase(ERROR_TYPE.getName())) return;

                    var implementations = schema.getObjects()
                            .values()
                            .stream()
                            .filter(it -> it.implementsInterface(schema.getInterface(name).getName())).toList();

                    if (interfaceDefinition.hasDiscriminator() == interfaceDefinition.isMultiTableInterface()) {
                        errorMessages.add(
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
                            errorMessages.add(
                                    String.format("Interface '%s' has discriminating field set as '%s', but the field " +
                                                    "does not exist in table '%s'.",
                                            name, interfaceDefinition.getDiscriminatorFieldName(), interfaceDefinition.getTable().getName()));
                        } else {
                            Optional<Class<?>> fieldType = getFieldType(
                                    interfaceDefinition.getTable().getName(),
                                    interfaceDefinition.getDiscriminatorFieldName()
                            );
                            if (fieldType.isEmpty() || !fieldType.get().equals(String.class)) {
                                errorMessages.add(
                                        String.format("Interface '%s' has discriminating field set as '%s', but the field " +
                                                        "does not return a string type, which is not supported.",
                                                name, interfaceDefinition.getDiscriminatorFieldName(), interfaceDefinition.getTable().getName()));
                            }
                        }

                        implementations.forEach(impl -> {
                            if (!impl.hasDiscriminator()) {
                                errorMessages.add(
                                        String.format("Type '%s' is missing '%s' directive in order to implement interface '%s'.",
                                                impl.getName(), DISCRIMINATOR.getName(), name));
                            }
                            if (impl.hasTable() && interfaceDefinition.hasTable() && !impl.getTable().equals(interfaceDefinition.getTable())) {
                                errorMessages.add(
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

                                        if (!fieldInInterface.getUpperCaseName().equals(it.getUpperCaseName())) {
                                            errorMessages.add(String.format(
                                                    sharedErrorMessage,
                                                    FIELD.getName(), impl.getName(), it.getName(), name
                                            ));
                                        }

                                        if (it.hasCondition() != fieldInInterface.hasCondition()
                                                || (it.hasCondition() && !it.getCondition().equals(fieldInInterface.getCondition()))) {
                                            errorMessages.add(String.format(
                                                    sharedErrorMessage,
                                                    CONDITION.getName(), impl.getName(), it.getName(), name
                                            ));
                                        }

                                        if (!it.getFieldReferences().equals(fieldInInterface.getFieldReferences())) {
                                            errorMessages.add(String.format(
                                                    sharedErrorMessage,
                                                    REFERENCE.getName(), impl.getName(), it.getName(), name
                                            ));
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
                                            errorMessages.add(String.format(
                                                    sharedErrorMessage,
                                                    entry.getKey(), interfaceDefinition.getName(), FIELD.getName()
                                            ));
                                        }

                                        if (!field.getFieldReferences().equals(first.getFieldReferences())) {
                                            errorMessages.add(String.format(
                                                    sharedErrorMessage,
                                                    entry.getKey(), interfaceDefinition.getName(), REFERENCE.getName()));
                                        }

                                        if (!(field.hasCondition() == first.hasCondition()
                                                && (!field.hasCondition() || field.getCondition().equals(first.getCondition())))) {
                                            errorMessages.add(String.format(
                                                    sharedErrorMessage,
                                                    entry.getKey(), interfaceDefinition.getName(), CONDITION.getName()));
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
                            .filter(it -> schema.getInterface(it).hasDiscriminator())
                            .toList();

                    if (singleTableInterfaces.isEmpty() && objectDefinition.hasDiscriminator()) {
                        errorMessages.add(
                                String.format("Type '%s' has discriminator, but doesn't implement any interfaces requiring it.", objectDefinition.getName())
                        );
                    }
                });
    }




    private void validateInterfacesReturnedInFields() {
        for (var field : allFields.stream().filter(ObjectField::isGeneratedWithResolver).collect(Collectors.toList())) {
            var typeName = field.getTypeName();
            var name = Optional
                    .ofNullable(schema.getObjectOrConnectionNode(typeName))
                    .map(AbstractObjectDefinition::getName)
                    .orElse(typeName);

            if (schema.isInterface(name)) {
                if (!(field.isRootField())) {
                    errorMessages.add(String.format("interface (%s) returned in non root object. This is not fully " +
                            "supported. Use with care", name));
                }

                if (name.equalsIgnoreCase(NODE_TYPE.getName())) {
                    Validate.isTrue(
                            field.getArguments().size() == 1,
                            "Only exactly one input field is currently supported for fields returning interfaces. " +
                                    "'%s' has %s input fields", field.getName(), field.getArguments().size()
                    );
                    Validate.isTrue(
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
                                    errorMessages.add(String.format("Interface '%s' is returned in field '%s', but type '%s' " +
                                            "implementing '%s' does not have table set. This is not supported.", name, field.getName(), implementation.getName(), name));
                                } else if (!tableHasPrimaryKey(implementation.getTable().getName())) {
                                    errorMessages.add(String.format("Interface '%s' is returned in field '%s', but implementing type '%s' " +
                                            "has table '%s' which does not have a primary key. This is not supported.", name, field.getName(), implementation.getName(), implementation.getTable().getName()));
                                }
                            });
                }
            }
        }
    }

    private void validateTypesUsingNodeInterface() {
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
                .filter(it -> it.implementsInterface(NODE_TYPE.getName()) && it.hasTable())
                .collect(groupingBy(
                        it -> it.getTable().getName(), Collectors.mapping(ObjectDefinition::getName, Collectors.toSet())));

        records.forEach((tablename, schematypes) -> {
            if (schematypes.size() > 1) {
                errorMessages.add(String.format(
                        "Multiple types (%s) implement the %s interface and refer to the same table %s. This is not supported.",
                        String.join(", ", schematypes), NODE_TYPE.getName(), tablename));
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
                .collect(Collectors.toList());

        for (var field : oneLayerFlattenedFields) {
            var messageStart = String.format("Argument '%s' is a collection of InputFields ('%s') type.", field.getName(), field.getTypeName());
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
                errorMessages.add(
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
        var errorMessages = schema
                .getRecordTypes()
                .values()
                .stream()
                .map(this::validateFieldTypes)
                .filter(it -> !it.isEmpty())
                .collect(Collectors.joining("\n\n"));
        if (!errorMessages.isEmpty()) {
            throw new IllegalArgumentException("Problems have been found that prevent code generation:\n" + errorMessages);
        }
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
            var hasConnectionSuffix = field.getTypeName().endsWith(GraphQLReservedName.SCHEMA_CONNECTION_SUFFIX.getName());
            if (hasConnectionSuffix && !field.hasRequiredPaginationFields()) {
                errorMessages.add(
                        String.format("Type %s ending with the reserved suffix 'Connection' must have either " +
                                "forward(first and after fields) or backwards(last and before fields) pagination, " +
                                "yet neither was found.", field.getTypeName()
                        )
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
                    .filter(ObjectField::isGeneratedWithResolver)
                    .filter(ObjectField::hasMutationType)
                    .forEach(target -> {
                        validateRecordRequiredFields(target);
                        new InputParser(target, schema).getJOOQRecords().values().forEach(inputField -> checkMutationIOFields(inputField, target));
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
            errorMessages.add(
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

        var objectFieldErrors = new InputParser(objectField, schema).getAllErrors();
        var payloadContainsIterableField = objectField.isIterableWrapped() ||
                schema.getObject(objectField)
                        .getFields()
                        .stream()
                        .filter(field -> !objectFieldErrors.contains(field))
                        .anyMatch(AbstractField::isIterableWrapped);

        if (!inputField.isIterableWrapped() && payloadContainsIterableField) {
            warningMessages.add(
                    String.format("Mutation %s with Input %s is not defined as a list while Payload type %s contains " +
                                    "a list",
                            objectField.getName(), inputField.getTypeName(), objectField.getTypeName()));
        } else if (inputField.isIterableWrapped() && !payloadContainsIterableField) {
            warningMessages.add(
                    String.format("Mutation %s with Input %s is defined as a list while Payload type %s does not " +
                                    "contain a list",
                            objectField.getName(), inputField.getTypeName(), objectField.getTypeName()));
        }
    }

    private void validateRecordRequiredFields(ObjectField target) {
        var mutationType = target.getMutationType();
        if (mutationType.equals(MutationType.INSERT) || mutationType.equals(MutationType.UPSERT)) {
            var recordInputs = new InputParser(target, schema).getJOOQRecords().values();
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
            warningMessages.add(
                    String.format(
                            message,
                            recordInput.getTypeName(),
                            schema.getInputType(recordInput).getTable().getMappingName(),
                            missingFields
                    )
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
                                errorMessages.add("Self reference must have splitQuery, field \"" + field.getName() + "\" in object \"" + object.getName() + "\"");
                            }
                        })
                );
    }

    private void validateNotUsingBothExternalFieldAndField() {
        schema.getObjects().values()
                .forEach(object -> object.getFields().stream()
                        .filter(it -> it.isExternalField() && it.hasFieldDirective())
                        .forEach(it -> errorMessages.add(
                                        "Field " + it.getName() + " in type " + it.getContainerTypeName() + " cannot have both the field and externalField directives."
                                )
                        )
                );
    }

    private void validateExternalField(ProcessedSchema schema) {
        this.schema.getObjects().values()
                .forEach(object -> object.getFields().stream()
                        .filter(GenerationSourceField::isExternalField)
                        .forEach(field -> {
                            String typeName = field.getContainerTypeName();
                            JOOQMapping table = schema.getObject(typeName).getTable();

                            if (table == null) {
                                errorMessages.add("No table found for field " + field.getName() + "in type " + typeName);
                                return;
                            }

                            Set<String> referenceImports = GeneratorConfig.getExternalReferenceImports();
                            List<Method> methods = referenceImports.stream()
                                    .map(it -> getMethodFromReference(it, table.getName(), field.getName()))
                                    .flatMap(Optional::stream)
                                    .toList();

                            if (methods.isEmpty()) {
                                errorMessages.add("No method found for field " + field.getName() + "in type " + typeName);
                                return;
                            }

                            if (methods.size() > 1) {
                                errorMessages.add("Multiple methods found for field " + field.getName() + "in type " + typeName);
                                return;
                            }

                            Method method = methods.get(0);

                            if (!method.getReturnType().equals(Field.class)) {
                                errorMessages.add("Return type of method needs to be generic type Field for field " + field.getName() + "in type " + typeName);
                            }

                            Type type = method.getGenericReturnType();

                            if (type instanceof ParameterizedType paramType) {
                                Type actualType = paramType.getActualTypeArguments()[0];

                                if(!actualType.getTypeName().equals(field.getTypeClass().toString())) {
                                    errorMessages.add("Type parameter of generic type Field in method needs to match scalar type of field " + field.getName() + "in type " + typeName);
                                }
                            }
                        })
                );
    }
}
