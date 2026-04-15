package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.validation.ValidationHandler.throwIfErrors;

/**
 * Validates that all field types in the schema resolve to known types.
 */
class FieldTypeValidator extends AbstractSchemaValidator {

    FieldTypeValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
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
        return checkTypeExists(field.getTypeName(), Set.of())
                .entrySet()
                .stream()
                .map(it -> String.format("Field \"%s\" within schema type \"%s\" has invalid type \"%s\" (or an union containing it). Closest type matches found by levenshtein distance are:\n%s", field.getName(), objectName, it.getKey(), it.getValue()))
                .collect(Collectors.toList());
    }

    private Map<String, String> checkTypeExists(String typeName, Set<String> seenTypes) {
        if (seenTypes.contains(typeName) || schema.isScalar(typeName)) {
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
        var similarTypes = findSimilarStringsWithDistance(typeName, schema.getAllValidFieldTypeNames().stream(), 12);
        var formatted = similarTypes.entrySet().stream()
                .map(e -> e.getKey() + " - " + e.getValue())
                .collect(Collectors.joining(", "));
        return Map.of(typeName, formatted);
    }
}
