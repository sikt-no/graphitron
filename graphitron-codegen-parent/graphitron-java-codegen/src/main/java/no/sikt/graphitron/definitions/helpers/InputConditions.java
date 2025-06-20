package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.interfaces.GenerationField;

import java.util.List;
import java.util.Map;

public record InputConditions(
        List<InputCondition> independentConditions, List<ConditionTuple> conditionTuples,
        Map<GenerationField, List<InputCondition>> declaredConditionsByField
) {
    public record ConditionTuple(String path, List<InputCondition> conditions) {}
}
