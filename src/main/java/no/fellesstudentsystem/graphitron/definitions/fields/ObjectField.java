package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.*;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;

import java.util.*;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.*;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.*;

/**
 * Represents the default field type, which in addition to the generic field functionality also provides join operation data.
 */
public class ObjectField extends AbstractField implements GenerationTarget {
    private int firstDefault = 100;
    private int lastDefault = 100;
    private boolean hasForwardPagination, hasBackwardPagination, hasRequiredPaginationFields;
    private final List<InputField> inputFields;
    private final List<InputField> nonReservedFields;
    private final MutationType mutationType;
    private final boolean isGenerated;
    private final boolean isResolver;
    public final static Set<String> RESERVED_PAGINATION_NAMES = Set.of(
            GraphQLReservedName.PAGINATION_FIRST.getName(),
            GraphQLReservedName.PAGINATION_AFTER.getName(),
            GraphQLReservedName.PAGINATION_LAST.getName(),
            GraphQLReservedName.PAGINATION_BEFORE.getName()
    );

    private final CodeReference serviceReference;

    public ObjectField(FieldDefinition field) {
        super(field);
        isResolver = field.hasDirective(SPLIT_QUERY.getName());
        inputFields = setInputAndPagination(field, isRootField());
        nonReservedFields = inputFields.stream().filter(inputField ->
                RESERVED_PAGINATION_NAMES.stream().noneMatch(n -> n.equals(inputField.getName()))
        ).collect(Collectors.toList());
        isGenerated = isResolver && !field.hasDirective(NOT_GENERATED.getName());

        serviceReference = field.hasDirective(SERVICE.getName()) ? new CodeReference(field, SERVICE, GenerationDirectiveParam.SERVICE, field.getName()) : null;
        mutationType = field.hasDirective(MUTATION.getName())
                ? MutationType.valueOf(getDirectiveArgumentEnum(field, MUTATION, GenerationDirectiveParam.TYPE))
                : null;
    }

    private List<InputField> setInputAndPagination(FieldDefinition field, boolean isTopLevel) {
        var inputs = field.getInputValueDefinitions();
        var inputFields = new ArrayList<InputField>();

        if (!isResolver && !isTopLevel) {
            return inputs.stream().map(InputField::new).collect(Collectors.toList());
        }

        boolean hasFirst = false, hasAfter = false, hasLast = false, hasBefore = false;
        for (var in : inputs) {
            var name = in.getName();

            if (name.equals(GraphQLReservedName.PAGINATION_FIRST.getName())) {
                hasFirst = true;
                if (in.getDefaultValue() != null) {
                    firstDefault = ((IntValue) (in.getDefaultValue())).getValue().intValue();
                }
            } else if (name.equals(GraphQLReservedName.PAGINATION_AFTER.getName())) {
                hasAfter = true;
            } else if (name.equals(GraphQLReservedName.PAGINATION_LAST.getName())) {
                hasLast = true;
                if (in.getDefaultValue() != null) {
                    lastDefault = ((IntValue) (in.getDefaultValue())).getValue().intValue();
                }
            } else if (name.equals(GraphQLReservedName.PAGINATION_BEFORE.getName())) {
                hasBefore = true;
            }
            inputFields.add(new InputField(in));
        }

        var hasConnection = getTypeName().endsWith(GraphQLReservedName.SCHEMA_CONNECTION_SUFFIX.getName());
        hasRequiredPaginationFields = hasFirst && hasAfter || hasLast && hasBefore;
        hasForwardPagination = hasConnection && hasFirst && hasAfter;
        hasBackwardPagination = hasConnection && hasLast && hasBefore;
        return inputFields;
    }

    /**
     * @return Should this field result in a generated method in a resolver?
     */
    public boolean isGenerated() {
        return isGenerated;
    }

    /**
     * @return Does this field point to a resolver which has to be defined manually, rather than generated?
     */
    public boolean isManualResolver() {
        return isResolver && !isGenerated;
    }

    /**
     * @return Does this field point to a resolver method?
     */
    public boolean isResolver() {
        return isResolver;
    }

    /**
     * @return Does this search field use forward pagination?
     * Does its type have the reserved "Connection" suffix?
     */
    public boolean hasForwardPagination() {
        return hasForwardPagination;
    }

    /**
     * @return Does this search field use backward pagination?
     * Does its type have the reserved "Connection" suffix?
     */
    public boolean hasBackwardPagination() {
        return hasBackwardPagination;
    }

    /**
     * @return The reference to the external service that this field is related to.
     */
    public CodeReference getServiceReference() {
        return serviceReference;
    }

    /**
     * @return Does this field have a service reference defined?
     */
    public boolean hasServiceReference() {
        return serviceReference != null;
    }

    public boolean hasMutationType() {
        return mutationType != null;
    }

    public MutationType getMutationType() {
        return mutationType;
    }

    /**
     * @return List of all input arguments for this field.
     */
    public List<InputField> getInputFields() {
        return inputFields;
    }

    /**
     * @return Default value set for the after parameter.
     */
    public int getFirstDefault() {
        return firstDefault;
    }

    /**
     * @return Default value set for the before parameter.
     */
    public int getLastDefault() {
        return lastDefault;
    }

    /**
     * @return List of all input non-reserved arguments for this field.
     */
    public List<InputField> getNonReservedInputFields() {
        return nonReservedFields;
    }

    /**
     * @return Does this field have any input fields defined?
     */
    public boolean hasInputFields() {
        return !inputFields.isEmpty();
    }

    /**
     * @return Does this field have any input fields defined that are not reserved?
     */
    public boolean hasNonReservedInputFields() {
        return !nonReservedFields.isEmpty();
    }

    /**
     * @return Does this field have any set of pagination fields?
     */
    public boolean hasRequiredPaginationFields() {
        return hasRequiredPaginationFields;
    }

    /**
     * @return List of instances based on a list of {@link FieldDefinition}.
     */
    public static List<ObjectField> from(List<FieldDefinition> fields) {
        return fields.stream().map(ObjectField::new).collect(Collectors.toList());
    }
}
