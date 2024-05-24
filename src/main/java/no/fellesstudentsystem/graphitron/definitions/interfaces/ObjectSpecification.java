package no.fellesstudentsystem.graphitron.definitions.interfaces;

import com.squareup.javapoet.ClassName;

import java.util.List;
import java.util.Set;

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
     * @return The fields which refer to any of these named objects.
     */
    List<T> getFieldsReferringTo(Set<String> names);

    /**
     * @return Is this type the top node? That should be either the Query or the Mutation type.
     */
    boolean isRoot();
}
