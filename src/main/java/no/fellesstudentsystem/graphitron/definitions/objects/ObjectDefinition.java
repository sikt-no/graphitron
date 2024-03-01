package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.fields.TopLevelObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.NOT_GENERATED;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.SCHEMA_ROOT_NODE_MUTATION;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.SCHEMA_ROOT_NODE_QUERY;


/**
 * Represents the default GraphQL object.
 * Objects which do not fall within a different object category will become instances of this class.
 * This is typically the only object type used in table referencing and joining operations.
 */
public class ObjectDefinition extends RecordObjectDefinition<ObjectTypeDefinition, ObjectField> implements GenerationTarget {
    private final boolean isGenerated, isRoot;
    private final LinkedHashSet<String> implementsInterfaces;
    private final ObjectTypeDefinition objectTypeDefinition;
    private final boolean explicitlyNotGenerated;

    public ObjectDefinition(ObjectTypeDefinition objectDefinition) {
        super(objectDefinition);

        isRoot = isRootType(objectDefinition);
        isGenerated = getFields().stream().anyMatch(ObjectField::isGenerated);
        implementsInterfaces = objectDefinition.getImplements().stream()
                .map(it -> ((TypeName) it).getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        objectTypeDefinition = objectDefinition;
        explicitlyNotGenerated = objectDefinition.hasDirective(NOT_GENERATED.getName());
    }

    public boolean isExplicitlyNotGenerated() {
        return explicitlyNotGenerated;
    }

    @Override
    protected List<ObjectField> createFields(ObjectTypeDefinition objectDefinition) {
        var definitions = objectDefinition.getFieldDefinitions();
        return isRootType(objectDefinition) ? TopLevelObjectField.from(definitions) : ObjectField.from(definitions);
    }

    private boolean isRootType(ObjectTypeDefinition objectDefinition) {
        return objectDefinition.getName().equalsIgnoreCase(SCHEMA_ROOT_NODE_QUERY.getName())
                || objectDefinition.getName().equalsIgnoreCase(SCHEMA_ROOT_NODE_MUTATION.getName());
    }

    public boolean isGenerated() {
        return isGenerated;
    }

    /**
     * @return Is this object the top node? That should be either the Query or the Mutation type.
     */
    public boolean isRoot() {
        return isRoot;
    }

    /**
     * @return The original interpretation of this object as provided by GraphQL.
     */
    public ObjectTypeDefinition getTypeDefinition() {
        return objectTypeDefinition;
    }

    /**
     * @return The fields which refer to any of these named objects.
     */
    public List<ObjectField> getReferredFieldsFromObjectNames(Set<String> objectsNames) {
        return getFields()
                .stream()
                .filter(f -> objectsNames.contains(f.getTypeName()))
                .collect(Collectors.toList());
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
