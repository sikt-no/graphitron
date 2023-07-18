package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.FieldDefinition;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.NOT_GENERATED;

/**
 * Represents an object field type placed on a top level object, for example the Query type.
 */
public class TopLevelObjectField extends ObjectField {
    private final boolean isGenerated;

    public TopLevelObjectField(FieldDefinition field) {
        super(field);
        isGenerated = !field.hasDirective(NOT_GENERATED.getName());
        super.setInputAndPagination(field, true);
    }

    /**
     * @return Should this field result in a generated method in a resolver?
     */
    public boolean isGenerated() {
        return isGenerated;
    }

    /**
     * @return List of instances based on a list of {@link TopLevelObjectField}.
     */
    public static List<ObjectField> from(List<FieldDefinition> fields) {
        return fields.stream().map(TopLevelObjectField::new).collect(Collectors.toList());
    }
}
