package no.fellesstudentsystem.graphitron.schema;

import graphql.language.*;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.FieldType;
import no.fellesstudentsystem.graphitron.definitions.interfaces.ObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.objects.*;
import no.fellesstudentsystem.graphitron.validation.ProcessedDefinitionsValidator;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.*;

/**
 * This class represents a fully processed GraphQL schema. This is Graphitron's pre-processing of the schema.
 */
public class ProcessedSchema {
    private final Map<String, EnumDefinition> enums;
    private final Map<String, ObjectDefinition> objects;
    private final Map<String, ExceptionDefinition> exceptions;
    private final Map<String, InputDefinition> inputs;
    private final Map<String, InterfaceDefinition> interfaces;
    private final Map<String, ConnectionObjectDefinition> connectionObjects;
    private final Map<String, UnionDefinition> unions;
    private final Set<String> objectsWithTableOrConnection;
    private final ObjectDefinition queryType;
    private final ObjectDefinition mutationType;

    private final Map<String, ObjectDefinition> objectWithPreviousTable;

    public ProcessedSchema(TypeDefinitionRegistry typeRegistry) {
        var objectTypes = typeRegistry.getTypes(ObjectTypeDefinition.class);
        enums = EnumDefinition.processEnumDefinitions(typeRegistry.getTypes(EnumTypeDefinition.class))
                .stream()
                .collect(Collectors.toMap(EnumDefinition::getName, Function.identity()));

        objects = ObjectDefinition
                .processObjectDefinitions(objectTypes)
                .stream()
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

        queryType = objects.get(SCHEMA_ROOT_NODE_QUERY.getName());
        mutationType = objects.get(SCHEMA_ROOT_NODE_MUTATION.getName());

        inputs = InputDefinition.processInputDefinitions(typeRegistry.getTypes(InputObjectTypeDefinition.class))
                .stream()
                .collect(Collectors.toMap(InputDefinition::getName, Function.identity()));

        interfaces = typeRegistry.getTypes(InterfaceTypeDefinition.class)
                .stream()
                .map(InterfaceDefinition::new)
                .collect(Collectors.toMap(ObjectSpecification::getName, Function.identity()));

        connectionObjects = objectTypes
                .stream()
                .filter(f -> f.getName().endsWith(GraphQLReservedName.SCHEMA_CONNECTION_SUFFIX.getName()))
                .map(this::getSearchObjectDefinitionFor)
                .collect(Collectors.toMap(ConnectionObjectDefinition::getName, Function.identity()));

        var objectsWithTable = objects
                .values()
                .stream()
                .filter(ObjectDefinition::hasTable)
                .map(ObjectDefinition::getName);
        objectsWithTableOrConnection = Stream.concat(connectionObjects.keySet().stream(), objectsWithTable).collect(Collectors.toSet());

        unions = UnionDefinition.processUnionDefinitions(typeRegistry.getTypes(UnionTypeDefinition.class))
                .stream()
                .collect(Collectors.toMap(UnionDefinition::getName, Function.identity()));

        // queryType may be null in certain tests.
        objectWithPreviousTable = new HashMap<>();
        var nodes = objects.values().stream().filter(it -> it.implementsInterface(NODE_TYPE.getName()));
        (queryType != null ? Stream.concat(nodes, Stream.of(queryType)) : nodes).forEach(this::buildPreviousTableMap);
    }

    /**
     * Ensure that the definitions created in this class match database names where applicable.
     */
    public void validate() {
        ProcessedDefinitionsValidator processedDefinitionsValidator = GeneratorConfig.getExtendedFunctionality().createExtensionIfAvailable(ProcessedDefinitionsValidator.class, new Class[]{ProcessedSchema.class}, this);
        processedDefinitionsValidator.validateThatProcessedDefinitionsConformToJOOQNaming();
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
    public boolean isEnum(AbstractField field) {
        return enums.containsKey(field.getTypeName()) || isConnectionObject(field) && isEnum(getConnectionObject(field).getNodeType());
    }

    /**
     * @return Does this field point to an enum type in the schema and does it have the {@link GenerationDirective#ENUM} directive set?
     */
    public boolean isJavaMappedEnum(AbstractField field) {
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
    public EnumDefinition getEnum(AbstractField field) {
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
    public boolean isInterface(AbstractField field) {
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
    public InterfaceDefinition getInterface(AbstractField field) {
        var typeName = field.getTypeName();
        return interfaces.get(isConnectionObject(typeName) ? getConnectionObject(typeName).getNodeType() : typeName);
    }

    /**
     * @return Map of all the objects in the schema by name.
     */
    public Map<String, ObjectDefinition> getObjects() {
        return objects;
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
    public boolean isObject(AbstractField field) {
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
    public ObjectDefinition getObject(AbstractField field) {
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
    public ObjectDefinition getObjectOrConnectionNode(AbstractField field) {
        var typeName = field.getTypeName();
        return getObject(isConnectionObject(typeName) ? getConnectionObject(field).getNodeType() : typeName);
    }

    /**
     * @return Does this name belong to an object type or connection node in the schema that is connected to a database table?
     */
    public boolean isTableObject(String name) {
        return isObject(name) && getObject(name).hasTable() || isConnectionObject(name) && isTableObject(getConnectionObject(name).getNodeType());
    }

    /**
     * @return Does this field point to an object type or connection node in the schema that is connected to a database table?
     */
    public boolean isTableObject(AbstractField field) {
        return isObject(field) && getObject(field).hasTable() || isConnectionObject(field) && isTableObject(getConnectionObject(field).getNodeType());
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
    public boolean implementsNode(AbstractField field) {
        return isObject(field) && getObject(field).implementsInterface(NODE_TYPE.getName());
    }

    /**
     * @return Map of all the objects whose names end in the {@link GraphQLReservedName#SCHEMA_CONNECTION_SUFFIX} in the schema by name.
     */
    public Map<String, ConnectionObjectDefinition> getConnectionObjects() {
        return connectionObjects;
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
    public boolean isConnectionObject(AbstractField field) {
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
    public ConnectionObjectDefinition getConnectionObject(AbstractField field) {
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
    public boolean isInputType(AbstractField field) {
        return inputs.containsKey(field.getTypeName());
    }

    /**
     * @return Does this field point to an input type with a table set in the schema?
     */
    public boolean isTableInputType(AbstractField field) {
        return Optional.ofNullable(getInputType(field)).map(InputDefinition::hasTable).orElse(false);
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
    public InputDefinition getInputType(AbstractField field) {
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
    public boolean isUnion(AbstractField field) {
        return unions.containsKey(field.getTypeName()) || isConnectionObject(field) && isUnion(getConnectionObject(field).getNodeType());
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
     * @return Get a union type with this name.
     */
    public UnionDefinition getUnion(String name) {
        return unions.get(isConnectionObject(name) ? getConnectionObject(name).getNodeType() : name);
    }

    /**
     * @return Get a union type that this field points to.
     */
    public UnionDefinition getUnion(AbstractField field) {
        return unions.get(isConnectionObject(field) ? getConnectionObject(field).getNodeType() : field);
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
     * @return Set of all the objects with the
     * "{@link GenerationDirective#TABLE table}" directive set and root objects.
     */
    public Set<String> getNamesWithTableOrConnections() {
        return objectsWithTableOrConnection;
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
     * @return The closest table on or above this object. Assumes only one table can be associated with the object.
     */
    public ObjectDefinition getPreviousTableObjectForObject(ObjectDefinition object) {
        return objectWithPreviousTable.get(object.getName());
    }

    /**
     * Create a map of which objects should be related to which tables. Note that this enforces that each type is only connected to one table.
     */
    private void buildPreviousTableMap(ObjectDefinition object) {
        buildPreviousTableMap(object, null, new HashSet<>(), 0);
    }

    private void buildPreviousTableMap(ObjectDefinition object, ObjectDefinition lastObject, HashSet<String> seenObjects, int recursion) {
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
                .filter(this::isObject)
                .map(this::getObjectOrConnectionNode)
                .filter(Objects::nonNull)
                .filter(it -> !objectWithPreviousTable.containsKey(it.getName()))
                .forEach(it -> buildPreviousTableMap(it, tableObject, seenObjects, recursion + 1));
    }
}
