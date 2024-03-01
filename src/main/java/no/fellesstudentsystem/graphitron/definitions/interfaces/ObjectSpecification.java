package no.fellesstudentsystem.graphitron.definitions.interfaces;

import com.squareup.javapoet.ClassName;

/**
 * Specifies that this Java object represents a GraphQL object.
 */
public interface ObjectSpecification {
    /**
     * @return The name of the object as specified in the schema.
     */
    String getName();

    /**
     * @return The pre-generated class for this GraphQL-type.
     */
    Class<?> getClassReference();

    /**
     * @return The javapoet {@link ClassName} for the imported generated GraphQL object.
     */
    ClassName getGraphClassName();
}
