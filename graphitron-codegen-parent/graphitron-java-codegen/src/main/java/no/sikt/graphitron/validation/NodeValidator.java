package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ArgumentField;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.definitions.objects.RecordObjectDefinition;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphitron.mappings.TableReflection;
import no.sikt.graphql.directives.GenerationDirectiveParam;
import no.sikt.graphql.schema.ProcessedSchema;
import org.jooq.Key;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.context.NodeIdReferenceHelpers.findForeignKeyForNodeIdField;
import static no.sikt.graphitron.generators.context.NodeIdReferenceHelpers.isNodeIdReferenceField;
import static no.sikt.graphitron.mappings.JavaPoetClassName.STRING;
import static no.sikt.graphitron.mappings.ReflectionHelpers.getJooqRecordClassForNodeIdInputField;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphql.directives.GenerationDirective.*;
import static no.sikt.graphql.directives.GenerationDirectiveParam.TYPE_ID;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Validates @node and @nodeId directives, node ID references in jOOQ and Java record inputs.
 */
class NodeValidator extends AbstractSchemaValidator {

    NodeValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        validateNodeDirective();
        validateTypesUsingNodeInterfaceWithoutNodeDirective();
        validateNodeId();
        validateNodeIdReferenceInJooqRecordInput();
        validateNodeIdFieldsInJavaRecordInputs();
    }

    private void validateNodeDirective() {
        var typeIdMap = new HashMap<String, Set<String>>();
        schema.getObjects().values().stream()
                .filter(ObjectDefinition::hasNodeDirective)
                .forEach(objectDefinition -> {
                    var nodeConfiguration = objectDefinition.getNodeConfiguration()
                            .orElseThrow(() -> new IllegalStateException(String.format( "Node type '%s' is unexpectedly missing node configuration.", objectDefinition.getName())));

                    if (objectDefinition.getTable() == null) {
                        addErrorMessage("Type %s has the %s directive, but is missing the %s directive.",
                                objectDefinition.getName(), NODE.getName(), TABLE.getName());
                    } else {
                        if (!objectDefinition.getTable().equals(nodeConfiguration.targetTable())) {
                            throw new IllegalStateException(String.format(
                                    "Unexpected mismatch between node configuration table and object table for '%s'. Please report if you have encountered this issue.",
                                    objectDefinition.getName())
                            );
                        }

                        var tableFields = getJavaFieldNamesForTable(nodeConfiguration.targetTable().getName());
                        nodeConfiguration.keyColumnsJavaNames().stream()
                                .filter(col -> tableFields.stream().noneMatch(it -> it.equalsIgnoreCase(col)))
                                .forEach(col -> addErrorMessage(
                                        "Key column '%s' in node ID for type '%s' does not exist in table '%s'",
                                        col,
                                        objectDefinition.getName(),
                                        nodeConfiguration.targetTable().getName())
                                );

                        if (getPrimaryOrUniqueKeyMatchingFields(nodeConfiguration.targetTable().getName(), nodeConfiguration.keyColumnsJavaNames()).isEmpty()) {
                            addErrorMessage(
                                    "Key columns in node ID for type '%s' does not match a PK/UK for table '%s'",
                                    objectDefinition.getName(),
                                    nodeConfiguration.targetTable().getName());
                        }
                    }
                    if (!objectDefinition.implementsInterface(NODE_TYPE.getName())) {
                        addErrorMessage("Type %s has the %s directive, but does not implement the %s interface.",
                                objectDefinition.getName(), NODE.getName(), NODE_TYPE.getName());
                    }
                    if (GeneratorConfig.requireTypeIdOnNode() && !nodeConfiguration.hasCustomTypeId()) {
                        addErrorMessage("Type '%s' has the '%s' directive, but is missing the '%s' parameter which has been configured to be required.",
                                objectDefinition.getName(), NODE.getName(), TYPE_ID.getName());
                    }
                    typeIdMap.computeIfAbsent(nodeConfiguration.typeId(), k -> new HashSet<>())
                            .add(objectDefinition.getName());
                });

        typeIdMap.entrySet().stream()
                .filter(it -> it.getValue().size() > 1)
                .forEach((entry) ->
                        addErrorMessage(
                                "Multiple node types (%s) have the same node type ID '%s'. Type IDs must be unique.",
                                String.join(", ", entry.getValue()),
                                entry.getKey())
                );
    }

    private void validateNodeId() {
        Stream<? extends GenerationSourceField<?>> inputStream =
                Stream.concat(
                        schema.getInputTypes().values().stream().flatMap(it -> it.getFields().stream()),
                        allFields.stream().flatMap(it -> it.getNonReservedArguments().stream())
                );
        Stream.concat(allFields.stream(), inputStream)
                .filter(GenerationSourceField::hasNodeID)
                .forEach(this::checkNodeId);
    }

    private void checkNodeId(GenerationField field) {
        var fieldName = field instanceof ArgumentField
                ? String.format("argument '%s' on a field in type '%s'", field.getName(), field.getContainerTypeName())
                : String.format("field %s", field.formatPath());

        if (!(field.isID() || field.getTypeName().equals(STRING.className.simpleName()))) {
            addErrorMessage(
                    "%s has %s directive, but is not an ID or String field.",
                    capitalize(fieldName),
                    NODE_ID.getName()
            );
        }

        if (field.getNodeIdTypeName().isPresent()) {
            var providedType = schema.getObject(field.getNodeIdTypeName().get());
            if (providedType == null) {
                addErrorMessage(
                        "Type with name '%s' referenced in the %s directive for %s does not exist.",
                        field.getNodeIdTypeName().get(),
                        NODE_ID.getName(),
                        fieldName
                );
                return;
            } else if (!providedType.hasNodeDirective()) {
                addErrorMessage(
                        "Referenced type '%s' referenced in the %s directive for %s is missing the necessary %s directive.",
                        providedType.getName(),
                        NODE_ID.getName(),
                        fieldName,
                        NODE.getName()
                );
                return;
            }
        } else if (schema.getNodeTypeForNodeIdField(field).isEmpty()) { // Implicit nodeType
            addErrorMessage("Cannot automatically deduce node type for node ID field %s. " +
                            "Please specify the node type with the %s parameter in the %s directive.",
                    field.formatPath(),
                    GenerationDirectiveParam.TYPE_NAME.getName(),
                    NODE_ID.getName()
            );
            return;
        }

        if (field instanceof ObjectField && isNodeIdReferenceField((ObjectField) field, schema)) {
            // Only filter object fields because we currently don't have reference validation on input (GGG-209)
            var recordType = Optional
                    .ofNullable(schema.getRecordType(field.getContainerTypeName()))
                    .flatMap(it -> Optional.ofNullable(it.getTable()));

            var referenceTable = schema.getNodeTypeForNodeIdFieldOrThrow(field)
                    .getTable();
            recordType.ifPresent(it -> validateReferencePath(field, it.getMappingName(), referenceTable.getMappingName()));
            if (recordType.isEmpty()) {
                var inputMapping = schema.findInputTables(field).stream().findFirst();
                if (inputMapping.isPresent() && !inputMapping.get().equals(referenceTable)) {
                    validateReferencePath(field, inputMapping.get().getMappingName(), referenceTable.getMappingName());
                }
            }
        }

        if (field.hasFieldDirective()) {
            // Allow @nodeId + @field only in @record input types targeting jOOQ record fields
            boolean isAllowed = getJooqRecordClassForNodeIdInputField(field, schema).isPresent();

            if (!isAllowed) {
                addErrorMessage(
                        "%s has both the '%s' and '%s' directives, which is only supported for node ID fields in Java Record inputs.",
                        capitalize(fieldName),
                        NODE_ID.getName(),
                        FIELD.getName()
                );
            }
        }
        if (field.isExternalField()) {
            addErrorMessage(
                    "%s has both the '%s' and '%s' directives, which is not supported.",
                    capitalize(fieldName),
                    NODE_ID.getName(),
                    EXTERNAL_FIELD.getName()
            );
        }
    }

    private void validateNodeIdReferenceInJooqRecordInput() {
        schema.getInputTypes()
                .values()
                .stream()
                .filter(it -> schema.isRecordType(it.getName()))
                .map(it -> schema.getRecordType(it.getName()))
                .filter(it -> it.getFields().stream().anyMatch(FieldSpecification::hasNodeID))
                .filter(it -> it.hasTable() && getTableByJavaFieldName(it.getTable().getName()).isPresent())
                .forEach(jooqRecordInput ->
                        jooqRecordInput.getFields()
                                .stream()
                                .filter(schema::isNodeIdField)
                                .filter(it -> schema.getNodeTypeForNodeIdField(it).isPresent())
                                .filter(it -> !schema.getNodeTypeForNodeIdFieldOrThrow(it).getTable().equals(jooqRecordInput.getTable()))
                                .forEach(it -> {
                                    validateNodeIdReferenceInRecord(jooqRecordInput.getTable().getName(), it, true, jooqRecordInput.getTable());
                                    findForeignKeyForNodeIdField(it, schema, jooqRecordInput.getTable()).ifPresent(foreignKey -> {
                                        if (isUsedInUpdateMutation(jooqRecordInput)) {
                                            getPrimaryKeyForTable(jooqRecordInput.getTable().getName())
                                                    .map(Key::getFields)
                                                    .filter(pkFields -> pkFields.stream().anyMatch(pkF -> foreignKey.getFields().stream().anyMatch(pkF::equals)))
                                                    .stream().findFirst()
                                                    .ifPresent((a) -> addErrorMessage(
                                                            "Foreign key used for node ID field '%s' in jOOQ record input '%s' overlaps with the primary key of the jOOQ record table. This is not supported for update/upsert mutations.",
                                                            it.getName(),
                                                            it.getContainerTypeName()
                                                    ));
                                        }
                                    });
                                })
                );
    }

    private void validateNodeIdReferenceInRecord(String jooqRecordName, GenerationField field, boolean isJooqRecordInput, JOOQMapping currentTable) {
        var inputKind = isJooqRecordInput ? "jOOQ record input" : "Java record input";

        var foreignKeyOptional = findForeignKeyForNodeIdField(field, schema, currentTable);
        if (foreignKeyOptional.isEmpty()) {
            addErrorMessage("Cannot find foreign key for node ID field '%s' in %s '%s'.",
                    field.getName(),
                    inputKind,
                    field.getContainerTypeName());
            return;
        }

        var foreignKey = foreignKeyOptional.get();

        if (!foreignKey.getTable().getName().equalsIgnoreCase(jooqRecordName)) {
            addErrorMessage(
                    "Node ID field '%s' in %s '%s' references a table with an inverse key which is not supported.",
                    field.getName(),
                    inputKind,
                    field.getContainerTypeName()
            );
            return;
        }

        var nodeType = schema.getNodeTypeForNodeIdFieldOrThrow(field);
        var nodeConfig = schema.getNodeConfigurationForNodeIdFieldOrThrow(field);
        var firstForeignKeyReferencesTargetTable = getTableJavaFieldNameByTableName(foreignKey.getInverseKey().getTable().getName())
                .orElseThrow()
                .equalsIgnoreCase(nodeConfig.targetTable().getName());

        if (field.getFieldReferences().size() > 1 || (field.hasFieldReferences() && !firstForeignKeyReferencesTargetTable)) {
            addErrorMessage(
                    "Node ID field '%s' in %s '%s' has a reference via table(s) which is not supported on %ss.",
                    field.getName(),
                    inputKind,
                    field.getContainerTypeName(),
                    inputKind
            );
            return;
        }

        var targetKey =  getPrimaryOrUniqueKeyMatchingFields(nodeConfig.targetTable().getName(), nodeConfig.keyColumnsJavaNames());

        if (targetKey.isPresent() && !targetKey.get().equals(foreignKey.getKey())) {
            addErrorMessage(
                    "Node ID field '%s' in %s '%s' uses foreign key '%s' which does not reference the same primary/unique key used for type '%s's node ID. This is not supported.",
                    field.getName(),
                    inputKind,
                    field.getContainerTypeName(),
                    foreignKey.getName(),
                    nodeType.getName()
            );
        }
    }

    private boolean isUsedInUpdateMutation(RecordObjectSpecification<?> jooqRecordInput) {
        var mutation = schema.getMutationType();
        if (mutation == null) {
            return false;
        }

        var usages = mutation.getFields().stream()
                .filter(ObjectField::hasMutationType)
                .flatMap(objectField -> {
                    var inputs = new InputParser(objectField, schema).getJOOQRecords().values();
                    return inputs.stream()
                            .map(schema::getInputType)
                            .filter(it -> it != null && it.getName().equals(jooqRecordInput.getName()))
                            .map(it -> objectField.getMutationType());
                }).collect(Collectors.toSet());

        if (usages.isEmpty()) {
            return false;
        }
        return usages.stream().anyMatch(mt -> mt == MutationType.UPDATE || mt == MutationType.UPSERT);
    }

    private void validateNodeIdFieldsInJavaRecordInputs() {
        schema.getInputTypes().values().stream()
                .filter(RecordObjectDefinition::hasJavaRecordReference)
                .flatMap(it -> it.getFields().stream())
                .filter(GenerationSourceField::hasNodeID)
                .filter(it -> schema.getNodeTypeForNodeIdField(it).isPresent())
                .forEach(it -> getJooqRecordClassForNodeIdInputField(it, schema)
                        .filter(recordClass -> !schema.getNodeTypeForNodeIdFieldOrThrow(it).getTable().getRecordClass().equals(recordClass))
                        .ifPresent(recordClass -> validateNodeIdReferenceInRecord(
                                TableReflection.getTableJavaFieldNameForRecordClass(recordClass).orElseThrow(),
                                it, false, JOOQMapping.fromTable(getTableName(recordClass)))));
    }

    private void validateTypesUsingNodeInterfaceWithoutNodeDirective() {
        if (!schema.nodeExists() ||
                schema.getQueryType() == null ||
                schema.getQueryType().getFieldByName(uncapitalize(NODE_TYPE.getName())) == null ||
                schema.getQueryType().getFieldByName(uncapitalize(NODE_TYPE.getName())).isExplicitlyNotGenerated()) {
            return;
        }

        var records = schema
                .getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(NODE_TYPE.getName()) && it.hasTable() && !it.hasNodeDirective())
                .collect(Collectors.groupingBy(
                        it -> it.getTable().getName(), Collectors.mapping(ObjectDefinition::getName, Collectors.toSet())));

        records.forEach((tableName, schemaTypes) -> {
            if (schemaTypes.size() > 1) {
                addErrorMessage(
                        "Multiple types (%s) implement the %s interface and refer to the same table %s. This is not supported.",
                        String.join(", ", schemaTypes), NODE_TYPE.getName(), tableName);
            }
        });
    }
}
