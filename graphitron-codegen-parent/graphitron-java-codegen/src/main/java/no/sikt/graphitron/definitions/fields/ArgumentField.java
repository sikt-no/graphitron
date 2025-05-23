package no.sikt.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;

/**
 * An argument for a {@link ObjectField}.
 */
public class ArgumentField extends InputField {
    public ArgumentField(InputValueDefinition field, String container) {
        super(field, container);
    }
}
