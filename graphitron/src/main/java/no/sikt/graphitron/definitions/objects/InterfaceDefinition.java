package no.sikt.graphitron.definitions.objects;

import graphql.language.InterfaceTypeDefinition;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;

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

    public JOOQMapping getTable() {
        return null;
    }

    public boolean hasTable() {
        return false;
    }

    public boolean hasDiscrimatingField() {
        return false;
    }

    public String getDiscriminatingFieldName() {
        return null;
    }
}
