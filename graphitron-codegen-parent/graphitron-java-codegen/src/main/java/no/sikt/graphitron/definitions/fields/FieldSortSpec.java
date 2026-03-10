package no.sikt.graphitron.definitions.fields;

import graphql.language.ObjectValue;
import no.sikt.graphql.directives.DirectiveHelpers;

import static no.sikt.graphql.directives.DirectiveHelpers.*;
import static no.sikt.graphql.directives.GenerationDirectiveParam.COLLATE;
import static no.sikt.graphql.directives.GenerationDirectiveParam.NAME;

/**
 * Represents a field sort specification parsed from the @order or @defaultOrder directives.
 * @param name Database field name.
 * @param collation Optional collation string.
 */
public record FieldSortSpec(String name, String collation) {

    public static FieldSortSpec from(ObjectValue obj) {
        var fields = obj.getObjectFields();
        var name = stringValueOf(getObjectFieldByName(fields, NAME));
        var collation = getOptionalObjectFieldByName(fields, COLLATE)
                .map(DirectiveHelpers::stringValueOf).orElse(null);
        return new FieldSortSpec(name, collation);
    }
}
