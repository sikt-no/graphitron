package no.sikt.graphitron.definitions.objects;

import graphql.language.EnumTypeDefinition;
import no.sikt.graphitron.configuration.externalreferences.CodeReference;
import no.sikt.graphitron.definitions.fields.EnumField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.directives.GenerationDirective.*;

/**
 * Representation of a GraphQL enum type.
 */
public class EnumDefinition extends AbstractObjectDefinition<EnumTypeDefinition, EnumField> implements GenerationTarget {
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

    /**
     * @return Does this enum have values with @order or @index directives (i.e., it defines sort fields)?
     */
    public boolean isOrderByEnum() {
        return getObjectDefinition().getEnumValueDefinitions().stream()
                .anyMatch(v -> v.hasDirective(ORDER.getName()) || v.hasDirective(INDEX.getName()));
    }

    /**
     * @return Is this a direction enum (exactly two values: ASC and DESC)?
     */
    public boolean isDirectionEnum() {
        var values = getObjectDefinition().getEnumValueDefinitions();
        if (values.size() != 2) {
            return false;
        }
        var names = values.stream().map(v -> v.getName().toUpperCase()).sorted().toList();
        return names.equals(List.of("ASC", "DESC"));
    }
}
