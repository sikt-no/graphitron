package no.fellesstudentsystem.graphitron.definitions.interfaces;

import com.squareup.javapoet.ClassName;
import graphql.language.TypeDefinition;

/**
 * Specifies that this Java object represents a GraphQL object.
 */
public interface ObjectSpecification<T extends TypeDefinition<T>> {
    /**
     * @return The name of the object as specified in the schema.
     */
    String getName();

    /**
     * @return The javapoet {@link ClassName} for the imported generated GraphQL object.
     */
    ClassName getGraphClassName();

    /**
     * @return The original interpretation of this object as provided by GraphQL.
     */
    T getTypeDefinition();
}
