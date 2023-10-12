package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;
import no.fellesstudentsystem.graphitron.definitions.mapping.RecordMethodMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.FIELD;

/**
 * An argument for a {@link ObjectField}.
 */
public class InputField extends AbstractField {
    private final String defaultValue;
    private final RecordMethodMapping recordFromColumnMapping;
    private final MethodMapping recordFromSchemaNameMapping;
    private final boolean hasFieldNameOverride;

    public InputField(InputValueDefinition field) {
        super(field);
        defaultValue = field.getDefaultValue() != null ? field.getDefaultValue().toString() : "";

        hasFieldNameOverride = field.hasDirective(FIELD.getName());
        if (hasFieldNameOverride) {
            recordFromColumnMapping = new RecordMethodMapping(getUpperCaseName());
            recordFromSchemaNameMapping = null;
        } else {
            recordFromColumnMapping = null;
            recordFromSchemaNameMapping = new MethodMapping(getUnprocessedNameInput());
        }
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
     * @return Record-side set method name mapping based on the name of the field or the directive set on this input.
     */
    public String getRecordSetCall(String input) {
        return hasFieldNameOverride ? recordFromColumnMapping.asSetCall(input) : recordFromSchemaNameMapping.asSetCall(input);
    }

    /**
     * @return Record-side name mapping based on the name of the field or the directive set on this input.
     */
    public String getRecordMappingName() {
        return hasFieldNameOverride ? recordFromColumnMapping.getName() : recordFromSchemaNameMapping.getName();
    }
}
