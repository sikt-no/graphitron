package no.sikt.graphitron.generators.datafetchers.resolvers.fetch;

import com.palantir.javapoet.*;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.generators.abstractions.DBClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherMethodGenerator;
import no.sikt.graphitron.generators.datafetchers.wiring.WiringContainer;
import no.sikt.graphitron.generators.db.fetch.FetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asEntityQueryMethodName;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asQueryClass;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getGeneratedClassName;
import static no.sikt.graphitron.generators.codebuilding.TypeNameFormat.getObjectMapTypeName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.*;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

/**
 * This class generates the main entity resolver.
 */
public class EntityFetcherResolverMethodGenerator extends DataFetcherMethodGenerator<ObjectField> {
    public static final String METHOD_NAME = "entityFetcher";
    private static final String VARIABLE_TYPENAME = "_typeName";
    protected static final TypeName LIST_MAP_TYPE = ParameterizedTypeName.get(LIST.className, getObjectMapTypeName()),
    DATA_FETCHER_TYPE = ParameterizedTypeName.get(DATA_FETCHER.className, LIST_MAP_TYPE);

    public EntityFetcherResolverMethodGenerator(ProcessedSchema processedSchema) {
        super(null, processedSchema);
        if (processedSchema.hasEntitiesField()) {
            dataFetcherWiring.add(new WiringContainer(METHOD_NAME, processedSchema.getQueryType().getName(), FEDERATION_ENTITIES_FIELD.getName()));
        }
    }

    @Override
    public MethodSpec generate(ObjectField dummy) {
        var cases = CodeBlock.builder();
        var entities = processedSchema.getEntities().values();
        for (var entity : entities) {
            var caseContent = CodeBlock.of(
                    "$N.putAll($T.$L($N, $N))",
                    VARIABLE_OBJECT,
                    getGeneratedClassName(DBClassGenerator.DEFAULT_SAVE_DIRECTORY_NAME + "." + FetchDBClassGenerator.SAVE_DIRECTORY_NAME, asQueryClass(entity.getName())),
                    asEntityQueryMethodName(entity.getName()),
                    CONTEXT_NAME,
                    VARIABLE_INTERNAL_ITERATION
            );
            cases.add(breakCaseWrap(entity.getName(), caseContent));
        }

        return getDefaultSpecBuilder(METHOD_NAME, DATA_FETCHER_TYPE)
                .beginControlFlow("return $N -> (($T) $N.getArgument($S)).stream().map($L ->", VARIABLE_ENV, LIST_MAP_TYPE, VARIABLE_ENV, FEDERATION_REPRESENTATIONS_ARGUMENT.getName(), VARIABLE_INTERNAL_ITERATION)
                .addCode(declare(CONTEXT_NAME, CodeBlock.of("($T) $N.getLocalContext()", DSL_CONTEXT.className, VARIABLE_ENV)))
                .addCode(declare(VARIABLE_TYPENAME, CodeBlock.of("($T) $N.get($S)", STRING.className, VARIABLE_INTERNAL_ITERATION, TYPE_NAME.getName())))
                .addCode(declare(VARIABLE_OBJECT, ParameterizedTypeName.get(HASH_MAP.className, STRING.className, ClassName.get("java.lang", "Object"))))
                .addStatement("$N.put($S, $N)", VARIABLE_OBJECT, TYPE_NAME.getName(), VARIABLE_TYPENAME)
                .beginControlFlow("switch ($N)", VARIABLE_TYPENAME)
                .addCode(cases.build())
                .addCode("default: $L", returnWrap("null"))
                .endControlFlow()
                .addCode(returnWrap(VARIABLE_OBJECT))
                .endControlFlow(")$L", collectToList())
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        // With the current setup, we do not actually have the field in our schema on generation time!
        // var field = processedSchema.getEntitiesField();
        // if (field == null || field.isExplicitlyNotGenerated()) {
        if (!processedSchema.hasEntitiesField()) {
            return List.of();
        }
        return List.of(generate(null));  // Does not currently require access to the field here.
    }

    @Override
    public boolean generatesAll() {
        return true;
    }
}
