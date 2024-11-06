package no.sikt.graphitron.generators.datafetcherresolvers.fetch;

import com.squareup.javapoet.*;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asEntityQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * This class generates the main entity resolver.
 */
public class EntityFetcherResolverMethodGenerator extends DataFetcherMethodGenerator<ObjectField> {
    private static final String
            METHOD_NAME = "entityFetcher",
            VARIABLE_TYPENAME = "_typeName",
            VARIABLE_OBJECT = "_obj";
    private static final TypeName LIST_MAP_TYPE = ParameterizedTypeName.get(
            LIST.className,
            ParameterizedTypeName.get(MAP.className, STRING.className, TypeName.OBJECT)
    ),
    DATA_FETCHER_TYPE = ParameterizedTypeName.get(DATA_FETCHER.className, LIST_MAP_TYPE);

    public EntityFetcherResolverMethodGenerator(ObjectDefinition target, ProcessedSchema processedSchema) {
        super(target, processedSchema);
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var cases = CodeBlock.builder();
        var entities = processedSchema.getEntities().values();
        for (var entity : entities) {
            var caseContent = CodeBlock.of(
                    "$N.putAll($T.$L($N, $N))",
                    VARIABLE_OBJECT,
                    ClassName.get(GeneratorConfig.outputPackage() + "." + DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + FetchDBClassGenerator.SAVE_DIRECTORY_NAME, asQueryClass(entity.getName())),
                    asEntityQueryMethodName(entity.getName()),
                    CONTEXT_NAME,
                    VARIABLE_INTERNAL_ITERATION
            );
            cases.add(breakCaseWrap(entity.getName(), caseContent));
        }

        var methodCode = CodeBlock
                .builder()
                .beginControlFlow("return $N -> (($T) $N.getArgument($S)).stream().map($L ->", VARIABLE_ENV, LIST_MAP_TYPE, VARIABLE_ENV, FEDERATION_REPRESENTATIONS_ARGUMENT.getName(), VARIABLE_INTERNAL_ITERATION)
                .add(declare(CONTEXT_NAME, CodeBlock.of("$N.getLocalContext()", VARIABLE_ENV)))
                .add(declare(VARIABLE_TYPENAME, CodeBlock.of("($T) $N.get($S)", STRING.className, VARIABLE_INTERNAL_ITERATION, TYPE_NAME.getName())))
                .add(declare(VARIABLE_OBJECT, ParameterizedTypeName.get(HASH_MAP.className, STRING.className, TypeName.OBJECT)))
                .addStatement("$N.put($S, $N)", VARIABLE_OBJECT, TYPE_NAME.getName(), VARIABLE_TYPENAME)
                .beginControlFlow("switch ($N)", VARIABLE_TYPENAME)
                .add(cases.build())
                .add("default: $L", returnWrap("null"))
                .endControlFlow()
                .add(returnWrap(VARIABLE_OBJECT))
                .endControlFlow(")$L", collectToList());
        return getDefaultSpecBuilder(METHOD_NAME, DATA_FETCHER_TYPE)
                .addCode(methodCode.build())
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        var field = getLocalObject().getFieldByName(FEDERATION_ENTITIES_FIELD.getName());
        if (field == null || field.isExplicitlyNotGenerated()) {
            return List.of();
        }
        return List.of(generate((ObjectField) field));
    }

    @Override
    public boolean generatesAll() {
        return true;
    }
}
