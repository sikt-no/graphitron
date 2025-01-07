package no.sikt.graphitron.definitions.objects;

import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.TypeResolverTarget;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.directives.GenerationDirective.NOT_GENERATED;

/**
 * Represents a GraphQL union type.
 */
public class UnionDefinition extends AbstractObjectDefinition<UnionTypeDefinition, ObjectField> implements TypeResolverTarget {
    private final List<String> fieldTypeNames;
    private final boolean isGenerated;

    public UnionDefinition(UnionTypeDefinition typeDefinition) {
        super(typeDefinition);
        fieldTypeNames = typeDefinition
                .getMemberTypes()
                .stream()
                .map(it -> ((TypeName)it).getName())
                .collect(Collectors.toList());
        isGenerated = !typeDefinition.hasDirective(NOT_GENERATED.getName());
    }

    /**
     * @return List of type names this union consists of.
     */
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

    @Override
    protected List<ObjectField> createFields(UnionTypeDefinition objectDefinition) {
        return List.of();
    }

    @Override
    public boolean isGenerated() {
        return isGenerated;
    }

    @Override
    public boolean isGeneratedWithResolver() {
        return isGenerated;
    }

    @Override
    public boolean isExplicitlyNotGenerated() {
        return !isGenerated;
    }
}
