package no.fellesstudentsystem.graphitron.generators.codebuilding;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.generators.abstractions.AbstractMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import org.jetbrains.annotations.NotNull;

import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asListedName;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asRecordName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

public class FormatCodeBlocks {
    private final static CodeBlock
            COLLECT_TO_LIST = CodeBlock.of(".collect($T.toList())", COLLECTORS.className),
            GET_CONTEXT_METHOD = CodeBlock.of("$N.getLocalContext()", AbstractMethodGenerator.ENV_NAME),
            DECLARE_CONTEXT_VARIABLE = CodeBlock.of(
                    "var $N = $L == null ? this.$N : ($T) $L",
                    Dependency.CONTEXT_NAME,
                    GET_CONTEXT_METHOD,
                    Dependency.CONTEXT_NAME,
                    DSL_CONTEXT.className,
                    GET_CONTEXT_METHOD
            ),
            FIND_FIRST = CodeBlock.of(".stream().findFirst()"),
            EMPTY_LIST = CodeBlock.of("$T.of()", LIST.className),
            EMPTY_SET = CodeBlock.of("$T.of()", SET.className),
            EMPTY_MAP = CodeBlock.of("$T.of()", MAP.className);

    @NotNull
    public static CodeBlock declareArrayList(String variableName, TypeName typeName) {
        return CodeBlock
                .builder()
                .addStatement("var $L = new $T<$T>()", asListedName(variableName), ARRAY_LIST.className, typeName)
                .build();
    }

    public static CodeBlock declareRecord(String name, TypeName sqlRecordClassName) {
        var recordName = asRecordName(name);
        return CodeBlock
                .builder()
                .add(declareVariable(recordName, sqlRecordClassName))
                .addStatement("$N.attach($N.configuration())", recordName, Dependency.CONTEXT_NAME)
                .build();
    }

    public static CodeBlock declareVariable(String name, TypeName typeName) {
        return CodeBlock.builder().addStatement("var $L = new $T()", name, typeName).build();
    }

    @NotNull
    public static CodeBlock ifNotNull(String name) {
        return CodeBlock.of("if ($N != null)", name);
    }

    @NotNull
    public static CodeBlock addToList(String addTarget, String addition) {
        return CodeBlock.builder().addStatement("$N.add($N)", addTarget, addition).build();
    }

    @NotNull
    public static CodeBlock addToList(String addTarget, CodeBlock addition) {
        return CodeBlock.builder().addStatement("$N.add($L)", addTarget, addition).build();
    }

    @NotNull
    public static CodeBlock listOf() {
        return EMPTY_LIST;
    }

    @NotNull
    public static CodeBlock listOf(CodeBlock content) {
        return CodeBlock.of("$T.of($L)", LIST.className, content);
    }

    @NotNull
    public static CodeBlock listOf(String variableName) {
        return CodeBlock.of("$T.of($N)", LIST.className, variableName);
    }

    @NotNull
    public static CodeBlock setOf() {
        return EMPTY_SET;
    }

    @NotNull
    public static CodeBlock setOf(CodeBlock content) {
        return CodeBlock.of("$T.of($L)", SET.className, content);
    }

    @NotNull
    public static CodeBlock setOf(String variableName) {
        return CodeBlock.of("$T.of($N)", SET.className, variableName);
    }

    @NotNull
    public static CodeBlock mapOf() {
        return EMPTY_MAP;
    }

    @NotNull
    public static CodeBlock mapOf(CodeBlock content) {
        return CodeBlock.of("$T.of($L)", MAP.className, content);
    }

    @NotNull
    public static CodeBlock collectToList() {
        return COLLECT_TO_LIST;
    }

    @NotNull
    public static CodeBlock findFirst() {
        return FIND_FIRST;
    }

    @NotNull
    public static CodeBlock nullIfNullElse(String variable) {
        return CodeBlock.of("$N == null ? null : ", variable);
    }

    @NotNull
    public static CodeBlock nullIfNullElse(CodeBlock check) {
        return CodeBlock.of("$L == null ? null : ", check);
    }

    @NotNull
    public static CodeBlock declareContextVariable() {
        return DECLARE_CONTEXT_VARIABLE;
    }

    @NotNull
    public static CodeBlock returnCompletedFuture(String resultName) {
        return CodeBlock
                .builder()
                .addStatement("return $T.completedFuture($N)", COMPLETABLE_FUTURE.className, resultName)
                .build();
    }

    @NotNull
    public static CodeBlock returnCompletedFuture(CodeBlock result) {
        return CodeBlock
                .builder()
                .addStatement("return $T.completedFuture($L)", COMPLETABLE_FUTURE.className, result)
                .build();
    }
}
