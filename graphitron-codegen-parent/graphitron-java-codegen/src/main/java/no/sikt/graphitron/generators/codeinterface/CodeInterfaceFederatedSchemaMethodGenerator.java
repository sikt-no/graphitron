package no.sikt.graphitron.generators.codeinterface;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.SimpleMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.typeregistry.TypeRegistryMethodGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringBuilderMethodGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.EntityFetcherClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.EntityFetcherMethodGenerator;
import no.sikt.graphitron.generators.datafetchers.typeresolvers.TypeResolverClassGenerator;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME;
import static no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator.FILE_NAME_SUFFIX;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asTypeResolverMethodName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_HANDLER;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;
import static no.sikt.graphitron.javapoet.CodeBlock.empty;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.FEDERATION_ENTITY_UNION;

/**
 * Generates methods for building a Federation 2 compatible GraphQL schema.
 * <p>
 * Generates two methods:
 * <ul>
 *   <li>{@code getFederatedSchema(registry, wiringBuilder, ...)} - flexible API for consumers who need custom wiring</li>
 *   <li>{@code getSchema(..., includeFederation)} - simple API that delegates to getFederatedSchema</li>
 * </ul>
 * <p>
 * When the schema has resolvable entities (hasEntitiesField), the getFederatedSchema method includes
 * entity type resolver and entity fetcher parameters.
 */
public class CodeInterfaceFederatedSchemaMethodGenerator extends SimpleMethodGenerator {
    public static final String METHOD_NAME = "getFederatedSchema";
    private static final String VAR_REGISTRY = "registry", VAR_WIRING = "wiring", VAR_WIRING_BUILDER = "wiringBuilder";
    private static final String VAR_INCLUDE_FEDERATION = "includeFederation";

    private final ProcessedSchema processedSchema;
    private final boolean includeNode;
    private final boolean hasEntities;
    private final boolean useNodeStrategy;
    private final String nodeParam;
    private final ClassName nodeParamClass;

    public CodeInterfaceFederatedSchemaMethodGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
        this.includeNode = processedSchema.nodeExists();
        this.hasEntities = processedSchema.hasEntitiesField();
        this.useNodeStrategy = GeneratorConfig.shouldMakeNodeStrategy();
        this.nodeParam = useNodeStrategy ? VAR_NODE_STRATEGY : VAR_NODE_HANDLER;
        this.nodeParamClass = useNodeStrategy ? NODE_ID_STRATEGY.className : NODE_ID_HANDLER.className;
    }

    @Override
    public List<MethodSpec> generateAll() {
        return List.of(generateFederatedSchemaMethod(), generateSchemaOverloadMethod());
    }

    private MethodSpec generateFederatedSchemaMethod() {
        var spec = MethodSpec.methodBuilder(METHOD_NAME)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(GRAPHQL_SCHEMA.className)
                .addParameter(TYPE_DEFINITION_REGISTRY.className, VAR_REGISTRY)
                .addParameter(RUNTIME_WIRING_BUILDER.className, VAR_WIRING_BUILDER);

        if (hasEntities) {
            spec.addParameterIf(useNodeStrategy, nodeParamClass, nodeParam);
            spec.addCode(returnWrap(buildFederatedSchemaWithEntities()));
        } else {
            spec.addCode(returnWrap(buildFederatedSchemaSimple()));
        }

        return spec.build();
    }

    private MethodSpec generateSchemaOverloadMethod() {
        var nodeParamCode = includeNode ? nodeParam : empty();

        return MethodSpec.methodBuilder(CodeInterfaceSchemaMethodGenerator.METHOD_NAME)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(GRAPHQL_SCHEMA.className)
                .addParameterIf(includeNode, nodeParamClass, nodeParam)
                .addParameter(boolean.class, VAR_INCLUDE_FEDERATION)
                .beginControlFlow("if (!$N)", VAR_INCLUDE_FEDERATION)
                .addCode(returnWrap(CodeBlock.of("$L($L)", CodeInterfaceSchemaMethodGenerator.METHOD_NAME, nodeParamCode)))
                .endControlFlow()
                .declare(VAR_WIRING, "$L($L)", WiringBuilderMethodGenerator.METHOD_NAME, nodeParamCode)
                .declare(VAR_REGISTRY, "$L()", TypeRegistryMethodGenerator.METHOD_NAME)
                .addCode(returnWrap(buildGetFederatedSchemaCall()))
                .build();
    }

    private CodeBlock buildGetFederatedSchemaCall() {
        if (hasEntities && useNodeStrategy) {
            return CodeBlock.of("$L($N, $N, $N)", METHOD_NAME, VAR_REGISTRY, VAR_WIRING, nodeParam);
        }
        return CodeBlock.of("$L($N, $N)", METHOD_NAME, VAR_REGISTRY, VAR_WIRING);
    }

    private CodeBlock buildFederatedSchemaSimple() {
        return CodeBlock.of(
                "$T.buildFederatedSchema($N, $N)",
                FEDERATION_HELPER.className,
                VAR_REGISTRY,
                VAR_WIRING_BUILDER
        );
    }

    private CodeBlock buildFederatedSchemaWithEntities() {
        var queryTypeName = processedSchema.getQueryType().getName();
        var entityFetcherClassName = getGeneratedClassName(
                DEFAULT_SAVE_DIRECTORY_NAME + "." + EntityFetcherClassGenerator.SAVE_DIRECTORY_NAME,
                queryTypeName + EntityFetcherClassGenerator.CLASS_NAME + FILE_NAME_SUFFIX
        );
        var entityTypeResolverClassName = getGeneratedClassName(
                DEFAULT_SAVE_DIRECTORY_NAME + "." + TypeResolverClassGenerator.SAVE_DIRECTORY_NAME,
                FEDERATION_ENTITY_UNION.getName().replace("_", "") + TypeResolverClassGenerator.FILE_NAME_SUFFIX
        );
        var typeResolverMethodName = asTypeResolverMethodName(FEDERATION_ENTITY_UNION.getName());

        var entityFetcherCall = CodeBlock.of("$T.$L($L)", entityFetcherClassName, EntityFetcherMethodGenerator.METHOD_NAME,
                useNodeStrategy ? nodeParam : empty());

        return CodeBlock.of(
                "$T.buildFederatedSchema($N, $N, $T.$L(), $L)",
                FEDERATION_HELPER.className,
                VAR_REGISTRY,
                VAR_WIRING_BUILDER,
                entityTypeResolverClassName,
                typeResolverMethodName,
                entityFetcherCall
        );
    }
}
