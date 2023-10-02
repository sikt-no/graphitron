package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.EnumDefinition;

import java.util.List;
import java.util.stream.Collectors;

/**
 * An enum value within a {@link EnumDefinition}.
 */
public class EnumField extends AbstractField {
    public EnumField(EnumValueDefinition field) {
        super(field);
    }

    /**
     * @return List of instances based on an instance of {@link EnumTypeDefinition}.
     */
    public static List<EnumField> from(EnumTypeDefinition e) {
        return e.getEnumValueDefinitions().stream().map(EnumField::new).collect(Collectors.toList());
    }
}
