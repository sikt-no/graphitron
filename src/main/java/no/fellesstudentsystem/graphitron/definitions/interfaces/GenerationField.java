package no.fellesstudentsystem.graphitron.definitions.interfaces;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.fields.containedtypes.FieldReference;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLCondition;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;

import java.util.List;

/**
 * This interface represents the general functionality associated with GraphQLs fields that can initialise code generation.
 */
public interface GenerationField extends GenerationTarget, FieldSpecification {
    boolean hasFieldReferences();

    boolean isInput();

    /**
     * @return Does this field point to a resolver method?
     */
    boolean isResolver();

    boolean hasCondition();

    boolean hasOverridingCondition();

    List<FieldReference> getFieldReferences();

    SQLCondition getCondition();

    /**
     * @return Does this field have a service reference defined?
     */
    boolean hasServiceReference();

    /**
     * @return The reference to the external service that this field is related to.
     */
    CodeReference getServiceReference();

    /**
     * @return jOOQ record-side name mapping based on the name of the field or the {@link GenerationDirective#FIELD} directive set on this type.
     */
    String getFieldJOOQMappingName();

    /**
     * @return jOOQ record-side method mapping based on the name of the field or the directive {@link GenerationDirective#FIELD} set on this type.
     */
    MethodMapping getMappingForJOOQFieldOverride();
}
