package no.fellesstudentsystem.graphitron.definitions.sql;

import no.fellesstudentsystem.kjerneapi.conditions.GeneratorCondition;

import java.util.List;

public class SQLCondition {
    private final GeneratorCondition condition;
    private final boolean override;

    public SQLCondition(String conditionName) {
        condition = GeneratorCondition.valueOf(conditionName);
        override = false;
    }

    public SQLCondition(String conditionName, boolean override) {
        condition = GeneratorCondition.valueOf(conditionName);
        this.override = override;
    }

    public String formatToString(List<String> methodInputs) {
        var method = condition.getConditionType();
        var methodName = method.getName();
        var declaringName = method.getDeclaringClass().getName();
        var paramTypes = method.getParameterTypes();
        var nParams = paramTypes.length;
        if (methodInputs.size() < nParams) {
            throw new IllegalArgumentException(
                    "Too few inputs for method '" + methodName + "' in class '" + declaringName + "'."
            );
        }
        return declaringName + "." + methodName + "(" + String.join(", ", methodInputs) + ")";
    }

    public boolean isOverride() {
        return override;
    }
}
