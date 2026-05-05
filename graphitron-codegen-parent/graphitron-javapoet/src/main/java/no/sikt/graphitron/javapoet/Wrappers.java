package no.sikt.graphitron.javapoet;

import java.util.function.UnaryOperator;

public class Wrappers {
    /**
     * returns a transform that wraps the receiver builder's accumulated content in {@code if (conditionValue != null) { ... }}.
     */
    public static UnaryOperator<CodeBlock.Builder> wrapNotNull(String conditionValue) {
        return wrapNotNull(CodeBlock.ofVar(conditionValue));
    }

    /**
     * returns a transform that wraps the receiver builder's accumulated content in {@code if (conditionValue != null) { ... }}.
     */
    public static UnaryOperator<CodeBlock.Builder> wrapNotNull(CodeBlock conditionValue) {
        return b -> CodeBlock
                .builder()
                .beginControlFlow("if ($L != null)", conditionValue)
                .add(b.build())
                .endControlFlow();
    }
}
