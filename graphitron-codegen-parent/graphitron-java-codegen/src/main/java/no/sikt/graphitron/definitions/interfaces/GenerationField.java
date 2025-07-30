package no.sikt.graphitron.definitions.interfaces;

import no.sikt.graphitron.definitions.fields.containedtypes.FieldReference;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.definitions.helpers.ServiceWrapper;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.definitions.sql.SQLCondition;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphql.directives.GenerationDirective;

import java.util.List;
import java.util.Map;

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

    /**
     * Note: This method does not account for implicit references, as this is not resolvable within the field object.
     * @return Does this field fulfill the conditions for initiating a subquery?
     */
    boolean invokesSubquery();

    boolean hasCondition();

    boolean hasOverridingCondition();

    List<FieldReference> getFieldReferences();

    SQLCondition getCondition();

    /**
     * @return Does this field have a service reference defined?
     */
    boolean hasServiceReference();

    /**
     * @return The wrapper object for a service reference, if the field has a service set. Otherwise, null.
     */
    ServiceWrapper getService();

    /**
     * @return Get any context fields related to this field. This may be either service or condition context fields (without duplicates).
     */
    Map<String, TypeName> getContextFields();

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

    boolean hasFieldDirective();
}
