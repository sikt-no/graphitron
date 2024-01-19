package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;

/**
 * An argument for a {@link ObjectField}.
 */
public class ArgumentField extends InputField {
    public ArgumentField(InputValueDefinition field) {
        super(field);
    }
}
