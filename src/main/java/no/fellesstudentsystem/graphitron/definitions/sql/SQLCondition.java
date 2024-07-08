package no.fellesstudentsystem.graphitron.definitions.sql;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
            throw new IllegalArgumentException(
                    String.format(
                            "Too few inputs for method '%s' in class '%s'.\nInputs were %s, but expected %s.",
                            methodName,
                            declaringName,
                            CodeBlock.join(methodInputs, ", ").toString(),
                            Arrays.stream(method.getParameterTypes()).map(Class::getCanonicalName).collect(Collectors.joining(", "))
                    )
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

    public CodeReference getConditionReference() {
        return conditionReference;
    }
}
