package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.RecordObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.validation.ValidationHandler.addWarningMessage;
import static no.sikt.graphitron.validation.messages.InputDirectiveWarning.RECORD_TYPE_CONFLICT;

/**
 * Validates directive combinations on input types.
 */
class InputDirectiveValidator extends AbstractSchemaValidator {

    InputDirectiveValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        validateNotUsingBothTableAndRecord();
    }

    private void validateNotUsingBothTableAndRecord() {
        schema.getInputTypes().values().stream()
                .filter(RecordObjectDefinition::hasTable)
                .filter(RecordObjectDefinition::hasJavaRecordReference)
                .forEach(input -> addWarningMessage(RECORD_TYPE_CONFLICT, input.getName()));
    }
}