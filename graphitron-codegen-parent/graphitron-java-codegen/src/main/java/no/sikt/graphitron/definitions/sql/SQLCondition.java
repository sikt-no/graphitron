package no.sikt.graphitron.definitions.sql;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.sikt.graphitron.definitions.helpers.CodeReferenceWrapper;
import no.sikt.graphitron.generators.codebuilding.VariablePrefix;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.CodeReference;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.directives.GenerationDirectiveParam;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentBoolean;
import static no.sikt.graphql.directives.GenerationDirectiveParam.OVERRIDE;

public class SQLCondition extends CodeReferenceWrapper {
    private final boolean override;

    public SQLCondition(CodeReference conditionReference) {
        super(conditionReference);
        this.override = false;
    }

    public <T extends NamedNode<T> & DirectivesContainer<T>> SQLCondition(T field) {
        super(field, GenerationDirective.CONDITION, GenerationDirectiveParam.CONDITION);
        this.override = getOptionalDirectiveArgumentBoolean(field, GenerationDirective.CONDITION, OVERRIDE).orElse(false);
    }

    public CodeBlock formatToString(List<CodeBlock> methodInputs) {
        var reference = getReference();
        var methods = GeneratorConfig.getExternalReferences().getMethodsFrom(reference);
        var methodName = reference.getMethodName();
        if (methods.isEmpty()) {
            throw new IllegalArgumentException(
                    "Condition reference " +
                            GeneratorConfig.getExternalReferences().getClassFrom(reference).getName() +
                            " does not contain method named " + methodName
            );
        }

        var inputSize = methodInputs.size() + getContextFields().size();
        var method = methods.stream().filter(it -> it.getParameterTypes().length == inputSize).findFirst();
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
        var contextFields = getContextFields();
        return CodeBlock
                .builder()
                .add("$N.$L(", declaringName, methodName)
                .add(StringUtils.repeat("$L", ", ", methodInputs.size()), methodInputs.toArray())
                .addIf(!contextFields.isEmpty(), ", ")
                .add(StringUtils.repeat("$L", ", ", contextFields.size()), contextFields.keySet().stream().map(VariablePrefix::contextFieldPrefix).toList().toArray())
                .add(")")
                .build();
    }

    public boolean isOverride() {
        return override;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof SQLCondition) {
            return ((SQLCondition) obj).getReference().equals(this.getReference()) && ((SQLCondition) obj).isOverride() == this.isOverride();
        } else {
            return false;
        }
    }
}
