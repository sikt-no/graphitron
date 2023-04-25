package no.fellesstudentsystem.graphitron.definitions.sql;

import no.fellesstudentsystem.kjerneapi.conditions.GeneratorCondition;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public class SQLCondition {
    private final GeneratorCondition condition;
    private final String conditionName;
    private final boolean override;

    public SQLCondition(String conditionName) {
        this(conditionName, false);
    }

    public SQLCondition(String conditionName, boolean override) {
        this.conditionName = conditionName;
        GeneratorCondition condition = null;
        try {
            condition = GeneratorCondition.valueOf(conditionName);
        } catch (IllegalArgumentException ignored) {}
        this.condition = condition;
        this.override = override;
    }

    public String formatToString(List<String> methodInputs) {
        return formatToString(methodInputs, Map.of());
    }

    public String formatToString(List<String> methodInputs, Map<String, Method> methodOverrides) {
        var overrideExists = methodOverrides.containsKey(conditionName);
        if (condition == null && !overrideExists) {
            throw new IllegalArgumentException("Condition with name '" + conditionName + "' does not exist.");
        }

        var method = overrideExists ? methodOverrides.get(conditionName) : condition.getConditionType();
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
