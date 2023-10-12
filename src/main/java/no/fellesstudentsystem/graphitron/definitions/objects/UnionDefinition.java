package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a GraphQL union type.
 */
public class UnionDefinition extends AbstractObjectDefinition<UnionTypeDefinition, ObjectField> {
    private final List<String> fieldTypeNames;

    public UnionDefinition(UnionTypeDefinition typeDefinition) {
        super(typeDefinition);
        fieldTypeNames = typeDefinition
                .getMemberTypes()
                .stream()
                .map(it -> ((TypeName)it).getName())
                .collect(Collectors.toList());
    }

    /**
     * @return List of type names this union consists of.
     */
    public List<String> getFieldTypeNames() {
        return fieldTypeNames;
    }

    public List<ObjectField> getFields() {
        return List.of();
    }

    /**
     * Creates instances of this class for each of the {@link UnionTypeDefinition} provided.
     * @return List of ObjectDefinitions.
     */
    public static List<UnionDefinition> processUnionDefinitions(List<UnionTypeDefinition> objects) {
        return objects.stream().map(UnionDefinition::new).collect(Collectors.toList());
    }
}
