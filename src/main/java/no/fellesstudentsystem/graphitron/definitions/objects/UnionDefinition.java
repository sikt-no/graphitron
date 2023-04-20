package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;

import java.util.List;
import java.util.stream.Collectors;

public class UnionDefinition extends AbstractObjectDefinition<UnionTypeDefinition> {
    private final List<String> fieldTypeNames;

    public UnionDefinition(UnionTypeDefinition typeDefinition) {
        super(typeDefinition);
        fieldTypeNames = typeDefinition
                .getMemberTypes()
                .stream()
                .map(it -> ((TypeName)it).getName())
                .collect(Collectors.toList());
    }

    public List<String> getFieldTypeNames() {
        return fieldTypeNames;
    }

    /**
     * Creates instances of this class for each of the {@link UnionTypeDefinition} provided.
     * @return List of ObjectDefinitions.
     */
    public static List<UnionDefinition> processUnionDefinitions(List<UnionTypeDefinition> objects) {
        return objects.stream().map(UnionDefinition::new).collect(Collectors.toList());
    }
}
