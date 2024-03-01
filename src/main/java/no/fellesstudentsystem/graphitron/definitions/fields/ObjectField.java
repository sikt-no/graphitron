package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.FieldDefinition;
import graphql.language.IntValue;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;
import no.fellesstudentsystem.graphql.naming.GraphQLReservedName;
import org.apache.commons.lang3.Validate;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.getDirectiveArgumentEnum;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.*;

/**
 * Represents the default field type, which in addition to the generic field functionality also provides join operation data.
 */
public class ObjectField extends GenerationSourceField<FieldDefinition> {
    private int firstDefault = 100;
    private int lastDefault = 100;
    private boolean hasForwardPagination, hasBackwardPagination, hasRequiredPaginationFields;
    private final List<ArgumentField> arguments, nonReservedArguments;
    private final ArgumentField orderField;
    private final LinkedHashMap<String, ArgumentField> argumentsByName;
    private final LinkedHashSet<String> lookupKeys;
    private final MutationType mutationType;
    private final boolean isGenerated, hasLookupKey, fetchByID;
    public final static List<String> RESERVED_PAGINATION_NAMES = List.of(
            GraphQLReservedName.PAGINATION_FIRST.getName(),
            GraphQLReservedName.PAGINATION_AFTER.getName(),
            GraphQLReservedName.PAGINATION_LAST.getName(),
            GraphQLReservedName.PAGINATION_BEFORE.getName()
    );

    private final CodeReference serviceReference;

    public ObjectField(FieldDefinition field) {
        super(field, new FieldType(field.getType()));
        arguments = setInputAndPagination(field, isRootField());
        orderField = arguments.stream().filter(InputField::isOrderField).findFirst().orElse(null);
        nonReservedArguments = arguments.stream().filter(inputField ->
                RESERVED_PAGINATION_NAMES.stream().noneMatch(n -> n.equals(inputField.getName())) && !inputField.equals(orderField)
        ).collect(Collectors.toList());
        isGenerated = isResolver() && !isExplicitlyNotGenerated();
        fetchByID = field.hasDirective(FETCH_BY_ID.getName());

        serviceReference = field.hasDirective(SERVICE.getName()) ? new CodeReference(field, SERVICE, GenerationDirectiveParam.SERVICE, field.getName()) : null;
        mutationType = field.hasDirective(MUTATION.getName())
                ? MutationType.valueOf(getDirectiveArgumentEnum(field, MUTATION, GenerationDirectiveParam.TYPE))
                : null;
        lookupKeys = nonReservedArguments.stream().filter(ArgumentField::isLookupKey).map(AbstractField::getName).collect(Collectors.toCollection(LinkedHashSet::new));
        hasLookupKey = !lookupKeys.isEmpty();
        argumentsByName = arguments.stream().collect(Collectors.toMap(AbstractField::getName, Function.identity(), (x, y) -> y, LinkedHashMap::new));
        Validate.isTrue(!hasLookupKey || getOrderField().isEmpty(),
                "'%s' has both @%s and @%s defined. These directives can not be used together", getName(), ORDER_BY.getName(), LOOKUP_KEY.getName());
    }

    private List<ArgumentField> setInputAndPagination(FieldDefinition field, boolean isTopLevel) {
        var inputs = field.getInputValueDefinitions();
        var inputFields = new ArrayList<ArgumentField>();

        if (!isResolver() && !isTopLevel) {
            return inputs.stream().map(ArgumentField::new).collect(Collectors.toList());
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
            inputFields.add(new ArgumentField(in));
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
     * @return Should this field use IDs to fetch record data?
     */
    public boolean isFetchByID() {
        return fetchByID;
    }

    /**
     * @return Does this field point to a resolver which has to be defined manually, rather than generated?
     */
    public boolean isManualResolver() {
        return isResolver() && !isGenerated;
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
    public List<ArgumentField> getArguments() {
        return arguments;
    }

    /**
     * @return Argument with this name if it exists.
     */
    public ArgumentField getArgumentByName(String name) {
        return argumentsByName.get(name);
    }

    /**
     * @return Does this field contain this argument?
     */
    public boolean hasArgument(String name) {
        return argumentsByName.containsKey(name);
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
    public List<ArgumentField> getNonReservedArguments() {
        return nonReservedArguments;
    }

    /**
     * @return List of all non-reserved and orderBy arguments for this field
     */
    public List<ArgumentField> getNonReservedArgumentsWithOrderField() {
        List<ArgumentField> result = new ArrayList<>(nonReservedArguments);

        if (orderField != null) {
            result.add(orderField);
        }
        return result;
    }

    /**
     * @return Does this field have any input fields defined?
     */
    public boolean hasInputFields() {
        return !arguments.isEmpty();
    }

    /**
     * @return Does this field have any input fields defined that are not reserved?
     */
    public boolean hasNonReservedInputFields() {
        return !nonReservedArguments.isEmpty();
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

    /**
     * @return Does this field contain an argument set as a key for a lookup operation?
     */
    public boolean hasLookupKey() {
        return hasLookupKey;
    }

    /**
     * @return Set of fields that are configured to be used as lookup keys.
     */
    public LinkedHashSet<String> getLookupKeys() {
        return lookupKeys;
    }

    public Optional<ArgumentField> getOrderField() {
        return Optional.ofNullable(orderField);
    }

    @Override
    public boolean isInput() {
        return false;
    }
}
