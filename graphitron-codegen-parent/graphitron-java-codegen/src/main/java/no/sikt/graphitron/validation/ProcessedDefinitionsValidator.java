package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.validation.ValidationHandler.logWarnings;
import static no.sikt.graphitron.validation.ValidationHandler.throwIfErrors;

/**
 * Orchestrates schema validation by delegating to focused sub-validators.
 * Each sub-validator is responsible for a specific domain of validation.
 */
public class ProcessedDefinitionsValidator {
    private final ProcessedSchema schema;
    private final List<ObjectField> allFields;

    private final List<AbstractSchemaValidator> validators;

    public ProcessedDefinitionsValidator(ProcessedSchema schema) {
        this.schema = schema;
        allFields = schema
                .getObjects()
                .values()
                .stream()
                .flatMap(it -> it.getFields().stream())
                .toList();

        validators = List.of(
                new InterfaceAndUnionValidator(schema, allFields),
                new TableValidator(schema, allFields),
                new RecordMappingValidator(schema, allFields),
                new ExternalReferenceValidator(schema, allFields),
                new ServiceValidator(schema, allFields),
                new MutationValidator(schema, allFields),
                new FieldDirectiveValidator(schema, allFields),
                new InputDirectiveValidator(schema, allFields),
                new ProcedureCallValidator(schema, allFields),
                new NodeValidator(schema, allFields),
                new PaginationValidator(schema, allFields),
                new FederationValidator(schema, allFields)
        );
    }

    /**
     * Validate the directive usage in the schema.
     */
    public void validateDirectiveUsage() {
        // Validate cycles first to ensure there are no infinite loops later
        new CycleValidator(schema, allFields).validate();
        throwIfErrors();

        validators.forEach(AbstractSchemaValidator::validate);
        logWarnings();
        throwIfErrors();
    }

    /**
     * Validate field types separately, as this is called conditionally from {@link ProcessedSchema#validate}.
     */
    public void validateObjectFieldTypes() {
        new FieldTypeValidator(schema, allFields).validate();
    }
}
