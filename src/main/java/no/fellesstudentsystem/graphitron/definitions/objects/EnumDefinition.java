package no.fellesstudentsystem.graphitron.definitions.objects;

import graphql.language.EnumTypeDefinition;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.fields.EnumField;
import no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.GenerationDirective.ENUM;
import static no.fellesstudentsystem.graphitron.definitions.fields.EnumField.from;

/**
 * Representation of a GraphQL enum type.
 */
public class EnumDefinition extends AbstractObjectDefinition<EnumTypeDefinition> {
    private final CodeReference enumReference;
    private final Map<String, EnumField> valuesMap;

    public EnumDefinition(EnumTypeDefinition enumTypeDefinition) {
        super(enumTypeDefinition);
        this.enumReference = enumTypeDefinition.hasDirective(ENUM.getName())
                ? new CodeReference(enumTypeDefinition, ENUM, GenerationDirectiveParam.ENUM)
                : null;
        this.valuesMap = from(enumTypeDefinition)
                .stream()
                .collect(Collectors.toMap(EnumField::getName, Function.identity()));
    }

    /**
     * @return Reference to the external Java enum that should correspond to the one in the schema.
     */
    public CodeReference getEnumReference() {
        return enumReference;
    }

    /**
     * @return Does this enum map to another enum in the API?
     */
    public boolean hasDbEnumMapping() {
        return enumReference != null;
    }

    /**
     * @return A map of the enum values for this enum object.
     */
    public Map<String, EnumField> getValuesMap() {
        return valuesMap;
    }

    /**
     * Creates instances of this class for each of the {@link EnumTypeDefinition} provided.
     * @return List of EnumDefinitions.
     */
    public static List<EnumDefinition> processEnumDefinitions(List<EnumTypeDefinition> enums) {
        return enums
                .stream()
                .map(EnumDefinition::new)
                .collect(Collectors.toList());
    }
}
