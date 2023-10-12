package no.fellesstudentsystem.graphitron.definitions.sql;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public class SQLCondition {
    private final CodeReference conditionReference;
    private final boolean override;

    public SQLCondition(CodeReference conditionReference) {
        this(conditionReference, false);
    }

    public SQLCondition(CodeReference conditionReference, boolean override) {
        this.conditionReference = conditionReference;
        this.override = override;
    }

    public CodeBlock formatToString(List<CodeBlock> methodInputs) {
        var method = GeneratorConfig.getExternalReferences().getMethodFrom(conditionReference);
        var methodName = method.getName();

        var declaringName = method.getDeclaringClass().getName();
        if (methodInputs.size() < method.getParameterTypes().length) {
            throw new IllegalArgumentException("Too few inputs for method '" + methodName + "' in class '" + declaringName + "'.");
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

    public CodeReference getConditionReference() {
        return conditionReference;
    }
}
