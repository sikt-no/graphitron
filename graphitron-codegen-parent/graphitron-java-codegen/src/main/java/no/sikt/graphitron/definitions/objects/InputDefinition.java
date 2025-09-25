package no.sikt.graphitron.definitions.objects;

import graphql.language.InputObjectTypeDefinition;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.InputField;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a default GraphQL input type.
 */
public class InputDefinition extends RecordObjectDefinition<InputObjectTypeDefinition, InputField> {
    private final LinkedHashSet<String> lookupInputs;
    private final boolean containsLookupKey;

    public InputDefinition(InputObjectTypeDefinition inputType) {
        super(inputType);
        lookupInputs = getFields().stream().filter(InputField::isLookupKey).map(InputField::getName).collect(Collectors.toCollection(LinkedHashSet::new));
        containsLookupKey = !lookupInputs.isEmpty();
    }

    @Override
    protected List<InputField> createFields(InputObjectTypeDefinition inputType) {
        return InputField.from(inputType.getInputValueDefinitions(), getName());
    }

    /**
     * @return Inputs set to be used as keys for lookup operations.
     */
    public LinkedHashSet<String> getLookupKeys() {
        return lookupInputs;
    }

    /**
     * @return Does this input type contain a field that is a key for a lookup operation?
     */
    public boolean containsLookupKey() {
        return containsLookupKey;
    }

    /**
     * Creates instances of this class for each of the {@link InputObjectTypeDefinition} provided.
     * @return List of InputDefinitions.
     */
    public static List<InputDefinition> processInputDefinitions(List<InputObjectTypeDefinition> inputs) {
        return inputs
                .stream()
                .map(InputDefinition::new)
                .collect(Collectors.toList());
    }
}
