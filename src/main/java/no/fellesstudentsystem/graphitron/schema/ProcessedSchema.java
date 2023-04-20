package no.fellesstudentsystem.graphitron.schema;

import graphql.language.*;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.fellesstudentsystem.graphitron.definitions.fields.FieldType;
import no.fellesstudentsystem.graphitron.definitions.interfaces.ObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.objects.*;
import no.fellesstudentsystem.graphitron.validation.DirectiveDefinitionsValidator;
import no.fellesstudentsystem.graphitron.validation.ProcessedDefinitionsValidator;
import no.fellesstudentsystem.graphql.mapping.GraphQLReservedName;
import no.fellesstudentsystem.graphql.mapping.GenerationDirective;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition.processObjectDefinitions;
import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.SCHEMA_ROOT_NODE_MUTATION;
import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.SCHEMA_ROOT_NODE_QUERY;

/**
 * This class represents a fully processed GraphQL schema.
 */
public class ProcessedSchema {
    private final Map<String, EnumDefinition> enums;
    private final Map<String, ObjectDefinition> objects;
    private final Map<String, InputDefinition> inputs;
    private final Map<String, InterfaceDefinition> interfaces;
    private final Map<String, ConnectionObjectDefinition> connectionObjects;
    private final Map<String, UnionDefinition> unions;
    private final Set<String> objectsWithTableOrConnection;
    private final ObjectDefinition queryType;
    private final ObjectDefinition mutationType;


    public ProcessedSchema(TypeDefinitionRegistry typeRegistry) {
        new DirectiveDefinitionsValidator(typeRegistry.getDirectiveDefinitions()).warnMismatchedDirectives();

        var objectTypes = typeRegistry.getTypes(ObjectTypeDefinition.class);
        enums = EnumDefinition.processEnumDefinitions(typeRegistry.getTypes(EnumTypeDefinition.class))
                .stream()
                .collect(Collectors.toMap(EnumDefinition::getName, Function.identity()));

        objects = processObjectDefinitions(objectTypes)
                .stream()
                .collect(Collectors.toMap(ObjectDefinition::getName, Function.identity()));

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

        new ProcessedDefinitionsValidator(this)
                .validateThatProcessedDefinitionsConformToDatabaseNaming();
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
    public Boolean isEnum(String name) {
        return enums.containsKey(name);
    }

    /**
     * @return Get an enum with this name.
     */
    public EnumDefinition getEnum(String name) {
        return enums.get(name);
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
    public Boolean isInterface(String name) {
        return interfaces.containsKey(name);
    }

    /**
     * @return Get an interface with this name.
     */
    public InterfaceDefinition getInterface(String name) {
        return interfaces.get(name);
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
    public Boolean isObject(String name) {
        return objects.containsKey(name);
    }

    /**
     * @return Get an object with this name.
     */
    public ObjectDefinition getObject(String name) {
        return objects.get(name);
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
    public Boolean isConnectionObject(String name) {
        return connectionObjects.containsKey(name);
    }

    /**
     * @return Get a connection object with this name.
     */
    public ConnectionObjectDefinition getConnectionObject(String name) {
        return connectionObjects.get(name);
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
    public Boolean isInputType(String name) {
        return inputs.containsKey(name);
    }

    /**
     * @return Get an input type with this name.
     */
    public InputDefinition getInputType(String name) {
        return inputs.get(name);
    }

    /**
     * @return Does this name belong to a union type in the schema?
     */
    public Boolean isUnion(String name) {
        return unions.containsKey(name);
    }

    /**
     * @return Get a union type with this name.
     */
    public UnionDefinition getUnion(String name) {
        return unions.get(name);
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
     * "{@link GenerationDirective#NODE table}" directive set and root objects.
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
                .filter(it -> it.getName().equalsIgnoreCase(GraphQLReservedName.PAGINATION_EDGE_FIELD.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(object.getName() + " has no '" + GraphQLReservedName.PAGINATION_EDGE_FIELD.getName() + "' field."))
                .getType();
        var edge = new EdgeObjectDefinition(objects.get(new FieldType(nodeFieldType).getName()).getTypeDefinition());
        return new ConnectionObjectDefinition(object, edge);
    }
}
