package no.sikt.graphitron.generators.codeinterface.wiring;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_HANDLER_NAME;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_STRATEGY_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RUNTIME_WIRING;

/**
 * This class generates code for the RuntimeWiring fetching method.
 */
public class WiringMethodGenerator extends WiringBuilderMethodGenerator {
    public static final String METHOD_NAME = "getRuntimeWiring";

    public WiringMethodGenerator(ProcessedSchema processedSchema) {
        super(List.of(), processedSchema);
    }

    @Override
    public MethodSpec generate() {
        var code = CodeBlock
                .builder()
                .add("$N", WiringBuilderMethodGenerator.METHOD_NAME)
                .add(includeNode ? CodeBlock.of("($N)",
                        GeneratorConfig.shouldMakeNodeStrategy() ? NODE_ID_STRATEGY_NAME : NODE_ID_HANDLER_NAME)
                        : CodeBlock.of("()")
                )
                .add(".build()");
        return getSpec(METHOD_NAME, RUNTIME_WIRING.className)
                .addCode(returnWrap(code.build()))
                .build();
    }
}
