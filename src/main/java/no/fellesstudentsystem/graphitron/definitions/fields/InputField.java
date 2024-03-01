package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.LOOKUP_KEY;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.ORDER_BY;

/**
 * A field for a {@link no.fellesstudentsystem.graphitron.definitions.objects.InputDefinition}.
 */
public class InputField extends GenerationSourceField<InputValueDefinition> {
    private final String defaultValue;
    private final boolean isLookupKey;
    private final boolean isOrderField;

    public InputField(InputValueDefinition field) {
        super(field, new FieldType(field.getType()));
        defaultValue = field.getDefaultValue() != null ? field.getDefaultValue().toString() : "";

        isLookupKey = field.hasDirective(LOOKUP_KEY.getName());
        isOrderField = field.hasDirective(ORDER_BY.getName());
    }

    /**
     * @return Does this input have a default value set?
     */
    public boolean hasDefaultValue() {
        return !defaultValue.isEmpty();
    }

    /**
     * @return Default value for this input.
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * @return Is this input field to be used as a key for a lookup operation?
     */
    public boolean isLookupKey() {
        return isLookupKey;
    }

    /**
     * @return List of instances based on a list of {@link InputValueDefinition}.
     */
    public static List<InputField> from(List<InputValueDefinition> fields) {
        return fields.stream().map(InputField::new).collect(Collectors.toList());
    }

    public boolean isOrderField() {
        return isOrderField;
    }

    @Override
    public boolean isInput() {
        return true;
    }
}
