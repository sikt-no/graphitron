package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.*;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.mapping.TableRelationType;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.RecordObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.shouldMakeNodeStrategy;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphitron.validation.ValidationHandler.*;
import static no.sikt.graphitron.validation.messages.SelfReferenceError.SELF_REFERENCE_WITHOUT_SPLITQUERY;
import static no.sikt.graphitron.validation.messages.SelfReferenceWarning.SELF_REFERENCE_IMPLICITLY_REVERSE;
import static no.sikt.graphql.directives.GenerationDirective.NODE_ID;
import static no.sikt.graphql.directives.GenerationDirective.REFERENCE;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITY_UNION;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_SERVICE_TYPE;

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
        validateOneToManyInputReferences();
        validateInputFields();
        validateWrapperTypesWithPreviousTable();
        validateOnlyOneInputRecordInputWhenNoTypeTableIsPresent();
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

        if (targetTable.equals(sourceTable)) {
            validateSelfReference(field);
        }
        validateReferencePath(field, sourceTable, targetTable);
    }

    private void validateSelfReference(GenerationField field) {
        if (!field.createsDataFetcher() && field.hasFieldReferences()) {
            addErrorMessage(SELF_REFERENCE_WITHOUT_SPLITQUERY.format(field.formatPath()));
        }
        // Only checks the first reference step. Multi-step self-reference paths are not yet validated.
        var firstRef = field.getFieldReferences().stream().findFirst();
        var joinsOnKeyPath = firstRef.isEmpty() || firstRef.get().hasTable() || firstRef.get().hasKey();
        if (field.isIterableWrapped() && joinsOnKeyPath) {
            addWarningMessage(SELF_REFERENCE_IMPLICITLY_REVERSE.format(field.formatPath()));
        }
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
            if (field.hasProcedureCall()) {
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
                || field.createsDataFetcher()
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

    /**
     * Validates that query arguments and input fields with multistep @reference paths do not contain
     * one-to-many (REVERSE) joins in non-final steps. Such joins cause row duplication that cannot be resolved
     * automatically. One-to-many in the final step is allowed, as it is handled by EXISTS subquery generation.
     */
    private void validateOneToManyInputReferences() {
        allFields.forEach(field -> {
            if (!field.hasNonReservedInputFields()) return;
            var connectionNode = schema.getObjectOrConnectionNode(field);
            if (connectionNode == null || !connectionNode.hasTable()) return;

            var rootTable = connectionNode.getTable().getMappingName();
            field.getNonReservedArguments()
                    .forEach(arg -> validateNoOneToManyInNonFinalStep(arg, rootTable));
            field.getNonReservedArguments().stream()
                    .filter(schema::isInputType)
                    .flatMap(arg -> schema.getInputType(arg).getFields().stream())
                    .forEach(inputField -> validateNoOneToManyInNonFinalStep(inputField, rootTable));
        });
    }

    /**
     * Validates that a filter field's @reference path does not contain a one-to-many relationship in a non-final step.
     * The total number of steps includes an implicit step from @nodeId if it targets a different table than the last
     * explicit reference.
     */
    private void validateNoOneToManyInNonFinalStep(GenerationField field, String rootTable) {
        var references = field.getFieldReferences();
        var lastRefTable = resolveReferenceTargetTable(references, rootTable).orElse(rootTable);
        var nodeType = schema.isNodeIdField(field) ? schema.getNodeTypeForNodeIdField(field).orElse(null) : null;
        var hasImplicitNodeIdStep = nodeType != null && nodeType.hasTable()
                && !nodeType.getTable().getMappingName().equals(lastRefTable);
        var totalSteps = references.size() + (hasImplicitNodeIdStep ? 1 : 0);
        if (totalSteps < 2) return;

        var currentTable = rootTable;
        for (int i = 0; i < references.size(); i++) {
            var ref = references.get(i);
            String nextTable;
            if (ref.hasKey()) {
                nextTable = resolveKeyOtherTable(ref.getKey().getName(), currentTable).orElse(null);
            } else if (ref.hasTable()) {
                nextTable = ref.getTable().getMappingName();
            } else {
                return;
            }
            if (nextTable == null) return;
            if (i < totalSteps - 1) {
                var relationType = inferRelationType(currentTable, nextTable, ref.hasKey() ? ref.getKey() : null);
                if (relationType == TableRelationType.REVERSE_IMPLICIT || relationType == TableRelationType.REVERSE_KEY) {
                    addErrorMessage(
                            "Field %s has a multi-step @%s path with a one-to-many relationship " +
                                    "from \"%s\" to \"%s\" in a non-final step. This is not currently supported on filter inputs because it causes row duplication. " +
                                    "Use the @condition directive for complex cross-table filtering instead.",
                            field.formatPath(), REFERENCE.getName(), currentTable, nextTable);
                }
            }
            currentTable = nextTable;
        }
    }

    // TODO: It seems we are now handling/validating lists that are directly below parent lists. But what about
    //  validation of nested lists where a list is not directly below parent list, but are deeper down the hierarchy?
    private void validateInputFields() {
        var oneLayerFlattenedFields = allFields
                .stream()
                .filter(schema::hasTableObject)
                .flatMap(it -> it.getNonReservedArguments().stream())
                .filter(it -> !schema.hasRecord(it) && !it.hasOverridingCondition())
                .filter(AbstractField::isIterableWrapped)
                .filter(schema::isInputType)
                .toList();

        for (var field : oneLayerFlattenedFields) {
            var messageStart = String.format("Argument '%s' is a collection of InputFields ('%s') type.", field.getName(), field.getTypeName());
            var inputDefinitionFields = schema.getInputType(field).getFields();

            inputDefinitionFields.stream().filter(AbstractField::isIterableWrapped).findFirst().ifPresent(it -> addErrorMessageAndThrow(
                    "%s Fields returning collections: '%s' are not supported on such types (used for generating condition tuples)",
                    messageStart,
                    it.getName()
            ));

            var optionalFields = inputDefinitionFields
                    .stream()
                    .filter(AbstractField::isNullable)
                    .map(AbstractField::getName)
                    .collect(Collectors.toList());
            if (!optionalFields.isEmpty()) {
                addErrorMessage(
                        String.format(
                                "%s Optional fields on such types are not supported. The following fields will be " +
                                        "treated as mandatory in the resulting, generated condition tuple: '%s'",
                                messageStart,
                                String.join("', '", optionalFields)
                        )
                );
            }
        }
    }

    private void validateWrapperTypesWithPreviousTable() {
        schema.getObjects()
                .values()
                .stream()
                .filter(it -> schema.isObjectWithPreviousTableObject(it.getName()))
                .map(AbstractObjectDefinition::getFields)
                .flatMap(Collection::stream)
                .filter(AbstractField::isIterableWrapped)
                .filter(schema::isObject)
                .filter(it -> !it.hasFieldReferences())
                .filter(it -> !it.createsDataFetcher())
                .filter(it -> Optional.ofNullable(schema.getObject(it).getTable())
                        .map(t -> schema.getPreviousTableObjectForField(it).getTable().equals(t))
                        .orElse(true)
                )
                .forEach(f ->
                        addErrorMessage(
                                "Field %s returns a list of wrapper type '%s' (a type wrapping a subset of the table fields)," +
                                        " which is not supported. Change the field to return a single '%s' to fix.",
                                f.formatPath(), f.getTypeName(), f.getTypeName(), f.getTypeName())
                );
    }

    private void validateOnlyOneInputRecordInputWhenNoTypeTableIsPresent() {
        var mutation = schema.getMutationType();
        var mutations = mutation != null ? mutation.getFields().stream() : Stream.<ObjectField>of();
        var query = schema.getQueryType();
        var queries = query != null ? query.getFields().stream() : Stream.<ObjectField>of();

        Stream
                .concat(queries, mutations)
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(it -> !it.hasServiceReference())
                .filter(it -> !it.getTypeName().equals(FEDERATION_SERVICE_TYPE.getName()))
                .filter(it -> !it.getTypeName().equals(FEDERATION_ENTITY_UNION.getName()))
                .filter(it -> !schema.isInterface(it))
                .filter(it -> !schema.isUnion(it))
                .filter(it -> schema.isScalar(it) || schema.isRecordType(it))
                .filter(it -> !schema.nextTypeTableExists(it, new HashSet<>()))
                .collect(Collectors.toMap(it -> it.getContainerTypeName() + "." + it.getName(), it -> schema.findInputTables(it).size()))
                .entrySet()
                .stream()
                .filter(it -> it.getValue() != 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                .forEach((key, value) -> addErrorMessage(
                        "%s is a field of a type without a table, and has %s potential input records to use as a source for the table in queries. In such cases, there must be exactly one input table so that it can be resolved unambiguously.",
                        key,
                        value
                ));
    }
}
