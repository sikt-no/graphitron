package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.definitions.interfaces.GenerationField;

import java.util.List;
import java.util.Map;

public class InputConditions {
    private final List<InputCondition> independentConditions;
    private final List<ConditionTuple> conditionTuples;
    private final Map<GenerationField, List<InputCondition>> declaredConditionsByField;

    public InputConditions(
            List<InputCondition> independentConditions,
            List<ConditionTuple> conditionTuples,
            Map<GenerationField, List<InputCondition>> declaredConditionsByField) {
        this.independentConditions = independentConditions;
        this.conditionTuples = conditionTuples;
        this.declaredConditionsByField = declaredConditionsByField;
    }

    public List<InputCondition> getIndependentConditions() {
        return independentConditions;
    }

    public List<ConditionTuple> getConditionTuples() {
        return conditionTuples;
    }

    public Map<GenerationField, List<InputCondition>> getDeclaredConditionsByField() {
        return declaredConditionsByField;
    }

    public static class ConditionTuple {
        private final String path;
        private final List<InputCondition> conditions;

        public ConditionTuple(String path, List<InputCondition> conditions) {
            this.path = path;
            this.conditions = conditions;
        }

        public String getPath() {
            return path;
        }

        public List<InputCondition> getConditions() {
            return conditions;
        }
    }
}
