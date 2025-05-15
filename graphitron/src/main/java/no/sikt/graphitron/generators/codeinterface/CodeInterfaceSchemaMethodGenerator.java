package no.sikt.graphitron.generators.codeinterface;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.generators.abstractions.SimpleMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.typeregistry.TypeRegistryMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringBuilderMethodGenerator;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_HANDLER_NAME;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_STRATEGY_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

/**
 * This class generates code for a method for fetching a complete schema.
 */
public class CodeInterfaceSchemaMethodGenerator extends SimpleMethodGenerator {
    public static final String METHOD_NAME = "getSchema";
    private static final String VAR_REGISTRY = "registry", VAR_WIRING = "wiring";
    private final boolean includeNode;

    public CodeInterfaceSchemaMethodGenerator(boolean includeNode) {
        this.includeNode = includeNode;
    }

    @Override
    public MethodSpec generate() {
        var code = includeNode ? CodeBlock.of("($N)",
                GeneratorConfig.shouldMakeNodeStrategy() ? NODE_ID_STRATEGY_NAME : NODE_ID_HANDLER_NAME)
                : CodeBlock.of("()");
        var spec = MethodSpec.methodBuilder(METHOD_NAME);
        if (includeNode) {
            if (GeneratorConfig.shouldMakeNodeStrategy()) {
                spec.addParameter(NODE_ID_STRATEGY.className, NODE_ID_STRATEGY_NAME);
            } else {
                spec.addParameter(NODE_ID_HANDLER.className, NODE_ID_HANDLER_NAME);
            }
        }
        return spec
                .addModifiers(Modifier.PUBLIC)
                .addModifiers(Modifier.STATIC)
                .returns(GRAPHQL_SCHEMA.className)
                .addCode(declare(VAR_WIRING, CodeBlock.of("$L$L", WiringBuilderMethodGenerator.METHOD_NAME, code)))
                .addCode(declare(VAR_REGISTRY, CodeBlock.of("$L()", TypeRegistryMethodGenerator.METHOD_NAME)))
                .addCode(returnWrap(CodeBlock.of("new $T().makeExecutableSchema($N, $N.build())", SCHEMA_GENERATOR.className, VAR_REGISTRY, VAR_WIRING)))
                .build();
    }
}
