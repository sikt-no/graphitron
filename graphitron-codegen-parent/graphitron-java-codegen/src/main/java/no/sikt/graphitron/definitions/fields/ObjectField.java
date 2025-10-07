package no.sikt.graphitron.definitions.fields;

import graphql.language.FieldDefinition;
import graphql.language.IntValue;
import no.sikt.graphitron.definitions.fields.containedtypes.FieldType;
import no.sikt.graphitron.definitions.fields.containedtypes.MutationType;
import no.sikt.graphitron.validation.ValidationHandler;
import no.sikt.graphql.directives.GenerationDirectiveParam;
import no.sikt.graphql.naming.GraphQLReservedName;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphql.directives.DirectiveHelpers.getDirectiveArgumentEnum;
import static no.sikt.graphql.directives.GenerationDirective.*;

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
    private final boolean hasLookupKey;
    public final static List<String> RESERVED_PAGINATION_NAMES = List.of(
            GraphQLReservedName.PAGINATION_FIRST.getName(),
            GraphQLReservedName.PAGINATION_AFTER.getName(),
            GraphQLReservedName.PAGINATION_LAST.getName(),
            GraphQLReservedName.PAGINATION_BEFORE.getName()
    );

    public ObjectField(FieldDefinition field, String container) {
        super(field, new FieldType(field.getType()), container);
        arguments = setInputAndPagination(field, isRootField(), container);
        orderField = arguments.stream().filter(InputField::isOrderField).findFirst().orElse(null);
        nonReservedArguments = arguments.stream().filter(inputField ->
                RESERVED_PAGINATION_NAMES.stream().noneMatch(n -> n.equals(inputField.getName())) && !inputField.equals(orderField)
        ).collect(Collectors.toList());

        mutationType = field.hasDirective(MUTATION.getName())
                ? MutationType.valueOf(getDirectiveArgumentEnum(field, MUTATION, GenerationDirectiveParam.TYPE))
                : null;
        lookupKeys = nonReservedArguments.stream().filter(ArgumentField::isLookupKey).map(AbstractField::getName).collect(Collectors.toCollection(LinkedHashSet::new));
        hasLookupKey = !lookupKeys.isEmpty();
        argumentsByName = arguments.stream().collect(Collectors.toMap(AbstractField::getName, Function.identity(), (x, y) -> y, LinkedHashMap::new));
        ValidationHandler.isTrue(!hasLookupKey || getOrderField().isEmpty(),
                "'%s' has both @%s and @%s defined. These directives can not be used together", getName(), ORDER_BY.getName(), LOOKUP_KEY.getName());
        ValidationHandler.isTrue(!hasLookupKey || !hasPagination(),
                "'%s' has both pagination and @%s defined. These can not be used together", getName(), LOOKUP_KEY.getName());
    }

    private List<ArgumentField> setInputAndPagination(FieldDefinition field, boolean isTopLevel, String container) {
        var inputs = field.getInputValueDefinitions();
        var inputFields = new ArrayList<ArgumentField>();

        if (!isResolver() && !isTopLevel) {
            return inputs.stream().map(it -> new ArgumentField(it, container)).collect(Collectors.toList());
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
            inputFields.add(new ArgumentField(in, container));
        }

        var hasConnection = getTypeName().endsWith(GraphQLReservedName.SCHEMA_CONNECTION_SUFFIX.getName());
        hasRequiredPaginationFields = hasFirst && hasAfter || hasLast && hasBefore;
        hasForwardPagination = hasConnection && hasFirst && hasAfter;
        hasBackwardPagination = hasConnection && hasLast && hasBefore;
        return inputFields;
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
     * @return Does this search field use any form of pagination?
     */
    public boolean hasPagination() {
        return hasForwardPagination() || hasBackwardPagination();
    }

    @Override
    public boolean hasMutationType() {
        return mutationType != null;
    }

    @Override
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
    public static List<ObjectField> from(List<FieldDefinition> fields, String container) {
        return fields.stream().map(it -> new ObjectField(it, container)).collect(Collectors.toList());
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
