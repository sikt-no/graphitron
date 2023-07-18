package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.EnumDefinition;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.MAP_ENUM;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;
import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.getDirectiveArgumentString;

/**
 * An enum value within a {@link EnumDefinition}.
 */
public class EnumField extends AbstractField {
    private final String upperCaseName;

    public EnumField(EnumValueDefinition field) {
        super(field);
        upperCaseName = field.hasDirective(MAP_ENUM.getName())
                ? getDirectiveArgumentString(field, MAP_ENUM, MAP_ENUM.getParamName(NAME)).toUpperCase()
                : super.getName().toUpperCase();
    }

    /**
     * @return The database equivalent name of this field. Defaults to field name.
     */
    @Override
    public String getUpperCaseName() {
        return upperCaseName;
    }

    /**
     * @return List of instances based on an instance of {@link EnumTypeDefinition}.
     */
    public static List<EnumField> from(EnumTypeDefinition e) {
        return e.getEnumValueDefinitions().stream().map(EnumField::new).collect(Collectors.toList());
    }
}
