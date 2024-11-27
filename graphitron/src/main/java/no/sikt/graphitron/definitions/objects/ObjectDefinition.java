package no.sikt.graphitron.definitions.objects;

import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.fields.TopLevelObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;

/**
 * Represents the default GraphQL object.
 * Objects which do not fall within a different object category will become instances of this class.
 * This is typically the only object type used in table referencing and joining operations.
 */
public class ObjectDefinition extends RecordObjectDefinition<ObjectTypeDefinition, ObjectField> {
    private final boolean isRoot, hasResolvers;
    private final LinkedHashSet<String> implementsInterfaces;
    private final ObjectTypeDefinition objectTypeDefinition;

    public ObjectDefinition(ObjectTypeDefinition objectDefinition) {
        super(objectDefinition);

        isRoot = isRootType(objectDefinition);
        hasResolvers = getFields().stream().anyMatch(GenerationTarget::isGeneratedWithResolver);
        implementsInterfaces = objectDefinition.getImplements().stream()
                .map(it -> ((TypeName) it).getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        objectTypeDefinition = objectDefinition;
    }

    @Override
    protected List<ObjectField> createFields(ObjectTypeDefinition objectDefinition) {
        var definitions = objectDefinition.getFieldDefinitions();
        return isRootType(objectDefinition) ? TopLevelObjectField.from(definitions, getName()) : ObjectField.from(definitions, getName());
    }

    private boolean isRootType(ObjectTypeDefinition objectDefinition) {
        return objectDefinition.getName().equalsIgnoreCase(SCHEMA_QUERY.getName())
                || objectDefinition.getName().equalsIgnoreCase(SCHEMA_MUTATION.getName());
    }

    /**
     * @return Is this object the top node? That should be either the Query or the Mutation type.
     */
    @Override
    public boolean isOperationRoot() {
        return isRoot;
    }

    /**
     * @return The original interpretation of this object as provided by GraphQL.
     */
    public ObjectTypeDefinition getTypeDefinition() {
        return objectTypeDefinition;
    }

    @Override
    public boolean isGeneratedWithResolver() {
        return hasResolvers;
    }

    /**
     * Creates instances of this class for each of the {@link ObjectTypeDefinition} provided.
     * @return List of ObjectDefinitions.
     */
    public static List<ObjectDefinition> processObjectDefinitions(List<ObjectTypeDefinition> objects) {
        return objects
                .stream()
                .map(ObjectDefinition::new)
                .collect(Collectors.toList());
    }

    /**
     * @return Does this object implement this interface?
     */
    public boolean implementsInterface(String interfaceName) {
        return implementsInterfaces.contains(interfaceName);
    }
}
