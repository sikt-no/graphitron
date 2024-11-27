package no.sikt.graphitron.definitions.sql;

import com.squareup.javapoet.CodeBlock;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.CodeReference;
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
        var methods = GeneratorConfig.getExternalReferences().getMethodsFrom(conditionReference);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException(
                    "Condition reference " +
                            GeneratorConfig.getExternalReferences().getClassFrom(conditionReference).getName() +
                            " does not contain method named " + conditionReference.getMethodName()
            );
        }

        var methodName = conditionReference.getMethodName();
        var method = methods.stream().filter(it -> it.getParameterTypes().length == methodInputs.size()).findFirst();
        if (method.isEmpty()) {
            var declaringName = methods.stream().findAny().get().getDeclaringClass().getName();
            var possibleInputs = methods
                    .stream()
                    .map(it -> Arrays.stream(it.getParameterTypes()).map(Class::getCanonicalName).collect(Collectors.joining(", ")))
                    .collect(Collectors.joining(" or "));
            throw new IllegalArgumentException(
                    String.format(
                            "Wrong number of inputs for method '%s' in class '%s'.\nInputs were %s, but expected %s.",
                            methodName,
                            declaringName,
                            CodeBlock.join(methodInputs, ", ").toString(),
                            possibleInputs
                    )
            );
        }

        var declaringName = method.get().getDeclaringClass().getName();
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
