package no.fellesstudentsystem.graphitron.generators.codebuilding;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import org.jetbrains.annotations.NotNull;

import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asListedName;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asRecordName;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

public class FormatCodeBlocks {
    private final static CodeBlock COLLECT_TO_LIST = CodeBlock.builder().add(".collect($T.toList())", COLLECTORS.className).build();

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
    public static CodeBlock collectToList() {
        return COLLECT_TO_LIST;
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
