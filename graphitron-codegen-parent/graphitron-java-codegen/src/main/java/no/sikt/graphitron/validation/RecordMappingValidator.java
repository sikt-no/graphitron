package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.RecordObjectDefinition;
import no.sikt.graphitron.mappings.ReflectionHelpers;
import no.sikt.graphql.schema.ProcessedSchema;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphql.directives.GenerationDirective.SPLIT_QUERY;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Validates Java record field mappings, split query fields in Java records,
 * wrapper types with previous table, and input field structure.
 */
class RecordMappingValidator extends AbstractSchemaValidator {

    RecordMappingValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        validateSplitQueryFieldsInJavaRecords();
        validateJavaRecordFieldMappings();
    }

    private void validateSplitQueryFieldsInJavaRecords() {
        allFields.stream()
                .filter(schema::hasJavaRecord)
                .map(schema::getObject)
                .map(AbstractObjectDefinition::getFields)
                .flatMap(Collection::stream)
                .filter(GenerationSourceField::createsDataFetcher)
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

    /**
     * Validates that all fields in types with @record directive can be mapped
     * to methods in the referenced Java record class.
     * - Input types: validates setter methods (setXxx)
     * - Output types: validates getter methods (getXxx)
     */
    private void validateJavaRecordFieldMappings() {
        schema.getInputTypes().values().stream()
                .filter(RecordObjectDefinition::hasJavaRecordReference)
                .forEach(type -> validateJavaRecordMethods(type, type.getRecordReference(), true));

        schema.getRecordTypes().values().stream()
                .filter(type -> !schema.isInputType(type.getName()))
                .filter(RecordObjectSpecification::hasJavaRecordReference)
                .forEach(type -> validateJavaRecordMethods(
                        (RecordObjectDefinition<?, ?>) type,
                        type.getRecordReference(),
                        false));
    }

    private void validateJavaRecordMethods(RecordObjectDefinition<?, ?> type, Class<?> recordClass, boolean isInput) {
        if (recordClass == null) return;

        for (var field : type.getFields()) {
            if (field.isExplicitlyNotGenerated()) {
                continue;
            }

            // Flatten nested types without @record/@table
            var flattenedType = getFlattenedNestedType(field, isInput);
            if (flattenedType.isPresent()) {
                validateJavaRecordMethods(flattenedType.get(), recordClass, isInput);
                continue;
            }

            if (shouldSkipFieldValidation(field, isInput)) {
                continue;
            }

            validateFieldHasMethod(field, type, recordClass, isInput);
        }
    }

    private Optional<RecordObjectDefinition<?, ?>> getFlattenedNestedType(GenerationField field, boolean isInput) {
        var nested = isInput
                ? schema.getInputType(field)
                : schema.getObject(field.getTypeName());

        if (nested != null && !nested.hasJavaRecordReference() && !nested.hasTable()
                && !schema.isExceptionOrExceptionUnion(field.getTypeName())) {
            return Optional.of(nested);
        }
        return Optional.empty();
    }

    private boolean shouldSkipFieldValidation(GenerationField field, boolean isInput) {
        return isInput
                ? schema.isInputType(field)
                : !field.createsDataFetcher() && schema.isObject(field);
    }

    private void validateFieldHasMethod(GenerationField field, RecordObjectDefinition<?, ?> type,
                                        Class<?> recordClass, boolean isInput) {
        if (!(field instanceof GenerationSourceField<?> sourceField)) {
            return;
        }

        var mapping = sourceField.getJavaRecordMethodMapping(true);
        var methodName = isInput ? mapping.asSet() : mapping.asGet();

        if (ReflectionHelpers.classHasMethod(recordClass, methodName)) {
            return;
        }
        var typeKind = isInput ? "input" : "type";
        var methodType = isInput ? "setter" : "getter";
        var suggestion = findSimilarMethods(recordClass, methodName, isInput);

        var message = suggestion.isEmpty()
                ? "Cannot map field '%s' in %s '%s' to %s in Java record '%s'. Expected method: %s"
                : "Cannot map field '%s' in %s '%s' to %s in Java record '%s'. Expected method: %s. Did you mean: %s?";

        if (suggestion.isEmpty()) {
            addErrorMessage(message, field.getName(), typeKind, type.getName(), methodType, recordClass.getSimpleName(), methodName);
        } else {
            addErrorMessage(message, field.getName(), typeKind, type.getName(), methodType, recordClass.getSimpleName(), methodName, suggestion.get());
        }
    }

    private Optional<String> findSimilarMethods(Class<?> recordClass, String expectedMethod, boolean isInput) {
        var prefix = isInput ? "set" : "get";
        var candidates = Arrays.stream(recordClass.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith(prefix));

        var similarMethods = findSimilarStringsWithDistance(expectedMethod, candidates, 12);
        if (similarMethods.isEmpty()) {
            return Optional.empty();
        }

        var fieldNames = similarMethods.keySet().stream()
                .map(methodName -> uncapitalize(methodName.substring(prefix.length())))
                .collect(Collectors.joining(", "));
        return Optional.of(fieldNames);
    }
}
