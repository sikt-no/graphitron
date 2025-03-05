package no.sikt.graphitron.definitions.interfaces;

import no.sikt.graphitron.configuration.externalreferences.CodeReference;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.sql.SQLCondition;
import no.sikt.graphql.directives.GenerationDirective;

import java.util.List;

/**
 * This interface represents the general functionality associated with GraphQLs fields that can initialise code generation.
 */
public interface GenerationField extends GenerationTarget, FieldSpecification {
    boolean hasFieldReferences();

    boolean isExternalField();

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
     * @return Does this field have a mutation operation defined?
     */
    boolean hasMutationType();

    /**
     * @return The type of mutation that should be applied in this operation.
     */
    MutationType getMutationType();

    /**
     * @return Record-side name mapping based on the name of the field or the {@link GenerationDirective#FIELD} directive set on this type.
     */
    String getFieldRecordMappingName();

    /**
     * @return Record-side method mapping based on the name of the field or the directive {@link GenerationDirective#FIELD} set on this type.
     */
    MethodMapping getMappingForRecordFieldOverride();
}
