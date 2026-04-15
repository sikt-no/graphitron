package no.sikt.graphitron.validation;

import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.AbstractObjectDefinition;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;
import static no.sikt.graphitron.mappings.TableReflection.getField;
import static no.sikt.graphitron.mappings.TableReflection.getFieldType;
import static no.sikt.graphitron.mappings.TableReflection.tableHasPrimaryKey;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphitron.validation.ValidationHandler.isTrue;
import static no.sikt.graphitron.validation.messages.MultitableError.MISSING_TABLE_ON_MULTITABLE;
import static no.sikt.graphql.directives.GenerationDirective.*;
import static no.sikt.graphql.naming.GraphQLReservedName.*;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Validates interface and union type definitions, including single-table interfaces,
 * multitable field constraints, and interface return type requirements.
 */
class InterfaceValidator extends AbstractSchemaValidator {

    InterfaceValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        validateMultitableTypeTables();
        validateSingleTableInterfaceDefinitions();
        validateInterfacesReturnedInFields();
        validateMultitableFieldsOutsideRoot();
        validateTypesUsingNodeInterfaceWithoutNodeDirective();
        validateUnionAndInterfaceSubTypes();
    }

    /*
     * This is a temporary validation until GG-104 has been fixed.
     */
    private void validateUnionAndInterfaceSubTypes() {
        schema.getObjects()
                .values().stream()
                .flatMap(o -> o.getFields().stream())
                .filter(schema::isMultiTableField)
                .filter(field -> !field.getTypeName().equals(NODE_TYPE.getName()))
                .filter(field -> !field.getTypeName().equals(FEDERATION_SERVICE_TYPE.getName()))
                .filter(field -> !field.getTypeName().equals(FEDERATION_ENTITY_UNION.getName()))
                .forEach(field -> {
                    var subTypes = schema.getTypesFromInterfaceOrUnion(field.getTypeName()).orElse(List.of());
                    if (subTypes.size() < 2 && subTypes.stream().noneMatch(type -> type.implementsInterface(ERROR_TYPE.getName()))) {
                        addErrorMessage(
                                "Multitable queries is currently only supported for interface and unions with more than one implementing type. \n" +
                                        "The field %s's type %s has %d implementing type(s).", field.getName(), field.getTypeName(), subTypes.size());
                    }
                });
    }

    private void validateMultitableFieldsOutsideRoot() {
        allFields.stream()
                .filter(GenerationSourceField::isGenerated)
                .filter(it -> !it.isRootField())
                .filter(it -> schema.isObjectWithPreviousTableObject(it.getContainerTypeName()))
                .filter(schema::isMultiTableField)
                .forEach(field -> {
                    if (!field.createsDataFetcher()) {
                        addErrorMessage("%s is a multitable field outside root, but is missing the %s directive. " +
                                        "Multitable queries outside root is only supported for resolver fields.",
                                field.formatPath(), SPLIT_QUERY.getName()
                        );
                    }

                    if (field.hasFieldReferences()) {
                        addErrorMessage("%s has the %s directive which is not supported on multitable queries. Use %s directive instead.",
                                field.formatPath(), REFERENCE.getName(), MULTITABLE_REFERENCE.getName()
                        );
                    }
                });
    }

    private void validateMultitableTypeTables() {
        schema
                .getObjects()
                .values()
                .stream()
                .filter(Objects::nonNull)
                .flatMap(it -> it.getFields().stream())
                .filter(schema::isMultiTableField)
                .filter(it -> it.createsDataFetcher() || it.isRootField())
                .filter(it -> !it.hasServiceReference())
                .filter(it -> !it.getName().equals(FEDERATION_ENTITIES_FIELD.getName()))
                .filter(it -> !it.getTypeName().equals(NODE_TYPE.getName()))
                .filter(it -> !it.getTypeName().equals(ERROR_TYPE.getName()))
                .forEach((field) -> {
                    var multitableName = field.getTypeName();
                    var typesMissingTable = schema
                            .getTypesFromInterfaceOrUnion(multitableName).orElse(List.of())
                            .stream()
                            .filter(it -> !it.hasTable())
                            .map(AbstractObjectDefinition::getName)
                            .collect(Collectors.joining("', '"));
                    if (!typesMissingTable.isEmpty()) {
                        addErrorMessage(MISSING_TABLE_ON_MULTITABLE, typesMissingTable, field.formatPath(), field.getTypeName());
                    }
                });
    }

    private void validateSingleTableInterfaceDefinitions() {
        schema.getInterfaces().forEach((name, interfaceDefinition) -> {
                    if (name.equalsIgnoreCase(NODE_TYPE.getName()) || name.equalsIgnoreCase(ERROR_TYPE.getName())) return;

                    var implementations = schema.getTypesFromInterfaceOrUnion(interfaceDefinition).orElse(List.of());

                    if (interfaceDefinition.hasDiscriminator() == interfaceDefinition.isMultiTableInterface()) {
                        addErrorMessage(
                                String.format("'%s' and '%s' directives on interfaces must be used together. " +
                                                "Interface '%s' is missing '%s' directive.",
                                        DISCRIMINATE.getName(), TABLE.getName(), name,
                                        interfaceDefinition.hasTable() ? DISCRIMINATE.getName() : TABLE.getName()));
                    }

                    if (!interfaceDefinition.isMultiTableInterface()) { // Single table interface with discriminator
                        Optional<?> discriminatorField = getField(
                                interfaceDefinition.getTable().getName(),
                                interfaceDefinition.getDiscriminatorFieldName()
                        );
                        if (discriminatorField.isEmpty()) {
                            addErrorMessage(
                                    String.format("Interface '%s' has discriminating field set as '%s', but the field " +
                                                    "does not exist in table '%s'.",
                                            name, interfaceDefinition.getDiscriminatorFieldName(), interfaceDefinition.getTable().getName()));
                        } else {
                            Optional<Class<?>> fieldType = getFieldType(
                                    interfaceDefinition.getTable().getName(),
                                    interfaceDefinition.getDiscriminatorFieldName()
                            );
                            if (fieldType.isEmpty() || !fieldType.get().equals(String.class)) {
                                addErrorMessage(
                                        String.format("Interface '%s' has discriminating field set as '%s', but the field " +
                                                        "does not return a string type, which is not supported.",
                                                name, interfaceDefinition.getDiscriminatorFieldName()));
                            }
                        }

                        implementations.forEach(impl -> {
                            if (!impl.hasDiscriminator()) {
                                addErrorMessage(
                                        String.format("Type '%s' is missing '%s' directive in order to implement interface '%s'.",
                                                impl.getName(), DISCRIMINATOR.getName(), name));
                            }
                            if (impl.hasTable() && interfaceDefinition.hasTable() && !impl.getTable().equals(interfaceDefinition.getTable())) {
                                addErrorMessage(
                                        String.format("Interface '%s' requires implementing types to have table '%s', " +
                                                        "but type '%s' has table '%s'.",
                                                name, interfaceDefinition.getTable().getName(), impl.getName(), impl.getTable().getName()));
                            }

                            impl.getFields()
                                    .stream()
                                    .filter(it -> interfaceDefinition.hasField(it.getName()))
                                    .forEach(it -> {
                                        var fieldInInterface = interfaceDefinition.getFieldByName(it.getName());
                                        String sharedErrorMessage = "Overriding '%s' configuration in types implementing " +
                                                "a single table interface is not currently supported, and must be identical " +
                                                "with interface. Type '%s' has a configuration mismatch on field '%s' from the interface '%s'.";

                                        if (it.hasCondition() != fieldInInterface.hasCondition()
                                                || (it.hasCondition() && !it.getCondition().equals(fieldInInterface.getCondition()))) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    CONDITION.getName(), impl.getName(), it.getName(), name
                                            );
                                        }

                                        if (!it.getFieldReferences().equals(fieldInInterface.getFieldReferences())) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    REFERENCE.getName(), impl.getName(), it.getName(), name
                                            );
                                        }
                                    });
                        });

                        // Check for conflicts in "shared" fields which are not in the interface definition
                        implementations.stream()
                                .map(AbstractObjectDefinition::getFields)
                                .flatMap(Collection::stream)
                                .filter(it -> !interfaceDefinition.hasField(it.getName()))
                                .collect(groupingBy(ObjectField::getName))
                                .entrySet()
                                .stream()
                                .filter(it -> it.getValue().size() > 1)
                                .forEach(entry -> {
                                    var fields = entry.getValue();
                                    var first = fields.get(0);

                                    fields.stream().skip(1).forEach(field -> {
                                        String sharedErrorMessage = "Different configuration on fields in types implementing the same single table interface is currently not supported. " +
                                                "Field '%s' occurs in two or more types implementing interface '%s', but there is a mismatch between the configuration of the '%s' directive.";
                                        if (!field.getUpperCaseName().equals(first.getUpperCaseName())) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    entry.getKey(), interfaceDefinition.getName(), FIELD.getName()
                                            );
                                        }

                                        if (!field.getFieldReferences().equals(first.getFieldReferences())) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    entry.getKey(), interfaceDefinition.getName(), REFERENCE.getName());
                                        }

                                        if (!(field.hasCondition() == first.hasCondition()
                                                && (!field.hasCondition() || field.getCondition().equals(first.getCondition())))) {
                                            addErrorMessage(
                                                    sharedErrorMessage,
                                                    entry.getKey(), interfaceDefinition.getName(), CONDITION.getName());
                                        }
                                    });
                                });
                    }
                }
        );

        schema.getObjects().values().stream()
                .filter(objectDefinition -> !objectDefinition.getImplementedInterfaces().isEmpty() || objectDefinition.hasDiscriminator())
                .forEach(objectDefinition -> {
                    var implementedInterfaces = objectDefinition.getImplementedInterfaces();

                    var singleTableInterfaces = implementedInterfaces.stream()
                            .filter(it -> !it.equals(NODE_TYPE.getName()))
                            .filter(schema::isSingleTableInterface)
                            .toList();

                    if (singleTableInterfaces.isEmpty() && objectDefinition.hasDiscriminator()) {
                        addErrorMessage(
                                String.format("Type '%s' has discriminator, but doesn't implement any interfaces requiring it.", objectDefinition.getName())
                        );
                    }
                });
    }

    private void validateInterfacesReturnedInFields() {
        allFields.stream()
                .filter(ObjectField::isGeneratedWithResolver)
                .filter(schema::isInterface)
                .forEach(field -> {
                            var typeName = field.getTypeName();
                            var name = Optional
                                    .ofNullable(schema.getObjectOrConnectionNode(typeName))
                                    .map(AbstractObjectDefinition::getName)
                                    .orElse(typeName);
                            var isSingleTable = schema.isSingleTableInterface(field);
                            if (!field.isRootField() && (isSingleTable || name.equals(NODE_TYPE.getName()))) {
                                addErrorMessage("interface (%s) returned in non root object. This is not fully " +
                                        "supported. Use with care", name);
                            }

                            if (name.equalsIgnoreCase(NODE_TYPE.getName())) {
                                isTrue(
                                        field.getArguments().size() == 1,
                                        "Only exactly one input field is currently supported for fields returning the '%s' interface. " +
                                                "%s has %s input fields", NODE_TYPE.getName(), field.formatPath(), field.getArguments().size()
                                );
                                isTrue(
                                        !field.isIterableWrapped(),
                                        "Generating fields returning a list of '%s' is not supported. " +
                                                "'%s' must return only one %s", name, field.getName(), field.getTypeName()
                                );
                            } else {
                                schema.getObjects()
                                        .values()
                                        .stream()
                                        .filter(it -> it.implementsInterface(schema.getInterface(name).getName()))
                                        .forEach(implementation -> {
                                            if (!implementation.hasTable() && isSingleTable) {
                                                addErrorMessage("Interface '%s' is returned in field '%s', but type '%s' " +
                                                        "implementing '%s' does not have table set. This is not supported.", name, field.getName(), implementation.getName(), name);
                                            } else if (implementation.hasTable() && !tableHasPrimaryKey(implementation.getTable().getName())) {
                                                addErrorMessage("Interface '%s' is returned in field '%s', but implementing type '%s' " +
                                                        "has table '%s' which does not have a primary key. This is not supported.", name, field.getName(), implementation.getName(), implementation.getTable().getName());
                                            }
                                        });
                            }
                        }
                );
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
                .collect(groupingBy(
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
