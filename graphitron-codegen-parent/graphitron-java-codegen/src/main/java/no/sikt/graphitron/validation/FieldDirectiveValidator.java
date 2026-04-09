package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Field;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.mappings.TableReflection.getJavaFieldName;
import static no.sikt.graphitron.mappings.TableReflection.getMethodFromReference;
import static no.sikt.graphitron.mappings.TableReflection.tableExists;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphitron.validation.messages.ConstructError.*;
import static no.sikt.graphql.directives.GenerationDirective.*;

/**
 * Validates @constructType, @externalField, and @field directive combinations.
 */
class FieldDirectiveValidator extends AbstractSchemaValidator {

    FieldDirectiveValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        validateNotUsingBothExternalFieldAndField();
        validateConstructTypeDirective();
        validateExternalField();
    }

    private void validateNotUsingBothExternalFieldAndField() {
        schema.getObjects().values()
                .forEach(object -> object.getFields().stream()
                        .filter(it -> it.isExternalField() && it.hasFieldDirective())
                        .forEach(it -> addErrorMessage(
                                        "Field " + it.getName() + " in type " + it.getContainerTypeName() + " cannot have both the field and externalField directives."
                                )
                        )
                );
    }

    private void validateConstructTypeDirective() {
        schema
                .getObjects()
                .values()
                .forEach(object -> object
                        .getFields()
                        .stream()
                        .filter(ObjectField::hasSelectConstruct)
                        .forEach(field -> validateConstructField(field, object))
                );
    }

    private void validateConstructField(ObjectField field, ObjectDefinition object) {
        var targetType = schema.getObject(field.getTypeName());

        if (targetType == null) {
            addErrorMessage(CONSTRUCT_FIELD_IS_NOT_TYPE, field.formatPath(), field.getTypeName());
            return;
        }

        if (targetType.hasTable()) {
            addErrorMessage(CONSTRUCT_TYPE_HAS_TABLE, field.formatPath(), field.getTypeName());
        }

        var hasTable = schema.hasTableObjectForObject(object);
        if (!hasTable) {
            addErrorMessage(CONSTRUCT_MISSING_TABLE, field.formatPath());
        }

        if (field.hasFieldDirective()) {
            addErrorMessage(CONSTRUCT_FIELD_HAS_ILLEGAL_DIRECTIVE, field.formatPath(), FIELD.getName());
        }

        if (field.isExternalField()) {
            addErrorMessage(CONSTRUCT_FIELD_HAS_ILLEGAL_DIRECTIVE, field.formatPath(), EXTERNAL_FIELD.getName());
        }

        var construct = field.getSelectConstruct();
        var tableJavaName = hasTable
                ? schema.getPreviousTableObjectForObject(object).getTable().getMappingName()
                : null;

        for (var entry : construct.values().entrySet()) {
            var selectionFieldName = entry.getKey();
            var columnName = entry.getValue();

            var innerField = targetType.getFields().stream()
                    .filter(f -> f.getName().equals(selectionFieldName))
                    .findFirst();

            if (innerField.isEmpty()) {
                addErrorMessage(CONSTRUCT_NONEXISTENT_FIELD, field.formatPath(), selectionFieldName, targetType.getName());
            } else if (schema.isObject(innerField.get())) {
                addErrorMessage(CONSTRUCT_CONTAINS_NESTED_TYPE, field.formatPath(), selectionFieldName);
            }

            if (hasTable && tableExists(tableJavaName) && getJavaFieldName(tableJavaName, columnName).isEmpty()) {
                addErrorMessage(CONSTRUCT_NONEXISTENT_COLUMN, field.formatPath(), columnName, tableJavaName);
            }
        }
    }

    private void validateExternalField() {
        this.schema.getObjects().values()
                .forEach(object -> object.getFields().stream()
                        .filter(GenerationSourceField::isExternalField)
                        .forEach(field -> {
                            String typeName = field.getContainerTypeName();
                            JOOQMapping table =
                                    Optional.ofNullable(schema.getObject(typeName).getTable())
                                            .orElseGet(() ->
                                                    Optional.ofNullable(schema.getPreviousTableObjectForField(field))
                                                            .map(RecordObjectSpecification::getTable)
                                                            .orElse(null)
                                            );

                            if (table == null) {
                                addErrorMessage("No table found for field " + field.getName() + "in type " + typeName);
                                return;
                            }

                            Set<String> referenceImports = GeneratorConfig.getExternalReferenceImports();
                            List<Method> methods = referenceImports.stream()
                                    .map(it -> getMethodFromReference(it, table.getName(), field.getName()))
                                    .flatMap(Optional::stream)
                                    .toList();

                            if (methods.isEmpty()) {
                                addErrorMessage("No method found for field " + field.getName() + " in type " + typeName);
                                return;
                            }

                            if (methods.size() > 1) {
                                addErrorMessage("Multiple methods found for field " + field.getName() + "in type " + typeName);
                                return;
                            }

                            Method method = methods.get(0);

                            if (!method.getReturnType().equals(Field.class)) {
                                addErrorMessage("Return type of method needs to be generic type Field for field " + field.getName() + "in type " + typeName);
                            }

                            Type type = method.getGenericReturnType();

                            if (type instanceof ParameterizedType paramType) {
                                Type actualType = paramType.getActualTypeArguments()[0];

                                if (!actualType.getTypeName().equals(field.getTypeClass().toString())) {
                                    addErrorMessage("Type parameter of generic type Field in method needs to match scalar type of field " + field.getName() + "in type " + typeName);
                                }
                            }
                        })
                );
    }
}
