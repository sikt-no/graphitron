package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.InterfaceTypeDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;

import java.util.List;

/**
 * Represents a GraphQL interface.
 */
public class InterfaceDefinition extends AbstractObjectDefinition<InterfaceTypeDefinition, ObjectField> {
    public InterfaceDefinition(InterfaceTypeDefinition typeDefinition) {
        super(typeDefinition);
    }

    @Override
    protected List<ObjectField> createFields(InterfaceTypeDefinition objectDefinition) {
        return ObjectField.from(objectDefinition.getFieldDefinitions(), getName());
    }
}
