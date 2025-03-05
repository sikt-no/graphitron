package no.sikt.graphitron.generators.codeinterface;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringBuilderMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_HANDLER_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RUNTIME_WIRING_BUILDER;

/**
 * This class generates a simple method for getting a RuntimeWiring Builder.
 */
public class CodeInterfaceBuilderMethodGenerator extends WiringBuilderMethodGenerator {
    public CodeInterfaceBuilderMethodGenerator(boolean includeNode) {
        super(List.of(), includeNode);
    }

    @Override
    public MethodSpec generate() {
        var code = includeNode ? CodeBlock.of("($N)", NODE_ID_HANDLER_NAME) : CodeBlock.of("()");
        var className = getGeneratedClassName(WiringClassGenerator.SAVE_DIRECTORY_NAME, WiringClassGenerator.CLASS_NAME);
        return getSpec(METHOD_NAME, RUNTIME_WIRING_BUILDER.className)
                .addCode(returnWrap(CodeBlock.of("$T.$L$L", className, WiringBuilderMethodGenerator.METHOD_NAME, code)))
                .build();
    }
}
