package no.sikt.graphql.schema;

import graphql.language.*;
import graphql.language.SchemaDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.AbstractField;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.InputField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldType;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.interfaces.ObjectSpecification;
import no.sikt.graphitron.definitions.interfaces.RecordObjectSpecification;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
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
    private final Map<String, ObjectDefinition> nodeTypes;
    private final Map<String, InterfaceDefinition> interfaces;
    private final Map<String, ConnectionObjectDefinition> connectionObjects;
    private final Map<String, EdgeObjectDefinition> edgeObjects;
    private final Map<String, UnionDefinition> unions;
    private final Set<String> tableTypesWithTable, scalarTypes, typeNames, validFieldTypes;
    private final ObjectDefinition queryType, mutationType;
    private final Map<String, RecordObjectSpecification<?>> objectWithPreviousTable;
    private final no.sikt.graphitron.definitions.objects.SchemaDefinition rootSchemaObject;
    private final List<GenerationField> transformableFields;
    private final List<ObjectDefinition> unreferencedObjects;
    private final boolean nodeExists;

    // Graphitron-provided special inputs. Should be excluded from certain operations.
    private final Set<String> specialInputs = Set.of("ExternalCodeReference", "ReferenceElement", "ErrorHandler", "ReferencesForType");

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

        rootSchemaObject = isObject(SCHEMA_QUERY.getName()) || isObject(SCHEMA_MUTATION.getName())
                ? new no.sikt.graphitron.definitions.objects.SchemaDefinition(createSchemaDefinition())
                : null;
        queryType = rootSchemaObject != null && rootSchemaObject.getQuery() != null ? getObject(rootSchemaObject.getQuery()) : null;
        mutationType = rootSchemaObject != null && rootSchemaObject.getMutation() != null ? getObject(rootSchemaObject.getMutation()) : null;

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

        edgeObjects = connectionObjects
                .values()
                .stream()
                .map(ConnectionObjectDefinition::getEdgeObject)
                .collect(Collectors.toMap(EdgeObjectDefinition::getName, Function.identity()));

        unions = UnionDefinition.processUnionDefinitions(typeRegistry.getTypes(UnionTypeDefinition.class))
                .stream()
                .collect(Collectors.toMap(UnionDefinition::getName, Function.identity()));

        recordTypes = Stream
                .concat(Stream.concat(objects.values().stream(), inputs.values().stream()),
                        Stream.concat(interfaces.values().stream(), unions.values().stream().filter(it -> !isExceptionUnion(it.getName()))))
                .collect(Collectors.toMap(AbstractObjectDefinition::getName, Function.identity()));
        tableTypesWithTable = recordTypes
                .values()
                .stream()
                .filter(RecordObjectSpecification::hasTable)
                .map(RecordObjectSpecification::getName)
                .collect(Collectors.toSet());
        nodeTypes = objects.values().stream().filter(RecordObjectDefinition::hasNodeDirective)
                .collect(Collectors.toMap(AbstractObjectDefinition::getName, Function.identity()));

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
        ProcessedDefinitionsValidator processedDefinitionsValidator = new ProcessedDefinitionsValidator(this);
        if (checkTypes) {
            processedDefinitionsValidator.validateObjectFieldTypes();
        }
        processedDefinitionsValidator.validateDirectiveUsage();
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
     * @return Does this field return a multi-table interface type in the schema?
     */
    public boolean isMultiTableInterface(GenerationField field) {
        return isMultiTableInterface(field.getTypeName());
    }

    /**
    * @return Does this name belong to a multi-table interface type in the schema?
    */
    public boolean isMultiTableInterface(String name) {
        return isInterface(name) && getInterface(name).isMultiTableInterface();
    }

    /**
     * @return Does this field return a single table interface type in the schema?
     */
    public boolean isSingleTableInterface(GenerationField field) {
        return isSingleTableInterface(field.getTypeName());
    }

    /**
     * @return Does this name belong to a single table interface type in the schema?
     */
    public boolean isSingleTableInterface(String name) {
        return isInterface(name) && !getInterface(name).isMultiTableInterface();
    }

    /**
     * @return Does this field return rows from multiple tables?
     */
    public boolean isMultiTableField(GenerationField field) {
        return isMultiTableInterface(field)|| isUnion(field);
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
            return getUnionSubTypes(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
        }
        if (isInterface(name)) {
            return getImplementationsForInterface(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
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

    public boolean isNodeType(String name) {
        return nodeTypes.containsKey(name);
    }

    public ObjectDefinition getNodeType(String name) {
        return nodeTypes.get(name);
    }

    public List<ObjectDefinition> getNodeTypesWithTable(JOOQMapping table) {
        return nodeTypes.values().stream()
                .filter(it -> it.getTable().equals(table))
                .toList();
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
     * @return Does this name belong to an edge object type in the schema?
     */
    public boolean isEdgeObject(String name) {
        return edgeObjects.containsKey(name);
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
     * @return Does this type name point to a type with a Java record?
     */
    public boolean hasJavaRecord(String typeName) {
        return Optional.ofNullable(getRecordType(typeName)).map(RecordObjectSpecification::hasJavaRecordReference).orElse(false);
    }

    /**
     * @return Does this field point to a type with a Java record set in the schema?
     */
    public boolean hasJavaRecord(GenerationField field) {
        return hasJavaRecord(field.getTypeName());
    }

    /**
     * @return Does this field point to an input type with a record set in the schema?
     */
    public boolean hasRecord(GenerationField field) {
        return Optional.ofNullable(getRecordType(field)).map(RecordObjectSpecification::hasRecordReference).orElse(false);
    }

    /**
     * @return Is this an ordered multi-key query?
     */
    public boolean isOrderedMultiKeyQuery(GenerationField field) {
        RecordObjectSpecification<?> type = getRecordType(field.getTypeName());
        boolean hasTable = type != null && type.hasTable();
        return field.isIterableWrapped() && field.isResolver()
                && (hasJavaRecord(field.getContainerTypeName()) || !isObjectWithPreviousTableObject(field.getContainerTypeName()))
                && hasTable;
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
     * @return Is this field a scalar in the schema?
     */
    public boolean isScalar(GenerationField field) {
        return scalarTypes.contains(field.getTypeName());
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
     * @return The root schema type, if it exists.
     */
    public no.sikt.graphitron.definitions.objects.SchemaDefinition getSchemaType() {
        return rootSchemaObject;
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
     * @return Whether a field is a node ID field using NodeIdStrategy
     */
    public boolean isNodeIdField(GenerationField field) {
        return GeneratorConfig.shouldMakeNodeStrategy() && (field.hasNodeID() || isImplicitNodeIdField(field));
    }

    public boolean isNodeIdReferenceField(GenerationField field) {
        if (isNodeIdField(field)) {
            if (isNodeIdForNodeTypeWithSameTable(field)) {
                return false;
            }

            return field.hasFieldReferences() || Optional.ofNullable(getNodeTypeForNodeIdField(field)).map(n -> !n.getName().equals(field.getContainerTypeName())).orElse(false);
        }
        return false;
    }

    /**
     * Is this a node ID field in a non-node type that shares the same table with another node type?
     * @return Whether this is a field creating a node ID for another node type from the current table
     */
    public boolean isNodeIdForNodeTypeWithSameTable(GenerationField field) {
        if (!field.hasNodeID() || field.hasFieldReferences()) {
            return false;
        }

        var containerType = getRecordType(field.getContainerTypeName());
        if (containerType == null || containerType.hasNodeDirective()) {
            return false;
        }

        var localTable = containerType.hasTable()
                ? Optional.of(containerType.getTable())
                : Optional.ofNullable(getPreviousTableObjectForObject(containerType)).map(RecordObjectSpecification::getTable);


        var nodeObject = Optional.ofNullable(getObject(field.getNodeIdTypeName()));
        return nodeObject.isPresent() && localTable.filter(t -> nodeObject.get().getTable().equals(t)).isPresent();

    }

    /**
     * @param field the {@link GenerationField} to check
     * @return Whether a field implicitly is a node ID field using NodeIdStrategy
     */
    private boolean isImplicitNodeIdField(GenerationField field) {
        return field.isID()
                && !field.hasNodeID() // Because we're not interested in explicit node ID here
                && field.getName().equals(NODE_ID.getName())
                && (isNodeType(field.getContainerTypeName()) || getImplicitNodeTypeForField(field).isPresent());
    }

    /**
     * @param field the {@link GenerationField} for which to find the implicit node type
     * @return the matching {@link RecordObjectSpecification}, or {@code null} if none found
     */
    private Optional<ObjectDefinition> getImplicitNodeTypeForField(GenerationField field) {
        var types = Optional.ofNullable(getRecordType(field.getContainerTypeName()))
                .map(RecordObjectSpecification::getTable)
                .map(this::getNodeTypesWithTable)
                .orElse(List.of());
        if (types.size() != 1) {
            return Optional.empty();
        }
        return types.stream().findFirst();
    }

    /**
     * @param field the {@link GenerationField} to resolve the node type for
     * @return the corresponding {@link RecordObjectSpecification}, or {@code null} if not found
     */
    public ObjectDefinition getNodeTypeForNodeIdField(GenerationField field) {
        return field.hasNodeID() ? getNodeType(field.getNodeIdTypeName())
                : getImplicitNodeTypeForField(field).orElse(null);
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
    * @return Returns whether the object has a table on or above it.
    * */
    public boolean hasTableObjectForObject(RecordObjectSpecification<?> object) {
        return objectWithPreviousTable.containsKey(object.getName());
    }

    /*
     * @return Returns whether name belongs to an object with a previous table
     * */
    public boolean isObjectWithPreviousTableObject(String name) {
        return isObject(name) && objectWithPreviousTable.containsKey(name);
    }

    public boolean isObjectOrConnectionNodeWithPreviousTableObject(String name) {
        return (isObject(name) || isConnectionObject(name)) && objectWithPreviousTable.containsKey(name);
    }

    public boolean isReferenceResolverField(ObjectField field) {
        return field.isResolver() && isObjectOrConnectionNodeWithPreviousTableObject(field.getContainerTypeName());
    }

    public boolean returnsList(ObjectField field) {
        return field.isIterableWrapped() || field.hasPagination();
    }

    /**
     * Simple method that tries to find a table reference in the input records.
     * This is not very robust, but we need this to not break existing things.
     * @return Table mapping for this context based on input records, if any exists.
     */
    public List<JOOQMapping> findInputTables(GenerationField field) {
        if (!(field instanceof ObjectField objectField)) {
            return List.of();
        }

        return objectField
                .getArguments()
                .stream()
                .filter(this::isRecordType)
                .map(this::getRecordType)
                .filter(RecordObjectSpecification::hasTable)
                .map(RecordObjectSpecification::getTable)
                .distinct()
                .toList();
    }

    /**
     * Finds the target field which the returned data from mutation should be outputted.
     * In the case of the mutation field being a wrapper type, this method will find the correct field inside the wrapper type which will contain data.
     * This method assumes there is maximum one level of nesting.
     * @param initialTarget The mutation field
     * @return The field which should contain the return data
     */
    public Optional<ObjectField> inferDataTargetForMutation(ObjectField initialTarget) {
        if (isScalar(initialTarget) || (isObject(initialTarget) && getObject(initialTarget).hasTable())) {
            return Optional.of(initialTarget);
        }

        var possibleMatches = getObject(initialTarget)
                .getFields()
                .stream()
                .filter(it -> !it.getName().equals(ERROR_FIELD.getName()))
                .toList();
        return possibleMatches.size() == 1 ? Optional.of(possibleMatches.get(0)) : Optional.empty();
    }

    /**
     * Simple method that tries to find a table reference the field's type.
     * @return Table mapping for this context based on the contents of the field's type records, if any exists.
     */
    public boolean nextTypeTableExists(GenerationField field, Set<String> seen) {
        if (!(field instanceof ObjectField objectField) || !isRecordType(objectField) || seen.contains(field.getName() + field.getContainerTypeName())) {
            return false;
        }

        var type = getRecordType(field);
        if (type.hasTable()) {
            return true;
        }
        seen.add(field.getName() + field.getContainerTypeName());

        return getRecordType(objectField)
                .getFields()
                .stream()
                .filter(this::isRecordType)
                .anyMatch(it -> nextTypeTableExists(it, seen));
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

    public Map<String, no.sikt.graphitron.javapoet.TypeName> getAllContextFields(GenerationField field) {
        var otherFields = field.getContextFields();
        var argumentFields = findNestedContextFields(!(field instanceof ObjectField) ? List.of() : ((ObjectField) field).getArguments(), 0);
        return Stream
                .concat(otherFields.entrySet().stream(), argumentFields)
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y, LinkedHashMap::new));
    }

    private Stream<Map.Entry<String, no.sikt.graphitron.javapoet.TypeName>> findNestedContextFields(List<? extends InputField> fields, int recursion) {
        recursionCheck(recursion);
        if (fields.isEmpty()) {
            return Stream.of();
        }
        var nextFields = fields
                .stream()
                .filter(this::isInputType)
                .map(this::getInputType)
                .map(AbstractObjectDefinition::getFields)
                .flatMap(Collection::stream)
                .toList();
        var foundContext = fields.stream()
                .map(GenerationSourceField::getContextFields)
                .map(Map::entrySet)
                .flatMap(Collection::stream);
        return Stream.concat(foundContext, findNestedContextFields(nextFields, recursion + 1));
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
                    ).stream()).toList()
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
                ).stream()).toList()
        );

        return array;
    }
}
