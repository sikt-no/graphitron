package no.sikt.graphitron.definitions.fields;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import no.sikt.graphitron.definitions.objects.EnumDefinition;

import java.util.List;
import java.util.stream.Collectors;

/**
 * An enum value within an {@link EnumDefinition}.
 */
public class EnumField extends AbstractField<EnumValueDefinition> {
    public EnumField(EnumValueDefinition field, String container) {
        super(field, container);
    }

    /**
     * @return List of instances based on an instance of {@link EnumTypeDefinition}.
     */
    public static List<EnumField> from(EnumTypeDefinition e, String container) {
        return e.getEnumValueDefinitions().stream().map(it -> new EnumField(it, container)).collect(Collectors.toList());
    }

    @Override
    public boolean hasNodeID() {
        return false;
    }

    @Override
    public String getNodeIdTypeName() {
        return null;
    }
}
