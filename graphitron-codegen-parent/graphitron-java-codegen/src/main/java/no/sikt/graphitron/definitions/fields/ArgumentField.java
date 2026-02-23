package no.sikt.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;

/**
 * An argument for a {@link ObjectField}.
 */
public class ArgumentField extends InputField {
    private final String targetFieldName;

    public ArgumentField(InputValueDefinition field, String container, String targetFieldName) {
        super(field, container);
        this.targetFieldName = targetFieldName;
    }

    @Override
    public String formatPath() {
        return String.format("'%s' on '%s.%s'", getName(), getContainerTypeName(), targetFieldName);
    }
}
