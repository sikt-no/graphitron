package no.fellesstudentsystem.graphitron.definitions.helpers;

import no.fellesstudentsystem.graphitron.definitions.fields.InputField;

import java.util.List;
import java.util.Map;

public class InputConditions {
    private final List<InputCondition> independentConditions;
    private final Map<InputField, List<InputCondition>> conditionTuples;

    public InputConditions(List<InputCondition> independentConditions, Map<InputField, List<InputCondition>> conditionTuples) {
        this.independentConditions = independentConditions;
        this.conditionTuples = conditionTuples;
    }

    public List<InputCondition> getIndependentConditions() {
        return independentConditions;
    }

    public Map<InputField, List<InputCondition>> getConditionTuples() {
        return conditionTuples;
    }
}
