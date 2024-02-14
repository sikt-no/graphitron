package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;
import no.fellesstudentsystem.graphitron.definitions.mapping.RecordMethodMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.FIELD;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.LOOKUP_KEY;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.ORDER_BY;

/**
 * A field for a {@link no.fellesstudentsystem.graphitron.definitions.objects.InputDefinition}.
 */
public class InputField extends AbstractField<InputValueDefinition> {
    private final String defaultValue;
    private final RecordMethodMapping recordFromColumnMapping;
    private final MethodMapping recordFromSchemaNameMapping;
    private final boolean hasFieldNameOverride, isLookupKey, isOrderField;

    public InputField(InputValueDefinition field) {
        super(field, new FieldType(field.getType()));
        defaultValue = field.getDefaultValue() != null ? field.getDefaultValue().toString() : "";

        hasFieldNameOverride = field.hasDirective(FIELD.getName());
        if (hasFieldNameOverride) {
            recordFromColumnMapping = new RecordMethodMapping(getUpperCaseName());
            recordFromSchemaNameMapping = null;
        } else {
            recordFromColumnMapping = null;
            recordFromSchemaNameMapping = new MethodMapping(getUnprocessedNameInput());
        }
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
}
