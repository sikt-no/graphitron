package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;
import no.fellesstudentsystem.graphitron.definitions.mapping.RecordMethodMapping;
import no.fellesstudentsystem.graphitron.definitions.mapping.MethodMapping;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLImplicitFKJoin;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.COLUMN;

/**
 * An argument for a {@link ObjectField}.
 */
public class InputField extends AbstractField {
    private final String defaultValue;
    private final SQLImplicitFKJoin join;
    private final RecordMethodMapping recordFromColumnMapping;
    private final MethodMapping recordFromSchemaNameMapping;
    private final boolean hasColumn;

    public InputField(InputValueDefinition field) {
        super(field);
        defaultValue = field.getDefaultValue() != null ? field.getDefaultValue().toString() : "";

        hasColumn = field.hasDirective(COLUMN.getName());
        if (hasColumn) {
            join = getSqlRoleJoin(field);
            recordFromColumnMapping = new RecordMethodMapping(getUpperCaseName());
            recordFromSchemaNameMapping = null;
        } else {
            join = null;
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
     * @return The optional key to use as implicit join.
     */
    public SQLImplicitFKJoin getImplicitJoin() {
        return join;
    }

    /**
     * @return Does this field have a join key that should be used.
     */
    public boolean hasImplicitJoin() {
        return join != null;
    }

    /**
     * @return Record-side method name mappings based on the name of the field or the directive set on this input.
     */
    public String getRecordSetCall(String input) {
        return hasColumn ? recordFromColumnMapping.asSetCall(input) : recordFromSchemaNameMapping.asSetCall(input);
    }
}
