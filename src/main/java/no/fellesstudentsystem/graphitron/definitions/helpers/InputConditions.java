package no.fellesstudentsystem.graphitron.definitions.helpers;

import java.util.List;

public class InputConditions {
    private final List<InputCondition> independentConditions;
    private final List<ConditionTuple> conditionTuples;

    public InputConditions(List<InputCondition> independentConditions, List<ConditionTuple> conditionTuples) {
        this.independentConditions = independentConditions;
        this.conditionTuples = conditionTuples;
    }

    public List<InputCondition> getIndependentConditions() {
        return independentConditions;
    }

    public List<ConditionTuple> getConditionTuples() {
        return conditionTuples;
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
