package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.InterfaceTypeDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a GraphQL interface.
 */
public class InterfaceDefinition extends AbstractObjectDefinition<InterfaceTypeDefinition, ObjectField> {
    private final List<ObjectField> objectFields;
    private final Set<String> objectFieldNames;

    public InterfaceDefinition(InterfaceTypeDefinition typeDefinition) {
        super(typeDefinition);
        objectFields = ObjectField.from(typeDefinition.getFieldDefinitions());
        objectFieldNames = objectFields.stream().map(AbstractField::getName).collect(Collectors.toSet());
    }

    /**
     * @return The fields that this interface requires any type that implements it to contain.
     */
    public List<ObjectField> getFields() {
        return objectFields;
    }

    /**
     * @return Does this interface contain this field?
     */
    public boolean hasField(String name) {
        return objectFieldNames.contains(name);
    }
}
