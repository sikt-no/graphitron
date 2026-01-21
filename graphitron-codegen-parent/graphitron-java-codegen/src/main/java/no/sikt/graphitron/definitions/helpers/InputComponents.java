package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.interfaces.GenerationField;

import java.util.List;
import java.util.Map;

/**
 * Holds categorized input components parsed from field arguments.
 * Supports both conditions (for WHERE clauses) and set values (for INSERT/UPDATE).
 */
public record InputComponents(
        List<InputComponent> independentComponents,
        List<InputTuple> tuples,
        Map<GenerationField, List<InputComponent>> declaredConditionsByField
) {
    public List<InputComponent> independentFilterConditions() {
        return independentComponents.stream()
                .filter(InputComponent::isFilterInput)
                .toList();
    }

    public List<InputComponent> independentSetValues() {
        return independentComponents.stream()
                .filter(InputComponent::isSetValueInput)
                .toList();
    }

    /**
     * Tuple for grouping list-wrapped input components by their path.
     */
    public record InputTuple(String path, List<InputComponent> components) {}
}
