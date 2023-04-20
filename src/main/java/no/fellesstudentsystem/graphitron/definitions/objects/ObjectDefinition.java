package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.fields.TopLevelObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQTableMapping;
import no.fellesstudentsystem.graphql.mapping.GenerationDirective;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.mapping.GenerationDirective.NODE;
import static no.fellesstudentsystem.graphql.mapping.GraphQLDirectiveParam.TABLE;
import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.SCHEMA_ROOT_NODE_MUTATION;
import static no.fellesstudentsystem.graphql.mapping.GraphQLReservedName.SCHEMA_ROOT_NODE_QUERY;
import static no.fellesstudentsystem.graphql.schema.SchemaHelpers.getOptionalDirectiveArgumentString;

/**
 * Represents the default GraphQL object.
 * Objects which do not fall within a different object category will become instances of this class.
 * This is typically the only object type used in table referencing and joining operations.
 */
public class ObjectDefinition extends AbstractObjectDefinition<ObjectTypeDefinition> implements GenerationTarget {
    private final JOOQTableMapping table;
    private final boolean hasTable;
    private final boolean isGenerated;
    private final boolean isRoot;
    private final List<ObjectField> objectFields;
    private final Set<String> implementsInterfaces;


    public ObjectDefinition(ObjectTypeDefinition objectDefinition) {
        super(objectDefinition);
        hasTable = objectDefinition.hasDirective(NODE.getName());
        if (hasTable) {
            table = new JOOQTableMapping(
                    getOptionalDirectiveArgumentString(objectDefinition, NODE, NODE.getParamName(TABLE))
                            .orElse(getName().toUpperCase())
            );
        } else {
            table = null;
        }

        isRoot = getName().equalsIgnoreCase(SCHEMA_ROOT_NODE_QUERY.getName())
                || getName().equalsIgnoreCase(SCHEMA_ROOT_NODE_MUTATION.getName());

        var definitions = objectDefinition.getFieldDefinitions();
        objectFields = isRoot ? TopLevelObjectField.from(definitions) : ObjectField.from(definitions);

        isGenerated = getFields().stream().anyMatch(ObjectField::isGenerated);

        implementsInterfaces = objectDefinition.getImplements().stream()
                .map(it -> ((TypeName) it).getName())
                .collect(Collectors.toSet());
    }

    /**
     * @return Table objects which holds table names.
     */
    public JOOQTableMapping getTable() {
        return table;
    }

    /**
     * @return Does this object have the "{@link GenerationDirective#NODE table}" directive
     * which implies a connection to a database table?
     */
    public boolean hasTable() {
        return hasTable;
    }

    /**
     * @return The exact name of the database table that this object corresponds to.
     */
    public List<ObjectField> getFields() {
        return objectFields;
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

    public Set<String> implementsInterfaces() {
        return implementsInterfaces;
    }

    /**
     * @return Does this object implement this interface?
     */
    public boolean implementsInterface(String interfaceName) {
        return implementsInterfaces.contains(interfaceName);
    }
}
