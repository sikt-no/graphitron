package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.VirtualSourceField;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.objects.RecordObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphql.directives.GenerationDirective.NODE_ID;

/**
 * Validates table and key existence, reference paths, and required table fields.
 */
class TableValidator extends AbstractSchemaValidator {

    TableValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        validateTablesAndKeys();
        validateRequiredTableFields();
    }

    private void validateTablesAndKeys() {
        var recordTypes = schema.getRecordTypes();
        recordTypes
                .values()
                .stream()
                .filter(RecordObjectSpecification::hasTable)
                .map(RecordObjectSpecification::getTable)
                .map(JOOQMapping::getMappingName)
                .filter(it -> !tableExists(it))
                .forEach(it -> addErrorMessage("No table with name \"%s\" found.", it));

        var allReferences = recordTypes
                .values()
                .stream()
                .flatMap(it -> it.getFields().stream())
                .flatMap(it -> it.isInput() ? Stream.of(it) : Stream.concat(Stream.of(it), ((ObjectField) it).getArguments().stream()))
                .flatMap(it -> it.getFieldReferences().stream())
                .toList();

        allReferences
                .stream()
                .filter(FieldReference::hasTable)
                .map(FieldReference::getTable)
                .map(JOOQMapping::getMappingName)
                .filter(it -> !tableExists(it))
                .forEach(it -> addErrorMessage("No table with name \"%s\" found.", it));

        allReferences
                .stream()
                .filter(FieldReference::hasKey)
                .map(FieldReference::getKey)
                .map(JOOQMapping::getMappingName)
                .filter(it -> !keyExists(it))
                .forEach(it -> addErrorMessage("No key with name \"%s\" found.", it));

        recordTypes
                .values()
                .stream()
                .filter(schema::hasTableObjectForObject)
                .filter(it -> !schema.isMultiTableInterface(it.getName()))
                .filter(it -> !it.hasJavaRecordReference())
                .flatMap(it -> it.getFields().stream())
                .flatMap(it -> schema.isMultiTableField(it)
                        ? schema.getTypesFromInterfaceOrUnion(it.getTypeName()).orElse(List.of()).stream().map(o -> new VirtualSourceField(o.getName(), (ObjectField) it))
                        : Stream.of(it))
                .filter(field -> !field.hasServiceReference())
                .filter(field -> schema.isRecordType(field.getTypeName()) || field.hasFieldReferences() || field.createsDataFetcher())
                .forEach(this::validateReferencePathFromField);
    }

    private void validateReferencePathFromField(GenerationField field) {
        var targetTable = getTargetTableForField(field);

        if (targetTable == null) return;

        var sourceTable = schema.getPreviousTableObjectForObject(schema.getRecordTypes().get(field.getContainerTypeName())).getTable().getName();
        validateReferencePath(field, sourceTable, targetTable);
    }

    /**
     * Returns target table for field determined by target type's table, nodeId directive or 'table' in last item provided in reference directive.
     * Does not find target table from key only.
     * @return Name of the target table
     */
    private String getTargetTableForField(GenerationField field) {
        if (schema.isNodeIdField(field)) {
            return schema.getNodeTypeForNodeIdFieldOrThrow(field).getTable().getName();
        }
        if (schema.hasTableObject(field)) {
            return schema.getObjectOrConnectionNode(field).getTable().getName();
        }

        if (schema.isScalar(field)) {
            if (!field.hasFieldReferences()) {
                return null;
            }
            var references = field.getFieldReferences();

            return Optional.ofNullable(references.get(references.size() - 1))
                    .filter(FieldReference::hasTable)
                    .map(it -> it.getTable().getName()).orElse(null);
        }
        return null;
    }

    /**
     * Walk a reference path from a source table and return the final resolved table name.
     * Returns empty if the path contains a condition-only element (no table metadata available).
     */
    private static Optional<String> resolveReferenceTargetTable(List<FieldReference> references, String sourceTable) {
        var currentTable = sourceTable;
        for (FieldReference ref : references) {
            if (ref.hasKey()) {
                var resolved = resolveKeyOtherTable(ref.getKey().getName(), currentTable);
                if (resolved.isEmpty()) return Optional.empty();
                currentTable = resolved.get();
            } else if (ref.hasTable()) {
                currentTable = ref.getTable().getMappingName();
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(currentTable);
    }

    private void validateRequiredTableFields() {
        // Validate objects with table
        schema.getObjects().values().stream()
                .filter(RecordObjectDefinition::hasTable)
                .toList()
                .forEach(object -> validateTableFieldsExist(object.getTable(), object.getFields()));

        // Validate input objects with table (jOOQ record input)
        var alreadyValidatedJooqRecordInput = schema.getInputTypes().values().stream()
                .filter(RecordObjectDefinition::hasTable)
                .filter(it -> !it.hasJavaRecordReference())
                .map(input -> {
                    validateTableFieldsExist(input.getTable(), input.getFields(), false);
                    return input.getName();
                })
                .toList();

        // Validate input on fields returning table object (except already validated jOOQ record input types)
        allFields.stream()
                .filter(schema::isObject)
                .filter(GenerationSourceField::isGenerated)
                .filter(ObjectField::hasNonReservedInputFields)
                .filter(it -> schema.getObjectOrConnectionNode(it) != null)
                .filter(it -> schema.getObjectOrConnectionNode(it).hasTable())
                .forEach(field -> {
                    var table = schema.getObjectOrConnectionNode(field).getTable();
                    var argumentsToValidate = field.getNonReservedArguments().stream()
                            .filter(it -> !alreadyValidatedJooqRecordInput.contains(it.getTypeName()))
                            .toList();
                    validateTableFieldsExist(table, argumentsToValidate, field.hasOverridingCondition());
                });

        // Validate fields with implicit table from input jOOQ record
        allFields.stream()
                .filter(GenerationSourceField::isGenerated)
                .filter(it -> !it.hasServiceReference())
                .filter(it -> schema.findInputTables(it).size() == 1)
                .filter(it -> schema.getObjectOrConnectionNode(it) == null || !schema.getObjectOrConnectionNode(it).hasTable())
                .forEach(field -> {
                    var implicitTableFromInput = schema.findInputTables(field).get(0);
                    List<ObjectField> fields;

                    if (field.hasMutationType()) {
                        var dataTargetOptional = schema.inferDataTargetForMutation(field);
                        if (dataTargetOptional.isEmpty()) {
                            return;
                        }

                        var dataTarget = dataTargetOptional.get();
                        if (schema.isObject(dataTarget) && schema.getObject(dataTarget).hasTable()) {
                            return;
                        }
                        fields = schema.isScalar(dataTarget) ? List.of(dataTarget) : schema.getObject(dataTarget).getFields();
                    } else if (schema.isObject(field)) {
                        fields = schema.getObject(field).getFields();
                    } else if (schema.isScalar(field)) {
                        fields = List.of(field);
                    } else {
                        return;
                    }
                    validateTableFieldsExist(implicitTableFromInput, fields);
                });
    }

    private void validateTableFieldsExist(JOOQMapping currentTable, Collection<ObjectField> fields) {
        for (var field : getFieldsInTableContext(fields, currentTable)) {
            if (schema.isObject(field)) {
                if (field.hasSelectConstruct()) {
                    continue;
                }
                validateTableFieldsExist(currentTable, schema.getObject(field).getFields());
                continue;
            }
            validateFieldExists(currentTable.getMappingName(), field);
        }
    }

    private void validateTableFieldsExist(JOOQMapping currentTable, Collection<? extends InputField> fields, boolean hasOverridingCondition) {
        for (var field : getFieldsInTableContext(fields, currentTable)) {
            var hasCondition = hasOverridingCondition || field.hasOverridingCondition();
            if (schema.isInputType(field)) {
                var inputType = schema.getInputType(field);
                if (!inputType.hasTable()) {
                    validateTableFieldsExist(currentTable, inputType.getFields(), hasCondition);
                }
            } else if (!hasCondition) {
                validateFieldExists(currentTable.getMappingName(), field);
            }
        }
        // Validate that @field columns exist in @reference tables
        if (!hasOverridingCondition) {
            fields.stream()
                    .filter(field -> field.isGenerated() && field.hasFieldReferences() && !field.hasNodeID() && !field.hasOverridingCondition())
                    .forEach(field ->
                            resolveReferenceTargetTable(field.getFieldReferences(), currentTable.getMappingName())
                                    .ifPresent(table -> validateFieldExists(table, field)));
        }
    }

    private <T extends GenerationField> List<T> getFieldsInTableContext(Collection<T> fieldList, JOOQMapping currentTable) {
        return fieldList.stream()
                .filter(GenerationTarget::isGenerated)
                .filter(it -> !leavesTableContext(it, currentTable))
                .toList();
    }

    private boolean leavesTableContext(GenerationField field, JOOQMapping currentTable) {
        return field.hasFieldReferences()
                || (schema.isObject(field) && schema.getObject(field).hasTable() && !schema.getObject(field).getTable().equals(currentTable))
                || schema.isConnectionObject(field)
                || schema.isMultiTableField(field)
                || schema.isInterface(field)
                || schema.isNodeIdField(field)
                || field.isExternalField()
                || field.hasServiceReference()
                || (schema.isInputType(field) && schema.getInputType(field).hasJavaRecordReference());
    }

    private void validateFieldExists(String tableJavaName, GenerationField field) {
        if (!tableExists(tableJavaName)) {
            return;
        }
        if (field.isID() && !GeneratorConfig.shouldMakeNodeStrategy()) {
            return;
        }
        if (getJavaFieldName(tableJavaName, field.getUpperCaseName()).isEmpty()) {
            if (shouldMakeNodeStrategy() && field.isID()) {
                addErrorMessage("No field with name '%s' found in table '%s' which may be required by %s. " +
                                "Add %s directive if %s is supposed to be a node ID field.",
                        field.getUpperCaseName(), tableJavaName, field.formatPath(), NODE_ID.getName(), field.formatPath());
            } else {
                addErrorMessage("No field with name '%s' found in table '%s' which is required by %s.",
                        field.getUpperCaseName(), tableJavaName, field.formatPath());
            }
        }
    }
}
