package no.sikt.graphitron.definitions.interfaces;

import com.palantir.javapoet.ClassName;

import java.util.List;

/**
 * Specifies that this Java object represents a GraphQL object.
 */
public interface ObjectSpecification<T extends FieldSpecification> {
    /**
     * @return The name of the type as specified in the schema.
     */
    String getName();

    /**
     * @return The pre-generated class for this GraphQL-type.
     */
    Class<?> getClassReference();

    /**
     * @return The javapoet {@link ClassName} for the imported generated GraphQL type.
     */
    ClassName getGraphClassName();

    /**
     * @return The fields contained within this type.
     */
    List<T> getFields();

    /**
     * @return The field with this name. Null if it does not exist.
     */
    T getFieldByName(String name);

    /**
     * @return Is this type the top node? That should be either the Query or the Mutation type.
     */
    boolean isOperationRoot();
}
