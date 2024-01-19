package no.fellesstudentsystem.graphitron.definitions.interfaces;

import com.squareup.javapoet.TypeName;

/**
 * Specifies that this Java object represents a GraphQL object.
 */
public interface ObjectSpecification {
    /**
     * @return The name of the object as specified in the schema.
     */
    String getName();

    /**
     * @return The javapoet {@link TypeName} for the imported generated GraphQL object.
     */
    TypeName getGraphClassName();
}
