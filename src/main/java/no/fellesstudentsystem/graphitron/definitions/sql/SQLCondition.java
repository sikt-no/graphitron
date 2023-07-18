package no.fellesstudentsystem.graphitron.definitions.sql;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public class SQLCondition {
    private final String conditionName;
    private final boolean override;

    public SQLCondition(String conditionName) {
        this(conditionName, false);
    }

    public SQLCondition(String conditionName, boolean override) {
        this.conditionName = conditionName;
        this.override = override;
    }

    public CodeBlock formatToString(List<CodeBlock> methodInputs) {
        if (!GeneratorConfig.getExternalConditions().contains(conditionName)) {
            throw new IllegalArgumentException("Condition with name '" + conditionName + "' does not exist.");
        }

        var method = GeneratorConfig.getExternalConditions().get(conditionName);
        var methodName = method.getName();
        var declaringName = method.getDeclaringClass().getName();
        var paramTypes = method.getParameterTypes();
        var nParams = paramTypes.length;
        if (methodInputs.size() < nParams) {
            throw new IllegalArgumentException(
                    "Too few inputs for method '" + methodName + "' in class '" + declaringName + "'."
            );
        }

        return CodeBlock
                .builder()
                .add("$N.$L(", declaringName, methodName)
                .add(StringUtils.repeat("$L", ", ", methodInputs.size()) + ")", methodInputs.toArray())
                .build();
    }

    public boolean isOverride() {
        return override;
    }
}
