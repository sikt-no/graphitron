package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.InputValueDefinition;
import no.fellesstudentsystem.graphitron.definitions.mapping.RecordMapping;
import no.fellesstudentsystem.graphitron.definitions.sql.SQLImplicitFKJoin;

import static no.fellesstudentsystem.graphql.mapping.GenerationDirective.COLUMN;

/**
 * An argument for a {@link ObjectField}.
 */
public class InputField extends AbstractField {
    private final String defaultValue;
    private final SQLImplicitFKJoin join;
    private final RecordMapping recordMapping;

    public InputField(InputValueDefinition field) {
        super(field);
        defaultValue = field.getDefaultValue() != null ? field.getDefaultValue().toString() : "";

        if (field.hasDirective(COLUMN.getName())) {
            join = getSqlRoleJoin(field);
        } else {
            join = null;
        }
        recordMapping = new RecordMapping(getUpperCaseName());
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
     * @return Record-side method name mappings based on the DB equivalent of this input.
     */
    public RecordMapping getRecordMapping() {
        return recordMapping;
    }
}
