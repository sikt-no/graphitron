package no.sikt.graphitron.generators.codeinterface;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringBuilderMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_HANDLER;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RUNTIME_WIRING_BUILDER;

/**
 * This class generates a simple method for getting a RuntimeWiring Builder.
 */
public class CodeInterfaceBuilderMethodGenerator extends WiringBuilderMethodGenerator {
    public CodeInterfaceBuilderMethodGenerator(ProcessedSchema processedSchema) {
        super(List.of(), processedSchema);
    }

    @Override
    public MethodSpec generate() {
        var code = includeNode ? CodeBlock.of("($N)",
                GeneratorConfig.shouldMakeNodeStrategy() ? VAR_NODE_STRATEGY : VAR_NODE_HANDLER)
                : CodeBlock.of("()");
        var className = getGeneratedClassName(WiringClassGenerator.SAVE_DIRECTORY_NAME, WiringClassGenerator.CLASS_NAME);
        return getSpec(METHOD_NAME, RUNTIME_WIRING_BUILDER.className)
                .addCode(returnWrap(CodeBlock.of("$T.$L$L", className, WiringBuilderMethodGenerator.METHOD_NAME, code)))
                .build();
    }
}
