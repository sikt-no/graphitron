package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.EnumDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.mappings.TableReflection.tableHasPrimaryKey;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphql.directives.GenerationDirective.*;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_CONNECTION_SUFFIX;

/**
 * Validates pagination specs, ordering requirements, and @orderBy input types.
 */
class PaginationValidator extends AbstractSchemaValidator {

    PaginationValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        schema.getObjects().values().forEach(it -> checkPaginationSpecs(it.getFields()));
        validatePaginatedFieldsHaveOrdering();
        validateOrderByInputTypes();
        validateDefaultOrderNotOnInterfaceOrUnion();
    }

    private static void checkPaginationSpecs(List<ObjectField> fields) {
        for (var field : fields) {
            var hasConnectionSuffix = field.getTypeName().endsWith(SCHEMA_CONNECTION_SUFFIX.getName());
            if (hasConnectionSuffix && !field.hasRequiredPaginationFields()) {
                addErrorMessage(
                        "Type %s ending with the reserved suffix 'Connection' must have either " +
                                "forward(first and after fields) or backwards(last and before fields) pagination, " +
                                "yet neither was found.", field.getTypeName()
                );
            }
        }
    }

    /**
     * Validates that paginated fields on tables without primary keys have stable ordering.
     * Without ordering, cursor-based pagination produces unstable results.
     * <p>
     * For tables without primary keys, we need one of:
     * - @defaultOrder directive (provides fallback ordering)
     * - non-nullable @orderBy (user must always provide ordering)
     * - both @orderBy and @defaultOrder (nullable orderBy with fallback)
     */
    private void validatePaginatedFieldsHaveOrdering() {
        allFields.stream()
                .filter(ObjectField::hasForwardPagination)
                .filter(ObjectField::isGenerated)
                .filter(field -> {
                    var recordType = schema.getRecordType(field);
                    return recordType != null && recordType.hasTable();
                })
                .forEach(field -> {
                    var recordType = schema.getRecordType(field);
                    var tableName = recordType.getTable().getMappingName();
                    if (!tableHasPrimaryKey(tableName)) {
                        var hasDefaultOrder = field.getDefaultOrder().isPresent();
                        var orderByField = field.getOrderField();
                        var hasNonNullableOrderBy = orderByField.isPresent() && orderByField.get().isNonNullable();

                        if (!hasDefaultOrder && !hasNonNullableOrderBy) {
                            if (orderByField.isPresent()) {
                                // Has nullable @orderBy but no @defaultOrder
                                addErrorMessage(
                                        "Paginated field '%s' in type '%s' has nullable @%s but no @%s directive. " +
                                                "Table '%s' has no primary key, so either make the orderBy argument non-nullable (orderBy: ...!) " +
                                                "or add @%s to provide fallback ordering when orderBy is not provided.",
                                        field.getName(),
                                        field.getContainerTypeName(),
                                        ORDER_BY.getName(),
                                        DEFAULT_ORDER.getName(),
                                        tableName,
                                        DEFAULT_ORDER.getName()
                                );
                            } else {
                                // No @orderBy and no @defaultOrder
                                addErrorMessage(
                                        "Paginated field '%s' in type '%s' requires @%s or @%s directive. " +
                                                "Table '%s' has no primary key, so without ordering, cursor-based pagination produces unstable results.",
                                        field.getName(),
                                        field.getContainerTypeName(),
                                        DEFAULT_ORDER.getName(),
                                        ORDER_BY.getName(),
                                        tableName
                                );
                            }
                        }
                    }
                });
    }

    /**
     * Validates that @defaultOrder directive is not used on fields returning interface or union types.
     * This feature is not yet supported for interface/union queries.
     */
    private void validateDefaultOrderNotOnInterfaceOrUnion() {
        allFields.stream()
                .filter(field -> field.getDefaultOrder().isPresent())
                .filter(field -> schema.isInterface(field) || schema.isUnion(field))
                .forEach(field -> addErrorMessage(
                        "@%s directive on field '%s' in type '%s' is not supported because the field returns %s type '%s'. " +
                                "@%s is not yet supported for interface or union queries.",
                        DEFAULT_ORDER.getName(),
                        field.getName(),
                        field.getContainerTypeName(),
                        schema.isInterface(field) ? "interface" : "union",
                        field.getTypeName(),
                        DEFAULT_ORDER.getName()
                ));
    }

    /**
     * Validates that all @orderBy input types have the required structure:
     * one enum field with @order/@index directives (the sort field) and one direction enum (ASC/DESC).
     */
    private void validateOrderByInputTypes() {
        allFields.stream()
                .map(ObjectField::getOrderField)
                .flatMap(Optional::stream)
                .forEach(orderField -> {
                    var inputType = schema.getInputType(orderField);
                    if (inputType == null) {
                        addErrorMessage("Input type '%s' not found in schema", orderField.getTypeName());
                        return;
                    }
                    var enums = inputType.getFields().stream()
                            .filter(schema::isEnum)
                            .map(schema::getEnum)
                            .toList();
                    var orderByCount = enums.stream().filter(EnumDefinition::isOrderByEnum).count();
                    var directionCount = enums.stream().filter(EnumDefinition::isDirectionEnum).count();
                    if (orderByCount != 1) {
                        addErrorMessage(
                                "Expected exactly one orderBy enum field on type '%s', but found %d. The @%s input type must contain exactly one enum field whose values have @%s directives.",
                                orderField.getTypeName(), orderByCount, ORDER_BY.getName(), ORDER.getName());
                    }
                    if (directionCount != 1) {
                        addErrorMessage(
                                "Expected exactly one direction enum field on type '%s', but found %d. The @%s input type must contain exactly one direction enum field (e.g., with ASC/DESC values).",
                                orderField.getTypeName(), directionCount, ORDER_BY.getName());
                    }
                    // Validate that all orderBy enum fields have @order or @index
                    enums.stream()
                            .filter(EnumDefinition::isOrderByEnum)
                            .forEach(e -> e.getObjectDefinition().getEnumValueDefinitions().stream()
                                    .filter(v -> !v.hasDirective(ORDER.getName()) && !v.hasDirective(INDEX.getName()))
                                    .forEach(v -> addErrorMessage(
                                            "Enum field '%s' of '%s' has no valid @%s directive",
                                            v.getName(), e.getName(), ORDER.getName())));
                });
    }
}
