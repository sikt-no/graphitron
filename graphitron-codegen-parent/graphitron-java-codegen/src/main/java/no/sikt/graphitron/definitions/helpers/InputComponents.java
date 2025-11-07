package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.interfaces.GenerationField;

import java.util.List;
import java.util.Map;

public record InputComponents(
        List<InputCondition> independentConditions, List<ConditionTuple> conditionTuples,
        List<InputSetValue> independentSetValues, SetValueTuple setValueTuple,
        Map<GenerationField, List<InputCondition>> declaredConditionsByField
) {
    public record ConditionTuple(String path, List<InputCondition> conditions) {}
    public record SetValueTuple(String path, List<InputSetValue> setValues) {}
}
