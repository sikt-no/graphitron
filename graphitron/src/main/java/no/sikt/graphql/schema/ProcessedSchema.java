package no.sikt.graphql.schema;

import graphql.language.SchemaDefinition;
import graphql.language.*;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldType;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.ObjectSpecification;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.objects.*;
import no.sikt.graphitron.validation.ProcessedDefinitionsValidator;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.naming.GraphQLReservedName;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.Recursion.recursionCheck;
import static no.sikt.graphql.naming.GraphQLReservedName.*;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class represents a fully processed GraphQL schema. This is Graphitron's pre-processing of the schema.
 */
public class ProcessedSchema {
    private final Map<String, EnumDefinition> enums;
    private final Map<String, ObjectDefinition> objects, entities;
    private final Map<String, ExceptionDefinition> exceptions;
    private final Map<String, InputDefinition> inputs;
    private final Map<String, RecordObjectSpecification<? extends GenerationField>> recordTypes;
    private final Map<String, InterfaceDefinition> interfaces;
    private final Map<String, ConnectionObjectDefinition> connectionObjects;
    private final Map<String, UnionDefinition> unions;
    private final Set<String> tableTypesWithTable, scalarTypes, typeNames, validFieldTypes;
    private final ObjectDefinition queryType, mutationType;
    private final Map<String, RecordObjectSpecification<?>> objectWithPreviousTable;
    private final no.sikt.graphitron.definitions.objects.SchemaDefinition rootObject;
    private final List<GenerationField> transformableFields;
    private final List<ObjectDefinition> unreferencedObjects;
    private final boolean nodeExists;

    // Graphitron-provided special inputs. Should be excluded from certain operations.
    private final Set<String> specialInputs = Set.of("ExternalCodeReference", "ReferenceElement", "ErrorHandler");

    public ProcessedSchema(TypeDefinitionRegistry typeRegistry) {
        typeNames = typeRegistry.getTypes(TypeDefinition.class).stream().map(TypeDefinition::getName).collect(Collectors.toSet());
        var objectTypes = typeRegistry.getTypes(ObjectTypeDefinition.class);
        enums = EnumDefinition.processEnumDefinitions(typeRegistry.getTypes(EnumTypeDefinition.class))
                .stream()
                .collect(Collectors.toMap(EnumDefinition::getName, Function.identity()));

        objects = ObjectDefinition
                .processObjectDefinitions(objectTypes)
                .stream()
                .collect(Collectors.toMap(ObjectDefinition::getName, Function.identity()));

        var allReferencedTypes = objects
                .values()
                .stream()
                .flatMap(it -> it.getFields().stream())
                .map(AbstractField::getTypeName)
                .collect(Collectors.toSet());
        unreferencedObjects = objects
                .values()
                .stream()
                .filter(it -> !allReferencedTypes.contains(it.getName()))
                .filter(it -> !it.getName().equals(SCHEMA_QUERY.getName()) && !it.getName().equals(SCHEMA_MUTATION.getName()))
                .collect(Collectors.toList());

        entities = objects
                .values()
                .stream()
                .filter(RecordObjectDefinition::isEntity)
                .collect(Collectors.toMap(ObjectDefinition::getName, Function.identity()));

        var exceptionTypes = objectTypes
                .stream()
                .filter(obj ->
                        obj
                                .getImplements()
                                .stream()
                                .filter(it -> it instanceof TypeName)
                                .map(it -> ((TypeName) it).getName())
                                .anyMatch(it -> it.equals(ERROR_TYPE.getName()))
                )
                .collect(Collectors.toList());
        exceptions = ExceptionDefinition
                .processObjectDefinitions(exceptionTypes)
                .stream()
                .collect(Collectors.toMap(ExceptionDefinition::getName, Function.identity()));

        rootObject = isObject(SCHEMA_QUERY.getName()) || isObject(SCHEMA_MUTATION.getName())
                ? new no.sikt.graphitron.definitions.objects.SchemaDefinition(createSchemaDefinition())
                : null;
        queryType = rootObject != null && rootObject.getQuery() != null ? getObject(rootObject.getQuery()) : null;
        mutationType = rootObject != null && rootObject.getMutation() != null ? getObject(rootObject.getMutation()) : null;

        inputs = InputDefinition.processInputDefinitions(typeRegistry.getTypes(InputObjectTypeDefinition.class))
                .stream()
                .filter(it -> !specialInputs.contains(it.getName()))
                .collect(Collectors.toMap(InputDefinition::getName, Function.identity()));

        interfaces = typeRegistry.getTypes(InterfaceTypeDefinition.class)
                .stream()
                .map(InterfaceDefinition::new)
                .collect(Collectors.toMap(ObjectSpecification::getName, Function.identity()));

        nodeExists = interfaces.containsKey(NODE_TYPE.getName());

        connectionObjects = objectTypes
                .stream()
                .filter(f -> f.getName().endsWith(GraphQLReservedName.SCHEMA_CONNECTION_SUFFIX.getName()))
                .map(this::getSearchObjectDefinitionFor)
                .collect(Collectors.toMap(ConnectionObjectDefinition::getName, Function.identity()));

        recordTypes = Stream
                .concat(objects.values().stream(), inputs.values().stream())
                .collect(Collectors.toMap(AbstractObjectDefinition::getName, Function.identity()));
        tableTypesWithTable = recordTypes
                .values()
                .stream()
                .filter(RecordObjectSpecification::hasTable)
                .map(RecordObjectSpecification::getName)
                .collect(Collectors.toSet());

        unions = UnionDefinition.processUnionDefinitions(typeRegistry.getTypes(UnionTypeDefinition.class))
                .stream()
                .collect(Collectors.toMap(UnionDefinition::getName, Function.identity()));

        scalarTypes = typeRegistry.scalars().keySet();
        validFieldTypes = Stream.concat(scalarTypes.stream(), typeNames.stream()).collect(Collectors.toSet());

        // queryType may be null in certain tests.
        objectWithPreviousTable = new HashMap<>();
        var nodes = objects.values().stream().filter(it -> it.implementsInterface(NODE_TYPE.getName())).map(it -> (RecordObjectSpecification<?>) it);
        (queryType != null ? Stream.concat(nodes, Stream.of(queryType)) : nodes).forEach(this::buildPreviousTableMap);
        interfaces.values().forEach(this::buildPreviousTableMap);

        transformableFields = findTransformableFields();
    }

    private SchemaDefinition createSchemaDefinition() {
        var definitionBuilder = SchemaDefinition.newSchemaDefinition();
        var query = objects.get(SCHEMA_QUERY.getName());
        if (query != null) {
            var queryName = query.getName();
            definitionBuilder.operationTypeDefinition(
                    OperationTypeDefinition
                            .newOperationTypeDefinition()
                            .name(uncapitalize(queryName)).typeName(TypeName.newTypeName().name(queryName).build())
                            .build()
            );
        }

        var mutation = objects.get(SCHEMA_MUTATION.getName());
        if (mutation != null) {
            var mutationName = mutation.getName();
            definitionBuilder.operationTypeDefinition(
                    OperationTypeDefinition
                            .newOperationTypeDefinition()
                            .name(uncapitalize(mutationName)).typeName(TypeName.newTypeName().name(mutationName).build())
                            .build()
            );
        }
        return definitionBuilder.build();
    }

    public boolean nodeExists() {
        return nodeExists;
    }

    /**
     * Ensure that the definitions created in this class match database names where applicable.
     */
    public void validate() {
        validate(true);
    }

    /**
     * Ensure that the definitions created in this class match database names where applicable.
     */
    public void validate(boolean checkTypes) {
        ProcessedDefinitionsValidator processedDefinitionsValidator = GeneratorConfig.getExtendedFunctionality().createExtensionIfAvailable(ProcessedDefinitionsValidator.class, new Class[]{ProcessedSchema.class}, this);
        if (checkTypes) {
            processedDefinitionsValidator.validateObjectFieldTypes();
        }
        processedDefinitionsValidator.validateThatProcessedDefinitionsConformToJOOQNaming();
    }

    /**
     * @return Does this name belong to a valid GraphQL type in the schema?
     */
    public boolean isType(String name) {
        return typeNames.contains(name);
    }

    /**
     * @return Does this field type belong to a valid GraphQL type in the schema?
     */
    public boolean isType(FieldSpecification field) {
        return typeNames.contains(field.getTypeName());
    }

    /**
     * @return Set of all possible type or scalar names that a field can have in the schema.
     */
    public Set<String> getAllValidFieldTypeNames() {
        return validFieldTypes;
    }

    /**
     * @return Map of all the enums in the schema by name.
     */
    public Map<String, EnumDefinition> getEnums() {
        return enums;
    }

    /**
     * @return Does this name belong to an enum type in the schema?
     */
    public boolean isEnum(String name) {
        return enums.containsKey(name) || isConnectionObject(name) && isEnum(getConnectionObject(name).getNodeType());
    }

    /**
     * @return Does this field point to an enum type in the schema?
     */
    public boolean isEnum(GenerationField field) {
        return enums.containsKey(field.getTypeName()) || isConnectionObject(field) && isEnum(getConnectionObject(field).getNodeType());
    }

    /**
     * @return Does this field point to an enum type in the schema and does it have the {@link GenerationDirective#ENUM} directive set?
     */
    public boolean isJavaMappedEnum(FieldSpecification field) {
        return Optional.ofNullable(getEnum(field)).map(EnumDefinition::hasJavaEnumMapping).orElse(false);
    }

    /**
     * @return Get an enum with this name.
     */
    public EnumDefinition getEnum(String name) {
        return enums.get(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
    }

    /**
     * @return Get the enum that this field points to.
     */
    public EnumDefinition getEnum(FieldSpecification field) {
        var typeName = field.getTypeName();
        return enums.get(isConnectionObject(typeName) ? getConnectionObject(typeName).getNodeType() : typeName);
    }

    /**
     * @return Map of all the interfaces in the schema by name.
     */
    public Map<String, InterfaceDefinition> getInterfaces() {
        return interfaces;
    }

    /**
     * @return Does this name belong to an interface type in the schema?
     */
    public boolean isInterface(String name) {
        return interfaces.containsKey(name) || isConnectionObject(name) && isInterface(getConnectionObject(name).getNodeType());
    }

    /**
     * @return Does this field point to an interface type in the schema?
     */
    public boolean isInterface(GenerationField field) {
        return interfaces.containsKey(field.getTypeName()) || isConnectionObject(field) && isInterface(getConnectionObject(field).getNodeType());
    }

    /**
     * @return Get an interface with this name.
     */
    public InterfaceDefinition getInterface(String name) {
        return interfaces.get(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
    }

    /**
     * @return Get the interface that this field points to.
     */
    public InterfaceDefinition getInterface(FieldSpecification field) {
        var typeName = field.getTypeName();
        return interfaces.get(isConnectionObject(typeName) ? getConnectionObject(typeName).getNodeType() : typeName);
    }

    /**
     * @return Get the implementations for an interface given its name
     */
    public Set<ObjectDefinition> getImplementationsForInterface(String interfaceName) {
        return getObjects()
                .values()
                .stream()
                .filter(it -> it.implementsInterface(interfaceName))
                .collect(Collectors.toSet());
    }

    /**
     * @return Get the implementations for an interface
     */
    public Set<ObjectDefinition> getImplementationsForInterface(InterfaceDefinition interfaceDefinition) {
        return getImplementationsForInterface(interfaceDefinition.getName());
    }

    /**
     * @return Get the ObjectDefinition for each Type in a Union given its name
     */
    public Set<ObjectDefinition> getUnionSubTypes(String objectName) {
        return getUnion(objectName).
                getFieldTypeNames()
                .stream()
                .map(this::getObjectOrConnectionNode)
                .collect(Collectors.toSet());
    }

    /*
    * Returns the ObjectDefinition for each Type in a union given that the name
    * supplied is a union. Otherwise, return the ObjectDefinition for all types
    * that implements the given interface.
    * */

    public Set<ObjectDefinition> getTypesFromInterfaceOrUnion(String name) {
        if (isUnion(name)) {
            return getUnionSubTypes(name);
        }
        if (isInterface(name)) {
            return getImplementationsForInterface(name);
        }
        return null;
    }

    /*
     * Returns the ObjectDefinition for each type implementing an interface given its interfaceTypeDefinition
     * */
    public Set<ObjectDefinition> getTypesFromInterfaceOrUnion(InterfaceDefinition interfaceDefinition) {
        return getTypesFromInterfaceOrUnion(interfaceDefinition.getName());
    }


    /*
     * Returns the ObjectDefinition for each type in a union given its unionTypeDefinition
     * */
    public Set<ObjectDefinition> getTypesFromInterfaceOrUnion(UnionDefinition unionDefinition) {
        return getTypesFromInterfaceOrUnion(unionDefinition.getName());
    }

    /**
     * @return Map of all the objects in the schema by name.
     */
    public Map<String, ObjectDefinition> getObjects() {
        return objects;
    }

    /**
     * @return List of all the objects in the schema that are not referenced by an object field.
     */
    public List<ObjectDefinition> getUnreferencedObjects() {
        return unreferencedObjects;
    }

    /**
     * @return Map of all the types by name that have the federation directive @key set.
     */
    public Map<String, ObjectDefinition> getEntities() {
        return entities;
    }

    /**
     * @return Set of all the scalar types in the schema.
     */
    public Set<String> getScalarTypes() {
        return scalarTypes;
    }

    /**
     * @return Does this name belong to an object type in the schema?
     */
    public boolean isObject(String name) {
        return objects.containsKey(name);
    }

    /**
     * @return Does this field point to an object type in the schema?
     */
    public boolean isObject(FieldSpecification field) {
        return objects.containsKey(field.getTypeName());
    }

    /**
     * @return Get an object with this name.
     */
    public ObjectDefinition getObject(String name) {
        return objects.get(name);
    }

    /**
     * @return Get the object that this field points to.
     */
    public ObjectDefinition getObject(FieldSpecification field) {
        return objects.get(field.getTypeName());
    }

    /**
     * @return Get an object or connection node with this name.
     */
    public ObjectDefinition getObjectOrConnectionNode(String name) {
        return getObject(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
    }

    /**
     * @return Get the object or connection node that this field points to.
     */
    public ObjectDefinition getObjectOrConnectionNode(GenerationField field) {
        return getObjectOrConnectionNode(field.getTypeName());
    }

    /**
     * @return Does this name belong to an object type or connection node in the schema that is connected to a database table?
     */
    public boolean hasTableObject(String name) {
        return isObject(name) && getObject(name).hasTable() || isConnectionObject(name) && hasTableObject(getConnectionObject(name).getNodeType());
    }

    /**
     * @return Does this field point to an object type or connection node in the schema that is connected to a database table?
     */
    public boolean hasTableObject(FieldSpecification field) {
        return hasTableObject(field.getTypeName());
    }

    /**
     * @return Does this name point to an object type in the schema which implements the Node interface?
     */
    public boolean implementsNode(String name) {
        return isObject(name) && getObject(name).implementsInterface(NODE_TYPE.getName());
    }

    /**
     * @return Does this field point to an object type in the schema which implements the Node interface?
     */
    public boolean implementsNode(FieldSpecification field) {
        return implementsNode(field.getTypeName());
    }
    /**
     * @return Does this name point to an object type in the schema which implements the interface?
     */
    public boolean implementsInterface(String name, String interfaceName) {
        return isObject(name) && getObject(name).implementsInterface(interfaceName);
    }

    /**
     * @return Does this field point to an object type in the schema which implements the interface?
     */
    public boolean implementsInterface(FieldSpecification field, String interfaceName) {
        return implementsInterface(field.getTypeName(), interfaceName);
    }

    /**
     * @return Does this name belong to a connection object type in the schema?
     */
    public boolean isConnectionObject(String name) {
        return connectionObjects.containsKey(name);
    }

    /**
     * @return Does this field point to a connection object type in the schema?
     */
    public boolean isConnectionObject(FieldSpecification field) {
        return connectionObjects.containsKey(field.getTypeName());
    }

    /**
     * @return Get a connection object with this name.
     */
    public ConnectionObjectDefinition getConnectionObject(String name) {
        return connectionObjects.get(name);
    }

    /**
     * @return Get the connection object that this field points to.
     */
    public ConnectionObjectDefinition getConnectionObject(GenerationField field) {
        return connectionObjects.get(field.getTypeName());
    }

    /**
     * @return Map of all the exceptions in the schema by name.
     */
    public Map<String, ExceptionDefinition> getExceptions() {
        return exceptions;
    }

    /**
     * @return Does this name belong to an exception type in the schema?
     */
    public boolean isException(String name) {
        return exceptions.containsKey(name);
    }

    /**
     * @return Get an exception with this name.
     */
    public ExceptionDefinition getException(String name) {
        return exceptions.get(name);
    }


    /**
     * @return Map of all the input type objects in the schema by name.
     */
    public Map<String, InputDefinition> getInputTypes() {
        return inputs;
    }

    /**
     * @return Does this name belong to an input type in the schema?
     */
    public boolean isInputType(String name) {
        return inputs.containsKey(name);
    }

    /**
     * @return Does this field point to an input type in the schema?
     */
    public boolean isInputType(FieldSpecification field) {
        return inputs.containsKey(field.getTypeName());
    }

    /**
     * @return Does this name point to a type that has a table set?
     */
    public boolean hasJOOQRecord(String name) {
        return tableTypesWithTable.contains(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
    }

    /**
     * @return Does this field point to a type that has a table set?
     */
    public boolean hasJOOQRecord(GenerationField field) {
        return hasJOOQRecord(field.getTypeName());
    }

    /**
     * @return Does this field point to an input type with a table set in the schema?
     */
    public boolean hasInputJOOQRecord(GenerationField field) {
        return Optional.ofNullable(getInputType(field)).map(InputDefinition::hasTable).orElse(false);
    }

    /**
     * @return Does this field point to an input type with a Java record set in the schema?
     */
    public boolean hasJavaRecord(GenerationField field) {
        return Optional.ofNullable(getRecordType(field)).map(RecordObjectSpecification::hasJavaRecordReference).orElse(false);
    }

    /**
     * @return Does this field point to an input type with a record set in the schema?
     */
    public boolean hasRecord(GenerationField field) {
        return Optional.ofNullable(getRecordType(field)).map(RecordObjectSpecification::hasRecordReference).orElse(false);
    }

    /**
     * @return Get an input type with this name.
     */
    public InputDefinition getInputType(String name) {
        return inputs.get(name);
    }

    /**
     * @return Get the input type that this field points to.
     */
    public InputDefinition getInputType(GenerationField field) {
        return inputs.get(field.getTypeName());
    }

    /**
     * @return Does this name belong to a union type in the schema?
     */
    public boolean isUnion(String name) {
        return unions.containsKey(name) || isConnectionObject(name) && isUnion(getConnectionObject(name).getNodeType());
    }

    /**
     * @return Does this field belong to a union type in the schema?
     */
    public boolean isUnion(FieldSpecification field) {
        return isUnion(field.getTypeName());
    }

    /**
     * @return Does this name belong to a union type containing only error types?
     */
    public boolean isExceptionUnion(String name) {
        if (!isUnion(name)) {
            return false;
        }
        return getUnion(name).getFieldTypeNames().stream().allMatch(this::isException); // What if only some match?
    }

    /**
     * @return Does this name belong to an exception type or a union type containing only error types?
     */
    public boolean isExceptionOrExceptionUnion(String name) {
        return isException(name) || isExceptionUnion(name);
    }

    /**
     * @return Does this field belong to an exception type or a union type containing only error types?
     */
    public boolean isExceptionOrExceptionUnion(FieldSpecification field) {
        return isExceptionOrExceptionUnion(field.getTypeName());
    }

    /**
     * @return Get a union type with this name.
     */
    public UnionDefinition getUnion(String name) {
        return unions.get(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
    }

    /**
     * @return Get a union type that this field points to.
     */
    public UnionDefinition getUnion(GenerationField field) {
        return getUnion(field.getTypeName());
    }

    /**
     * @return Get all union types keyed by name.
     */
    public Map<String, UnionDefinition> getUnions() {
        return unions;
    }

    /**
     * @return Does this name belong to a scalar in the schema?
     */
    public boolean isScalar(String name) {
        return scalarTypes.contains(name);
    }

    /**
     * @return The Query type.
     */
    public ObjectDefinition getQueryType() {
        return queryType;
    }

    /**
     * @return The Mutation type, if it exists.
     */
    public ObjectDefinition getMutationType() {
        return mutationType;
    }

    /**
     * @return Does this name point to a type that may have a record set?
     */
    public boolean isRecordType(String name) {
        return recordTypes.containsKey(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
    }

    /**
     * @return Does this field point to a type that may have a record set?
     */
    public boolean isRecordType(FieldSpecification field) {
        return isRecordType(field.getTypeName());
    }

    /**
     * @return Find the type this field refers to.
     */
    public RecordObjectSpecification<?> getRecordType(String name) {
        return recordTypes.get(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
    }

    /**
     * @return Find the type this field refers to.
     */
    public RecordObjectSpecification<?> getRecordType(GenerationField field) {
        return getRecordType(field.getTypeName());
    }

    /**
     * @return Does this schema use the _entities field?
     */
    public boolean hasEntitiesField() {
        return queryType != null && queryType.hasField(FEDERATION_ENTITIES_FIELD.getName());
    }

    /**
     * @return The _entities field in the Query type, if it exists.
     */
    public ObjectField getEntitiesField() {
        return queryType.getFieldByName(FEDERATION_ENTITIES_FIELD.getName());
    }

    /**
     * @return All types which could potentially have tables.
     */
    public Map<String, RecordObjectSpecification<? extends GenerationField>> getRecordTypes() {
        return recordTypes;
    }

    /**
     * @return Is this field the federation _service field?
     */
    public boolean isFederationService(GenerationField target) {
        return target.getName().equals(FEDERATION_SERVICE_FIELD.getName())
                && target.getTypeName().equals(FEDERATION_SERVICE_TYPE.getName())
                && target.getContainerTypeName().equals(SCHEMA_QUERY.getName());
    }

    /**
     * @param object Object that should be mapped to a connection object.
     * @return The original object transformed to a connection object.
     */
    private ConnectionObjectDefinition getSearchObjectDefinitionFor(ObjectTypeDefinition object) {
        var nodeFieldType = object
                .getFieldDefinitions()
                .stream()
                .filter(it -> it.getName().equalsIgnoreCase(GraphQLReservedName.CONNECTION_EDGE_FIELD.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(object.getName() + " has no '" + GraphQLReservedName.CONNECTION_EDGE_FIELD.getName() + "' field."))
                .getType();
        var edge = new EdgeObjectDefinition(objects.get(new FieldType(nodeFieldType).getName()).getTypeDefinition());
        return new ConnectionObjectDefinition(object, edge);
    }

    /**
     * @return The enum definition representing the OrderByField of the given orderInputField
     */
    public OrderByEnumDefinition getOrderByFieldEnum(InputField orderInputField) {
        return Optional.ofNullable(getInputType(orderInputField))
                .orElseThrow(() -> new IllegalArgumentException(String.format("Input type '%s' not found in schema", orderInputField.getTypeName())))
                .getFields().stream()
                .filter(it -> it.getName().equals(GraphQLReservedName.ORDER_BY_FIELD.getName()))
                .map(this::getEnum)
                .map(OrderByEnumDefinition::from)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format("Expected field '%s' on type %s, but no such field was found", GraphQLReservedName.ORDER_BY_FIELD, orderInputField.getTypeName())));
    }

    /**
     * @return The closest table on or above this object. Assumes only one table can be associated with the object.
     */
    public RecordObjectSpecification<?> getPreviousTableObjectForObject(RecordObjectSpecification<?> object) {
        return objectWithPreviousTable.get(object.getName());
    }

    /**
     * Create a map of which objects should be related to which tables. Note that this enforces that each type is only connected to one table.
     */
    private void buildPreviousTableMap(RecordObjectSpecification<?> object) {
        buildPreviousTableMap(object, null, new HashSet<>(), 0);
    }

    private void buildPreviousTableMap(RecordObjectSpecification<?> object, RecordObjectSpecification<?> lastObject, HashSet<String> seenObjects, int recursion) {
        recursionCheck(recursion);
        if (!seenObjects.add(object.getName())) {
            return;
        }

        var tableObject = object.hasTable() ? object : lastObject;
        if (tableObject != null) {
            objectWithPreviousTable.put(object.getName(), tableObject);
        }

        object
                .getFields()
                .stream()
                .filter(this::isRecordType)
                .map(this::getRecordType)
                .filter(Objects::nonNull)
                .filter(it -> !objectWithPreviousTable.containsKey(it.getName()))
                .forEach(it -> buildPreviousTableMap(it, tableObject, seenObjects, recursion + 1));

        object
                .getFields()
                .stream()
                .filter(this::isInterface)
                .map(this::getInterface)
                .map(this::getImplementationsForInterface)
                .flatMap(Collection::stream)
                .filter(it -> !objectWithPreviousTable.containsKey(it.getName()))
                .forEach(it -> buildPreviousTableMap(it, tableObject, seenObjects, recursion + 1));
    }

    /**
     * @return List of all error types this type contains.
     */
    @NotNull
    public List<ObjectField> getAllErrors(String typeName) {
        return getObject(typeName)
                .getFields()
                .stream()
                .filter(this::isExceptionOrExceptionUnion)
                .collect(Collectors.toList());
    }

    /**
     * @return The error type or union of error types with this name if it exists.
     */
    @NotNull
    public AbstractObjectDefinition<?, ?> getErrorTypeDefinition(String name) {
        return isUnion(name) ? getUnion(name) : getException(name);
    }

    /**
     * @return List of exception definitions that exists for this type name. If it is not a union, the list will only have one element.
     */
    @NotNull
    public List<ExceptionDefinition> getExceptionDefinitions(String name) {
        if (!isUnion(name)) {
            return List.of(getException(name));
        }

        return getUnion(name)
                .getFieldTypeNames()
                .stream()
                .map(this::getException)
                .collect(Collectors.toList());
    }

    /**
     * @return List of fields in the schema that may be used to generate transforms.
     */
    public List<GenerationField> getTransformableFields() {
        return transformableFields;
    }

    private List<GenerationField> findTransformableFields() {
        var fields = new ArrayList<GenerationField>();
        var query = getQueryType();
        if (query != null && !query.isExplicitlyNotGenerated()) {
            query
                    .getFields()
                    .stream()
                    .flatMap(field -> findTransformableFields(field, false).stream())
                    .forEach(fields::add);
        }

        var mutation = getMutationType();
        if (mutation != null && !mutation.isExplicitlyNotGenerated()) {
            mutation
                    .getFields()
                    .stream()
                    .flatMap(field -> findTransformableFields(field, true).stream())
                    .forEach(fields::add);
        }
        return new ArrayList<>(
                fields
                        .stream()
                        .filter(this::isRecordType)
                        .collect(Collectors.toMap(this::getRecordType, Function.identity(), (it1, it2) -> it1)) // Filter duplicates if multiple fields use the same record type.
                        .values()
        );
    }

    /**
     * @param field Field that are to be searched.
     * @return List of fields that points to a type with the table or record directive set.
     */
    private List<GenerationField> findTransformableFields(ObjectField field, boolean isMutation) {
        // Note, do not check for conditions in this variable, it should not apply to object fields. Conditions should not affect return type mapping!
        var canMapTable = isMutation || field.hasServiceReference();

        var objects = findTransformableFields(field, new HashSet<>(), isMutation, canMapTable, false, false, 0).stream();
        var inputs = field
                .getArguments()
                .stream()
                .flatMap(it -> findTransformableFields(it, new HashSet<>(), isMutation, canMapTable || hasRecord(it), false, field.hasCondition(), 0).stream());
        return Stream.concat(objects, inputs).collect(Collectors.toList());
    }

    private List<GenerationField> findTransformableFields(
            GenerationField field,
            HashSet<GenerationField> seen,
            boolean isMutation,
            boolean canMapTable,
            boolean hadTable,
            boolean hadMappableInputConfiguration,
            int recursion
    ) {
        recursionCheck(recursion);
        if (seen.contains(field)) {
            return List.of();
        }
        seen.add(field);

        var hasService = field.hasServiceReference();
        var hasMutationType = field.hasMutationType();
        var type = getRecordType(field);
        if (!field.isInput() && hasMutationType || field.isResolver() && isMutation && !hasService || type == null) {
            return List.of();
        }

        var hasCondition = field.hasCondition();
        var canMapTableHere = isMutation || hasService || hasCondition && field.isInput() || type.hasJavaRecordReference() || canMapTable || hadMappableInputConfiguration;
        var notAlreadyWrappedInTable = !hadTable || !type.hasTable() || hadMappableInputConfiguration;
        var array = new ArrayList<GenerationField>();
        if ((type.hasRecordReference() || hasService && !hasMutationType) && !field.isExplicitlyNotGenerated() && notAlreadyWrappedInTable && canMapTableHere) {
            array.add(field);
        }

        if (!field.isInput()) {
            array.addAll(((ObjectField) field).getArguments().stream().flatMap(it ->
                    findTransformableFields(
                            it,
                            seen,
                            false,
                            !(hadTable || type.hasTable()),
                            hadTable || type.hasTable(),
                            true,
                            recursion + 1
                    ).stream()).collect(Collectors.toList())
            );
        }

        array.addAll(type.getFields().stream().flatMap(it ->
                findTransformableFields(
                        it,
                        seen,
                        isMutation,
                        canMapTableHere && !(hadTable || type.hasTable()),
                        !canMapTableHere && (hadTable || type.hasTable()) && !it.isResolver(),
                        false,
                        recursion + 1
                ).stream()).collect(Collectors.toList())
        );

        return array;
    }
}
