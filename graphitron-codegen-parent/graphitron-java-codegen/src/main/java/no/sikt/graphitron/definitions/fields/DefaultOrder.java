package no.sikt.graphitron.definitions.fields;

import graphql.language.FieldDefinition;
import no.sikt.graphitron.validation.ValidationHandler;

import java.util.List;

import static no.sikt.graphql.directives.DirectiveHelpers.*;
import static no.sikt.graphql.directives.GenerationDirective.DEFAULT_ORDER;
import static no.sikt.graphql.directives.GenerationDirectiveParam.*;

/**
 * Represents a parsed @defaultOrder directive with one of three modes: index, fields, or primaryKey.
 */
public record DefaultOrder(String index, List<FieldSortSpec> fields, boolean primaryKey, String direction) {

    public DefaultOrder {
        var modeCount = (index != null ? 1 : 0)
                + (fields != null && !fields.isEmpty() ? 1 : 0)
                + (primaryKey ? 1 : 0);
        ValidationHandler.isTrue(modeCount == 1,
                "@%s must have exactly one of index, fields, or primaryKey set, but %d were set.",
                DEFAULT_ORDER.getName(), modeCount);
    }

    public static DefaultOrder from(FieldDefinition field) {
        var indexArg = getOptionalDirectiveArgumentString(field, DEFAULT_ORDER, INDEX);
        var fieldsArg = getOptionalDirectiveArgumentObjectValueList(field, DEFAULT_ORDER, FIELDS);
        var hasPrimaryKey = getOptionalDirectiveArgumentBoolean(field, DEFAULT_ORDER, PRIMARY_KEY).orElse(false);

        var fieldSpecs = fieldsArg.isPresent() && !fieldsArg.get().isEmpty()
                ? fieldsArg.get().stream().map(FieldSortSpec::from).toList()
                : null;
        var direction = getOptionalDirectiveArgumentEnum(field, DEFAULT_ORDER, DIRECTION).orElse("ASC");
        return new DefaultOrder(indexArg.orElse(null), fieldSpecs, hasPrimaryKey, direction);
    }

    public boolean isIndex() {
        return index != null;
    }

    public boolean isFields() {
        return fields != null && !fields.isEmpty();
    }
}
