package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.validation.messages.InputTableError;
import no.sikt.graphql.naming.GraphQLReservedName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.*;
import java.util.stream.Collectors;

import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphitron.generators.context.NodeIdReferenceHelpers.isNodeIdReferenceField;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphitron.validation.ValidationHandler.addWarningMessage;
import static no.sikt.graphitron.validation.messages.InputTableError.MISSING_FIELD;
import static no.sikt.graphitron.validation.messages.InputTableError.MISSING_NON_NULLABLE;
import static org.apache.commons.lang3.StringUtils.lowerCase;

/**
 * Validates mutation directives, required fields, and delete/insert-with-returning mutations.
 */
class MutationValidator extends AbstractSchemaValidator {

    MutationValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
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

        validateMutationDirectives(mutations);
        validateMutationRequiredFields(mutations);
    }

    private void validateMutationDirectives(List<ObjectField> mutations) {
        mutations
                .stream()
                .filter(it -> !it.hasMutationType() && !it.hasServiceReference())
                .forEach(it -> addErrorMessage("Mutation '%s' is set to generate, but has neither a service nor mutation type set.", it.getName()));
        mutations
                .stream()
                .filter(it -> it.hasMutationType() && !new InputParser(it, schema).hasJOOQRecords())
                .forEach(it -> addErrorMessage("Mutations must have at least one table attached when generating resolvers with queries. Mutation '%s' has no tables attached.", it.getName()));

        if (GeneratorConfig.failOnMerge() && !GeneratorConfig.generateUpsertAsStore()) {
            mutations
                    .stream()
                    .filter(it -> it.hasMutationType() && it.getMutationType() == MutationType.UPSERT)
                    .forEach(it -> addErrorMessage("MERGE generation is disabled (failOnMerge is enabled), but mutation '%s' uses UPSERT. Possible workaround is enabling generateUpsertAsStore.", it.getName()));
        }
    }

    private void validateMutationRequiredFields(List<ObjectField> mutations) {
        mutations
                .stream()
                .filter(ObjectField::hasMutationType)
                .forEach(target -> {
                    validateRecordRequiredFields(target);
                    validateMutationWithReturning(target);
                    new InputParser(target, schema).getJOOQRecords().values().forEach(inputField -> checkMutationIOFields(inputField, target));
                });
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
                .filter(it -> !schema.isNodeIdField(it) || schema.getNodeTypeForNodeIdField(it).map(n -> n.getTable().equals(targetTable)).orElse(false))
                .filter(it -> schema.isNodeIdField(it) || it.getUpperCaseName().equalsIgnoreCase(GraphQLReservedName.NODE_ID.getName()))
                .toList();

        var requiredIdOrNodeIdFields = idFields.stream()
                .filter(AbstractField::isNonNullable)
                .toList();

        if (requiredIdOrNodeIdFields.isEmpty()) {
            var possibleFixes = new ArrayList<>(idFields.stream().map(GenerationSourceField::formatPath).map(it -> "Make " + it + " non-nullable.").toList());
            if (schema.getNodeTypesWithTable(targetTable).size() == 1) {
                possibleFixes.add(String.format("Add non-nullable node ID input field for type '%s'.",
                        schema.getNodeTypesWithTable(targetTable).stream().findFirst().orElseThrow().getName())
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

        if (target.hasFieldReferences() || isNodeIdReferenceField(target, schema, targetTable)) {
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
                    .filter(it -> !it.createsDataFetcher())
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

    // Check input and payload("output") fields
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

    private void checkRequiredFields(InputField recordInput) {
        var inputObject = schema.getInputType(recordInput);
        var tableName = inputObject.getTable().getMappingName();

        var requiredDBFields = getRequiredFields(tableName)
                .stream()
                .map(String::toUpperCase)
                .filter(it -> !tableFieldHasDefaultValue(tableName, it))
                .collect(Collectors.toList());
        var recordFieldNames =
                inputObject.getFields()
                        .stream()
                        .map(InputField::getUpperCaseName)
                        .collect(Collectors.toSet());
        checkRequiredFieldsExist(MISSING_FIELD, recordFieldNames, requiredDBFields, recordInput);

        var requiredRecordFieldNames =
                inputObject.getFields()
                        .stream()
                        .filter(AbstractField::isNonNullable)
                        .map(InputField::getUpperCaseName)
                        .collect(Collectors.toSet());
        checkRequiredFieldsExist(MISSING_NON_NULLABLE, requiredRecordFieldNames, requiredDBFields, recordInput);
    }

    private void checkRequiredFieldsExist(InputTableError message, Set<String> actualFields, List<String> requiredFields, InputField recordInput) {
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

}
