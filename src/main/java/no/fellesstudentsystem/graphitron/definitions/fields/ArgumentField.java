package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.LOOKUP_KEY;

/**
 * An argument for a {@link ObjectField}.
 */
public class ArgumentField extends InputField {
    private final boolean isLookupKey;

    public ArgumentField(InputValueDefinition field) {
        super(field);

        isLookupKey = field.hasDirective(LOOKUP_KEY.getName());
    }

    /**
     * @return Is this input field to be used as a key for a lookup operation?
     */
    public boolean isLookupKey() {
        return isLookupKey;
    }
}
