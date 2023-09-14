package no.fellesstudentsystem.graphitron.validation;

import graphql.com.google.common.collect.Sets;
import no.fellesstudentsystem.graphitron.mojo.GraphQLGenerator;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.*;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphitron.definitions.objects.*;
import no.fellesstudentsystem.graphitron.generators.context.UpdateContext;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.mappings.PersonHack.asHackedIDFields;
import static no.fellesstudentsystem.graphitron.mappings.PersonHack.getHackedIDFields;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.getRequiredFields;
import static no.fellesstudentsystem.graphitron.mappings.TableReflection.tableFieldHasDefaultValue;

public class ProcessedDefinitionsValidator {
    private final ProcessedSchema schema;
    static final Logger LOGGER = LoggerFactory.getLogger(GraphQLGenerator.class);
    private static final String
            ERROR_MISSING_FIELD = "Input type %s referencing table %s does not map all fields required by the database. Missing required fields: %s",
            ERROR_MISSING_NON_NULLABLE = "Input type %s referencing table %s does not map all fields required by the database as non-nullable. Nullable required fields: %s";

    public ProcessedDefinitionsValidator(ProcessedSchema schema) {
        this.schema = schema;
    }

    public void validateThatProcessedDefinitionsConformToDatabaseNaming() {
        schema.getObjects().values().forEach(objectDefinition -> {
            var fields = objectDefinition.getFields();
            if (objectDefinition.hasTable()) {
                String tableName = objectDefinition.getTable().getName();
                Set<String> dbNamesForFieldsThatShouldBeValidated = getDbNamesForFieldsThatShouldBeValidated(
                        fields
                                .stream()
                                .filter(f -> !f.hasImplicitJoin())
                                .collect(Collectors.toList())
                );
                validateTableExistsAndHasMethods(tableName, dbNamesForFieldsThatShouldBeValidated);
            }
            checkPaginationSpecs(fields);
        });
        schema.getObjects().values().forEach(objectDefinition -> objectDefinition
                .getFields()
                .stream()
                .filter(ObjectField::isGenerated)
                .forEach(this::validateGeneratedField)
        );

        Map<String, Set<String>> columnsByTableHavingImplicitJoin = new HashMap<>();
        schema.getObjects().values().forEach(objectDefinition -> objectDefinition
                .getFields()
                .stream()
                .filter(ObjectField::hasImplicitJoin)
                .forEach(objectField -> addColumnForTable(
                        columnsByTableHavingImplicitJoin,
                        objectField.getImplicitJoin().getTable().getName(),
                        objectField.getUpperCaseName())
                )
        );
        columnsByTableHavingImplicitJoin.forEach(this::validateTableExistsAndHasMethods);

        var enumValueSet = GeneratorConfig.getExternalEnums();
        schema.getEnums().values()
                .stream()
                .filter(EnumDefinition::hasDbEnumMapping)
                .map(EnumDefinition::getDbName)
                .filter(e -> !enumValueSet.contains(e.toUpperCase()))
                .forEach(e -> LOGGER.warn("No enum with name '{}' found.", e));

        schema.getInputTypes().values()
                .stream()
                .filter(InputDefinition::hasTable)
                .forEach(inputDefinition -> validateTableExistsAndHasMethods(inputDefinition.getTable().getName(), Set.of()));

        var mutation = schema.getMutationType();
        if (mutation != null) {
            mutation
                    .getFields()
                    .stream()
                    .filter(ObjectField::isGenerated)
                    .filter(ObjectField::hasMutationType)
                    .forEach(this::validateRecordRequiredFields);
        }
    }

    private void validateGeneratedField(ObjectField generatedField) {
        String typeName = generatedField.getTypeName();
        Optional<String> nodeName = Optional.ofNullable(schema.getConnectionObjects().get(typeName))
                .map(ConnectionObjectDefinition::getNodeType);

        String name = nodeName.orElse(typeName);

        var object = schema.getObjects().get(name);
        if (schema.getInterfaces().containsKey(name)) {
            Validate.isTrue(generatedField.getInputFields().size() == 1,
                    "Only exactly one input field is currently supported for fields returning interfaces. " +
                            "'%s' has %s input fields", generatedField.getName(), generatedField.getInputFields().size());
            Validate.isTrue(!generatedField.isIterableWrapped(),
                    "Generating fields returning collections/lists of interfaces is not supported. " +
                            "'%s' must return only one %s", generatedField.getName(), generatedField.getFieldType().getName());
            if (!(generatedField instanceof TopLevelObjectField)) {
                LOGGER.warn("interface ({}) returned in non root object. This is not fully supported. Use with care", name );
            }
        } else if (object != null && object.hasTable()) {
            validateInputFields(generatedField.getNonReservedInputFields(), object.getTable());
        }
    }

    private void validateInputFields(List<InputField> nonReservedInputFields, JOOQTableMapping table) {
        var inputs = schema.getInputTypes();
        nonReservedInputFields.forEach(field -> {
            FieldType fieldType = field.getFieldType();

            if (fieldType.isIterableWrapped()) {
                Optional.ofNullable(inputs.get(fieldType.getName()))
                        .ifPresent(definition -> validateConditionTupleGenerationForField(definition, field));
            }
        });

        var flatInputs = new ArrayList<InputField>();
        var inputBuffer = new LinkedList<>(nonReservedInputFields);
        var seen = new HashSet<String>();
        while (!inputBuffer.isEmpty()) {
            var f = inputBuffer.poll();
            if (inputs.containsKey(f.getTypeName()) && !seen.contains(f.getName() + f.getTypeName())) {
                var definition = inputs.get(f.getTypeName());
                inputBuffer.addAll(definition.getInputs());
                seen.add(f.getName() + f.getTypeName());
            } else {
                flatInputs.add(f);
            }
        }

        Set<String> inputFieldDbNames = flatInputs
                .stream()
                .filter(inputField -> !inputField.getFieldType().isID())
                .filter(inputField -> !inputField.hasImplicitJoin())
                .map(AbstractField::getUpperCaseName)
                .collect(Collectors.toSet());
        validateTableExistsAndHasMethods(table.getName(), inputFieldDbNames);
    }

    private void validateConditionTupleGenerationForField(InputDefinition inputDefinition, InputField inputField) {
        List<String> optionalFields = new ArrayList<>();
        String messageStart = String.format("Argument '%s' is of collection of InputFields ('%s') type.", inputField.getName(), inputField.getFieldType().getName());

        inputDefinition.getInputs().forEach(field -> {
            if (field.getFieldType().isNullable()) {
                optionalFields.add(field.getName());
            }
            if (field.isIterableWrapped()) {
                throw new IllegalArgumentException(String.format("%s Fields returning collections: '%s' are not supported on such types (used for generating condition tuples)", messageStart, field.getName()));
            }
        });

        if (!optionalFields.isEmpty()) {
            LOGGER.warn("{} Optional fields on such types are not supported. The following fields will be treated as mandatory in the resulting, generated condition tuple: '{}'", messageStart, String.join("', '", optionalFields));
        }
    }

    private Set<String> getDbNamesForFieldsThatShouldBeValidated(Collection<ObjectField> objectFields) {
        Set<String> fieldNames = new HashSet<>();
        objectFields.stream().filter(o -> !o.getFieldType().isID()).forEach(objectField ->
                Optional.ofNullable(schema.getObjects().get(objectField.getTypeName())).ifPresentOrElse(
                        objectDefinition -> {
                            if (!objectDefinition.hasTable()) {
                                fieldNames.addAll(getDbNamesForFieldsThatShouldBeValidated(objectDefinition.getFields()));
                            }
                        },
                        () -> {
                            if (!objectField.hasImplicitJoin()) {
                                fieldNames.add(objectField.getUpperCaseName());
                            }
                        }
                ));
        return fieldNames;
    }

    private void validateTableExistsAndHasMethods(String tableName, Set<String> expectedMethodNames) {
        if (!TableReflection.tableExists(tableName)) {
            LOGGER.warn("No table with name '{}' found in {}", tableName, GeneratorConfig.getGeneratedJooqTablesClass().getName());
        } else {
            validateTableHasMethods(tableName, expectedMethodNames);
        }
    }

    public void validateTableHasMethods(String tableName, Set<String> methodNames) {
        var methodsNotFoundInTable = Sets.difference(methodNames, TableReflection.getFieldNamesForTable(tableName));

        if (!methodsNotFoundInTable.isEmpty()) {
            LOGGER.warn("No column(s) with name(s) '{}' found in table '{}'", methodsNotFoundInTable.stream().sorted().collect(Collectors.joining(", ")), tableName);
        }
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

    private void addColumnForTable(Map<String, Set<String>> columnsByTable, String table, String column) {
        Optional.ofNullable(columnsByTable.get(table)).ifPresentOrElse(
                columns -> columns.add(column),
                () -> columnsByTable.put(table, Sets.newHashSet(column))
        );
    }

    private void validateRecordRequiredFields(ObjectField target) {
        var mutationType = target.getMutationType();
        if (mutationType.equals(MutationType.INSERT) || mutationType.equals(MutationType.UPSERT)) {
            var context = new UpdateContext(target, schema);
            var recordInputs = context.getRecordInputs().values();
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

    private void checkRequiredFields(InputField recordInput) {
        var inputObject = schema.getInputType(recordInput);
        var tableName = inputObject.getTable().getName();

        // WARNING: FS-SPECIFIC CODE. This must be generalized at some point.
        var splitFieldsOnIsID = inputObject.getInputs().stream().collect(Collectors.partitioningBy(it -> it.getFieldType().isID()));
        var containedRequiredIDFields = new HashSet<String>();
        var containedOptionalIDFields = new HashSet<String>();
        for (var idField : splitFieldsOnIsID.get(true)) {
            var hackedIDFields = getHackedIDFields(tableName, idField.getRecordMappingName());
            if (hackedIDFields.isPresent()) {
                if (idField.getFieldType().isNullable()) {
                    containedOptionalIDFields.addAll(hackedIDFields.get());
                } else {
                    containedRequiredIDFields.addAll(hackedIDFields.get());
                }
            }
        }

        var hackedRequiredDBFields = asHackedIDFields(getRequiredFields(tableName))
                .stream()
                .filter(it -> !tableFieldHasDefaultValue(tableName, it))
                .collect(Collectors.toList()); // No need to complain when it has a default set. Note that this does not work for views.
        var recordFieldNames = Stream
                .concat(
                        splitFieldsOnIsID.get(false).stream().map(InputField::getUpperCaseName),
                        Stream.concat(containedOptionalIDFields.stream(), containedRequiredIDFields.stream())
                )
                .collect(Collectors.toSet());
        checkRequiredFieldsExist(recordFieldNames, hackedRequiredDBFields, recordInput, ERROR_MISSING_FIELD);

        var requiredRecordFieldNames = Stream
                .concat(
                        containedRequiredIDFields.stream(),
                        splitFieldsOnIsID
                                .get(false)
                                .stream()
                                .filter(it -> it.getFieldType().isNonNullable())
                                .map(InputField::getUpperCaseName)
                )
                .collect(Collectors.toSet());
        checkRequiredFieldsExist(requiredRecordFieldNames, hackedRequiredDBFields, recordInput, ERROR_MISSING_NON_NULLABLE);
    }

    private void checkRequiredFieldsExist(Set<String> actualFields, List<String> requiredFields, InputField recordInput, String message) {
        if (!actualFields.containsAll(requiredFields)) {
            var missingFields = requiredFields.stream().filter(it -> !actualFields.contains(it)).collect(Collectors.joining(", "));
            LOGGER.warn(
                    String.format(
                            message,
                            recordInput.getTypeName(),
                            schema.getInputType(recordInput).getTable().getName(),
                            missingFields
                    )
            );
        }
    }
}
