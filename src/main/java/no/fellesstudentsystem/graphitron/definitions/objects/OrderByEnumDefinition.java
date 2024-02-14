package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import no.fellesstudentsystem.graphitron.definitions.fields.OrderByEnumField;

import java.util.List;

/**
 * Representation of an OrderBy enum type.
 */
public class OrderByEnumDefinition extends AbstractObjectDefinition<EnumTypeDefinition, EnumValueDefinition, OrderByEnumField> {

    public OrderByEnumDefinition(EnumTypeDefinition enumTypeDefinition) {
        super(enumTypeDefinition);
    }

    public static OrderByEnumDefinition from(EnumDefinition it) {
        return new OrderByEnumDefinition(it.getObjectDefinition());
    }

    @Override
    protected List<OrderByEnumField> createFields(EnumTypeDefinition objectDefinition) {
        return OrderByEnumField.from(objectDefinition);
    }
}
