package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.definitions.objects.RecordObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.context.NodeIdReferenceHelpers.isNodeIdReferenceField;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphql.directives.GenerationDirective.LOOKUP_KEY;
import static no.sikt.graphql.directives.GenerationDirective.TABLE;

/**
 * Validates Apollo Federation entity definitions and @lookupKey arguments.
 */
class FederationValidator extends AbstractSchemaValidator {

    FederationValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        validateLookupArguments();
        validateEntities();
    }

    private void validateLookupArguments() {
        allFields.forEach(field -> {
            var lookupArguments = field.getNonReservedArguments().stream()
                    .filter(InputField::isLookupKey)
                    .toList();

            JOOQMapping currentTable;
            var previousTableObject = schema.getPreviousTableObjectForField(field);
            if (schema.isObject(field.getTypeName()) && schema.getObject(field.getTypeName()).hasTable()) {
                currentTable = schema.getObject(field.getTypeName()).getTable();
            } else {
                if (previousTableObject != null && previousTableObject.hasTable()) {
                    currentTable = previousTableObject.getTable();
                } else {
                    currentTable = null;
                }
            }

            lookupArguments.stream()
                    .filter(it -> it.hasFieldReferences() || isNodeIdReferenceField(it, schema, currentTable))
                    .forEach(it -> addErrorMessage(
                            "Argument/input field %s has %s directive, but is a reference field. Lookup on references is not currently supported.",
                            it.formatPath(), LOOKUP_KEY.getName()));

            lookupArguments.stream()
                    .filter(schema::isInputType)
                    .forEach(arg -> {
                        var referenceFields = schema.getInputType(arg).getFields().stream()
                                .filter(it -> it.hasFieldReferences() || isNodeIdReferenceField(it, schema, currentTable))
                                .map(GenerationSourceField::formatPath)
                                .collect(Collectors.joining(", "));
                        if (!referenceFields.isEmpty()) {
                            addErrorMessage(
                                    "Argument %s has %s directive, but contains reference field(s): %s. Lookup on references is not currently supported.",
                                    arg.formatPath(), LOOKUP_KEY.getName(), referenceFields);
                        }
                    });
        });
    }

    private void validateEntities() {
        schema.getObjects().values().stream()
                .filter(RecordObjectDefinition::isEntity)
                .forEach(this::validateEntity);
    }

    private void validateEntity(ObjectDefinition entityType) {
        if (!entityType.hasTable()) {
            addErrorMessage("Entity type '%s' must map to a table using the @%s directive",
                    entityType.getName(),
                    TABLE.getName());
            return;
        }

        var hasNestedKeys = entityType.getEntityKeys().keys().stream().anyMatch(key -> !key.getNestedKeys().isEmpty());
        if (hasNestedKeys) {
            addErrorMessage("Nested key(s) found in entity type '%s'. This is currently not supported.", entityType.getName());
        }

        for (var compositeKey: entityType.getEntityKeys().keys()) {
            for (var key : compositeKey.getKeys()) {
                var matchingField = entityType.getFields().stream()
                        .filter(field -> field.getName().equals(key))
                        .findFirst();
                if (matchingField.isEmpty()) {
                    var similarFields = findSimilarStringsWithDistance(key, entityType.getFields().stream().map(AbstractField::getName), 6);
                    var suggestion = similarFields.isEmpty() ? "" : String.format(". Did you mean one of: '%s'?", String.join("', '", similarFields.keySet()));
                    addErrorMessage("Entity Key field '%s' was not found in type '%s'%s", key, entityType.getName(), suggestion);
                } else if (matchingField.get().hasFieldReferences() || isNodeIdReferenceField(matchingField.get(), schema)) {
                    addErrorMessage("Entity Key field '%s' in type '%s' is a reference. This is currently not supported.",
                            key, entityType.getName());
                }
            }
        }
    }
}
