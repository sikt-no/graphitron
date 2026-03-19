package no.sikt.graphql.federation.fieldsets;

import graphql.language.Field;
import no.sikt.graphql.federation.FieldSetKey;
import no.sikt.graphql.schema.SelectionSetParser;

import java.util.List;

/**
 * The top level of entity keys for a type. One entry here corresponds to one key directive in the schema.
 */
public record FederationFieldSet(List<FieldSetKey> keys) {
    public static FederationFieldSet fromString(List<String> rawKeys) {
        return new FederationFieldSet(rawKeys.stream().map(FederationFieldSet::parseKeys).toList());
    }

    private static FieldSetKey parseKeys(String rawKey) {
        return new CompoundFieldSetKey(
                SelectionSetParser.parseFields(rawKey).stream().map(FederationFieldSet::toFieldSetKey).toList()
        );
    }

    private static FieldSetKey toFieldSetKey(Field field) {
        if (field.getSelectionSet() == null || field.getSelectionSet().getSelections().isEmpty()) {
            return new SimpleFieldSetKey(field.getName());
        }

        var nestedFields = field
                .getSelectionSet()
                .getSelections()
                .stream()
                .map(Field.class::cast)
                .map(FederationFieldSet::toFieldSetKey)
                .toList();
        return new NestedFieldSetKey(new CompoundFieldSetKey(nestedFields), field.getName());
    }
}
