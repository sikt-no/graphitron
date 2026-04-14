package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.helpers.CodeReferenceWrapper;
import no.sikt.graphitron.definitions.objects.EnumDefinition;
import no.sikt.graphitron.definitions.sql.SQLCondition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;

/**
 * Validates that external references (enums, services, conditions) configured in the schema
 * resolve to known classes.
 */
class ExternalReferenceValidator extends AbstractSchemaValidator {

    ExternalReferenceValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        var referenceSet = GeneratorConfig.getExternalReferences();
        schema
                .getEnums()
                .values()
                .stream()
                .filter(EnumDefinition::hasJavaEnumMapping)
                .map(EnumDefinition::getEnumReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> addErrorMessage("No enum with name '%s' found.", e.getSchemaClassReference()));

        allFields.stream()
                .filter(ObjectField::hasServiceReference)
                .map(ObjectField::getExternalMethod)
                .map(CodeReferenceWrapper::getReference)
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
}
