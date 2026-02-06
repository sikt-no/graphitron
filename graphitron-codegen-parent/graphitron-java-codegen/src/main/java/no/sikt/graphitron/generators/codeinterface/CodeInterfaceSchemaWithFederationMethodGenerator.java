package no.sikt.graphitron.generators.codeinterface;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.SimpleMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.typeregistry.TypeRegistryMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringBuilderMethodGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_HANDLER;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;
import static no.sikt.graphitron.javapoet.CodeBlock.empty;
import static no.sikt.graphitron.javapoet.TypeName.BOOLEAN;
import static no.sikt.graphitron.mappings.JavaPoetClassName.GRAPHQL_SCHEMA;
import static no.sikt.graphitron.mappings.JavaPoetClassName.NODE_ID_HANDLER;
import static no.sikt.graphitron.mappings.JavaPoetClassName.NODE_ID_STRATEGY;

/**
 * Generates the {@code getSchema(..., includeFederation)} overload method that provides a simple API
 * for getting either a regular or federated schema based on a boolean flag.
 * <p>
 * This method delegates to {@code getFederatedSchema} when federation is enabled,
 * or falls back to the regular {@code getSchema} method otherwise.
 */
public class CodeInterfaceSchemaWithFederationMethodGenerator extends SimpleMethodGenerator {
    private static final String VAR_REGISTRY = "registry", VAR_WIRING = "wiring";
    private static final String VAR_INCLUDE_FEDERATION = "includeFederation";

    private final boolean includeNode;
    private final boolean hasEntities;
    private final boolean useNodeStrategy;
    private final String nodeParam;
    private final ClassName nodeParamClass;

    public CodeInterfaceSchemaWithFederationMethodGenerator(ProcessedSchema processedSchema) {
        this.includeNode = processedSchema.nodeExists();
        this.hasEntities = processedSchema.federationEntitiesExist();
        this.useNodeStrategy = GeneratorConfig.shouldMakeNodeStrategy();
        this.nodeParam = useNodeStrategy ? VAR_NODE_STRATEGY : VAR_NODE_HANDLER;
        this.nodeParamClass = useNodeStrategy ? NODE_ID_STRATEGY.className : NODE_ID_HANDLER.className;
    }

    @Override
    public MethodSpec generate() {
        var nodeParamCode = includeNode ? nodeParam : empty();

        return MethodSpec.methodBuilder(CodeInterfaceSchemaMethodGenerator.METHOD_NAME)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(GRAPHQL_SCHEMA.className)
                .addParameterIf(includeNode, nodeParamClass, nodeParam)
                .addParameter(BOOLEAN, VAR_INCLUDE_FEDERATION)
                .beginControlFlow("if (!$N)", VAR_INCLUDE_FEDERATION)
                .addCode(returnWrap(CodeBlock.of("$L($L)", CodeInterfaceSchemaMethodGenerator.METHOD_NAME, nodeParamCode)))
                .endControlFlow()
                .declare(VAR_WIRING, "$L($L)", WiringBuilderMethodGenerator.METHOD_NAME, nodeParamCode)
                .declare(VAR_REGISTRY, "$L()", TypeRegistryMethodGenerator.METHOD_NAME)
                .addCode(returnWrap(buildGetFederatedSchemaCall()))
                .build();
    }

    private CodeBlock buildGetFederatedSchemaCall() {
        return CodeBlock.of("$L($N, $N$L)", CodeInterfaceGetFederatedSchemaMethodGenerator.METHOD_NAME, VAR_REGISTRY, VAR_WIRING,
                CodeBlock.ofIf(hasEntities && useNodeStrategy, ", $N", nodeParam));
    }
}
