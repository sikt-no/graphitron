package no.sikt.graphitron.definitions.fields;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import no.sikt.graphitron.validation.ValidationHandler;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphql.directives.DirectiveHelpers.*;
import static no.sikt.graphql.directives.GenerationDirective.ORDER;
import static no.sikt.graphql.directives.GenerationDirectiveParam.*;

/**
 * An order by field enum value within an {@link no.sikt.graphitron.definitions.objects.EnumDefinition}.
 */
public class OrderByEnumField extends AbstractField<EnumValueDefinition> {

    public enum SortMode { INDEX, FIELDS, PRIMARY_KEY }

    private final String indexName;
    private final List<FieldSortSpec> fieldSortSpecs;
    private final SortMode sortMode;

    public OrderByEnumField(EnumValueDefinition field, String container) {
        super(field, container);

        var indexArg = getOptionalDirectiveArgumentString(field, ORDER, INDEX);
        var fieldsArg = getOptionalDirectiveArgumentObjectValueList(field, ORDER, FIELDS);
        var hasPrimaryKey = getOptionalDirectiveArgumentBoolean(field, ORDER, PRIMARY_KEY).orElse(false);

        var hasIndex = indexArg.isPresent();
        var hasFields = fieldsArg.isPresent() && !fieldsArg.get().isEmpty();
        var modeCount = (hasIndex ? 1 : 0) + (hasFields ? 1 : 0) + (hasPrimaryKey ? 1 : 0);

        if (modeCount == 0) {
            ValidationHandler.addErrorMessage(
                    "Expected enum field '%s' of '%s' to have an '@%s' directive with one of index, fields, or primaryKey set",
                    field.getName(), container, ORDER.getName());
        }
        ValidationHandler.isTrue(modeCount <= 1,
                "Enum field '%s' of '%s' must have exactly one of @%s(index:), @%s(fields:), or @%s(primaryKey: true) set, but %d were set",
                field.getName(), container, ORDER.getName(), ORDER.getName(), ORDER.getName(), modeCount);

        if (hasIndex) {
            indexName = indexArg.get();
            fieldSortSpecs = null;
            sortMode = SortMode.INDEX;
        } else if (hasFields) {
            indexName = null;
            fieldSortSpecs = fieldsArg.get().stream().map(FieldSortSpec::from).toList();
            sortMode = SortMode.FIELDS;
        } else if (hasPrimaryKey) {
            indexName = null;
            fieldSortSpecs = null;
            sortMode = SortMode.PRIMARY_KEY;
        } else {
            indexName = null;
            fieldSortSpecs = null;
            sortMode = null;
        }
    }

    /**
     * @return List of instances based on an instance of {@link EnumTypeDefinition}.
     */
    public static List<OrderByEnumField> from(EnumTypeDefinition e, String container) {
        return e.getEnumValueDefinitions()
                .stream()
                .map(field -> new OrderByEnumField(field, container))
                .collect(Collectors.toList());
    }

    public Optional<String> getIndexName() {
        return Optional.ofNullable(indexName);
    }

    public List<FieldSortSpec> getFieldSortSpecs() {
        return fieldSortSpecs != null ? fieldSortSpecs : List.of();
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    @Override
    public boolean hasNodeID() {
        return false;
    }
}
