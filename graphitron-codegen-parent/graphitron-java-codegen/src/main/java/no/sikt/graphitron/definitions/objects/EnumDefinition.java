package no.sikt.graphitron.definitions.objects;

import graphql.language.EnumTypeDefinition;
import no.sikt.graphitron.configuration.externalreferences.CodeReference;
import no.sikt.graphitron.definitions.fields.EnumField;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.directives.GenerationDirective.ENUM;

/**
 * Representation of a GraphQL enum type.
 */
public class EnumDefinition extends AbstractObjectDefinition<EnumTypeDefinition, EnumField> {
    private final CodeReference enumReference;

    public EnumDefinition(EnumTypeDefinition enumTypeDefinition) {
        super(enumTypeDefinition);
        this.enumReference = enumTypeDefinition.hasDirective(ENUM.getName())
                ? new CodeReference(enumTypeDefinition, ENUM, GenerationDirectiveParam.ENUM)
                : null;
    }

    @Override
    protected List<EnumField> createFields(EnumTypeDefinition objectDefinition) {
        return EnumField.from(objectDefinition, getName());
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
    public boolean hasJavaEnumMapping() {
        return enumReference != null;
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
