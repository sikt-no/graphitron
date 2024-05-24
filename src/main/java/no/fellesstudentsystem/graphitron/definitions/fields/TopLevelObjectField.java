package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.FieldDefinition;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents an object field type placed on a top level object, for example the Query type.
 */
public class TopLevelObjectField extends ObjectField {

    public TopLevelObjectField(FieldDefinition field, String container) {
        super(field, container);
    }

    @Override
    public boolean isRootField() {
        return true;
    }

    /**
     * @return List of instances based on a list of {@link TopLevelObjectField}.
     */
    public static List<ObjectField> from(List<FieldDefinition> fields, String container) {
        return fields.stream().map(it -> new TopLevelObjectField(it, container)).collect(Collectors.toList());
    }
}
