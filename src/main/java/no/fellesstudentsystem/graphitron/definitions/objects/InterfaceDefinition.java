package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.InterfaceTypeDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class InterfaceDefinition extends AbstractObjectDefinition<InterfaceTypeDefinition> {
    private final Map<String, ObjectField> objectFields;

    public InterfaceDefinition(InterfaceTypeDefinition typeDefinition) {
        super(typeDefinition);
        objectFields = ObjectField
                .from(typeDefinition.getFieldDefinitions())
                .stream()
                .collect(Collectors.toMap(AbstractField::getName, Function.identity()));
    }

    public List<ObjectField> getInterfaceFields() {
        return new ArrayList<>(objectFields.values());
    }

    public boolean hasField(String name) {
        return objectFields.containsKey(name);
    }
}
