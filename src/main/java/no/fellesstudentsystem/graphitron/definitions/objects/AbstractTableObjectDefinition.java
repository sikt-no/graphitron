package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.TypeDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.ObjectSpecification;
import no.fellesstudentsystem.graphitron.definitions.mapping.JOOQMapping;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;

import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;

/**
 * A generalized implementation of {@link ObjectSpecification} for types that can be linked to tables.
 */
public abstract class AbstractTableObjectDefinition<T extends TypeDefinition<T>, U extends AbstractField> extends AbstractObjectDefinition<T, U> {
    private final JOOQMapping table;
    private final boolean hasTable;

    public AbstractTableObjectDefinition(T objectDefinition) {
        super(objectDefinition);
        hasTable = objectDefinition.hasDirective(GenerationDirective.TABLE.getName());
        table = hasTable
                ? JOOQMapping.fromTable(getOptionalDirectiveArgumentString(objectDefinition, GenerationDirective.TABLE, NAME).orElse(getName().toUpperCase()))
                : null;
    }

    /**
     * @return Table objects which holds table names.
     */
    public JOOQMapping getTable() {
        return table;
    }

    /**
     * @return Does this object have the "{@link GenerationDirective#TABLE table}" directive
     * which implies a connection to a database table?
     */
    public boolean hasTable() {
        return hasTable;
    }
}
