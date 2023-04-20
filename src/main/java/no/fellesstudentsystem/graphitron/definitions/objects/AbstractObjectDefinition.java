package no.fellesstudentsystem.graphitron.definitions.objects;

import com.squareup.javapoet.ClassName;
import graphql.language.TypeDefinition;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.ObjectSpecification;

/**
 * A generalized implementation of {@link ObjectSpecification}.
 * Contains functionality that is common between the different kinds of GraphQL objects.
 */
public abstract class AbstractObjectDefinition<T extends TypeDefinition<T>> implements ObjectSpecification<T> {
    private final String name;
    private final ClassName graphClassName;
    private final T objectTypeDefinition;

    public AbstractObjectDefinition(T objectDefinition) {
        name = objectDefinition.getName();
        graphClassName = ClassName.get(GeneratorConfig.generatedModelsPackage(), name);
        objectTypeDefinition = objectDefinition;
    }

    public String getName() {
        return name;
    }

    public ClassName getGraphClassName() {
        return graphClassName;
    }

    /**
     * @return The original interpretation of this object as provided by GraphQL.
     */
    public T getTypeDefinition() {
        return objectTypeDefinition;
    }
}
