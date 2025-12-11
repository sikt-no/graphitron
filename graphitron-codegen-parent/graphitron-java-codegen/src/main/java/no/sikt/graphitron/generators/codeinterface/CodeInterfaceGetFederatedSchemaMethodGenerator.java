package no.sikt.graphitron.generators.codeinterface;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.SimpleMethodGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.EntityFetcherClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.EntityFetcherMethodGenerator;
import no.sikt.graphitron.generators.datafetchers.typeresolvers.TypeResolverClassGenerator;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;

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
 * Generates the {@code getFederatedSchema(registry, wiringBuilder, ...)} method for building
 * a Federation 2 compatible GraphQL schema.
 * <p>
 * This provides a flexible API for consumers who need custom type registry and wiring configuration.
 * When the schema has resolvable entities, the method includes entity type resolver and entity fetcher parameters.
 */
public class CodeInterfaceGetFederatedSchemaMethodGenerator extends SimpleMethodGenerator {
    public static final String METHOD_NAME = "getFederatedSchema";
    private static final String VAR_REGISTRY = "registry", VAR_WIRING_BUILDER = "wiringBuilder";

    private final ProcessedSchema processedSchema;
    private final boolean hasEntities;
    private final boolean useNodeStrategy;
    private final String nodeParam;
    private final ClassName nodeParamClass;

    public CodeInterfaceGetFederatedSchemaMethodGenerator(ProcessedSchema processedSchema) {
        this.processedSchema = processedSchema;
        this.hasEntities = processedSchema.hasEntitiesField();
        this.useNodeStrategy = GeneratorConfig.shouldMakeNodeStrategy();
        this.nodeParam = useNodeStrategy ? VAR_NODE_STRATEGY : VAR_NODE_HANDLER;
        this.nodeParamClass = useNodeStrategy ? NODE_ID_STRATEGY.className : NODE_ID_HANDLER.className;
    }

    @Override
    public MethodSpec generate() {
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
