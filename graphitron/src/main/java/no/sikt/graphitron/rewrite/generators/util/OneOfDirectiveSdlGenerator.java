package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.OneOfDirectiveSdl;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Emits the consumer-side {@code OneOfDirectiveSdl} runtime helper into
 * {@code <outputPackage>.util}. The helper corrects the runtime {@code _Service.sdl} value baked
 * by {@code SchemaTransformer.build}, which routes through
 * {@link com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter#generateServiceSDLV2}
 * and therefore drops the {@code @oneOf} directive definition. See
 * {@link OneOfDirectiveSdl} for the full diagnosis.
 *
 * <p>This is the runtime mirror of the codegen-side {@link OneOfDirectiveSdl}: the consumer
 * compiles the generated {@code GraphitronSchema} against graphql-java + federation-jvm only,
 * with the {@code graphitron} codegen module deliberately off its classpath, so the runtime arm
 * cannot call {@code OneOfDirectiveSdl} directly. The single semantic invariant, the exact
 * definition string, is single-sourced: the emitted {@code DEFINITION} literal comes from
 * {@link OneOfDirectiveSdl#DEFINITION}.
 *
 * <p>{@code GraphitronSchemaClassGenerator} wraps the federation {@code build}'s final
 * {@code return fb.build()} in {@code OneOfDirectiveSdl.withOneOfDefinition(...)} only when the
 * schema uses {@code @oneOf}; {@code GraphQLRewriteGenerator} emits this helper under the same gate
 * conjoined with {@code federationLink} (the helper has no caller on a non-federation schema, whose
 * file arm already prints the definition through graphql-java's {@code SchemaPrinter}). The
 * writer's orphan sweep removes the emitted source if a schema later drops {@code @oneOf} or
 * federation.
 *
 * <p>Generated as a source file so consuming projects take no runtime dependency on graphitron;
 * every type it references ({@code GraphQLSchema}, {@code ServiceSDLPrinter},
 * {@code StaticDataFetcher}, {@code FieldCoordinates}) is already on the consumer's compile
 * classpath.
 */
public final class OneOfDirectiveSdlGenerator {

    public static final String CLASS_NAME = "OneOfDirectiveSdl";
    public static final String WITH_ONE_OF_DEFINITION_METHOD = "withOneOfDefinition";

    private static final ClassName GRAPHQL_SCHEMA       = ClassName.get("graphql.schema", "GraphQLSchema");
    private static final ClassName GRAPHQL_NAMED_TYPE   = ClassName.get("graphql.schema", "GraphQLNamedType");
    private static final ClassName GRAPHQL_INPUT_OBJECT = ClassName.get("graphql.schema", "GraphQLInputObjectType");
    private static final ClassName CODE_REGISTRY        = ClassName.get("graphql.schema", "GraphQLCodeRegistry");
    private static final ClassName FIELD_COORDINATES    = ClassName.get("graphql.schema", "FieldCoordinates");
    private static final ClassName STATIC_DATA_FETCHER  = ClassName.get("graphql.schema", "StaticDataFetcher");
    private static final ClassName SERVICE_SDL_PRINTER  =
        ClassName.get("com.apollographql.federation.graphqljava.printer", "ServiceSDLPrinter");

    private OneOfDirectiveSdlGenerator() {
    }

    public static List<TypeSpec> generate(String outputPackage) {
        var definition = FieldSpec.builder(String.class, "DEFINITION",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$S", OneOfDirectiveSdl.DEFINITION)
            .addJavadoc("The canonical {@code @oneOf} directive definition, single-sourced from\n"
                + "the codegen-side {@code OneOfDirectiveSdl.DEFINITION}.\n")
            .build();

        var body = CodeBlock.builder();
        body.addStatement("boolean usesOneOf = false");
        body.beginControlFlow("for ($T type : schema.getAllTypesAsList())", GRAPHQL_NAMED_TYPE);
        body.beginControlFlow("if (type instanceof $T inputType && inputType.isOneOf())", GRAPHQL_INPUT_OBJECT);
        body.addStatement("usesOneOf = true");
        body.addStatement("break");
        body.endControlFlow();
        body.endControlFlow();
        body.beginControlFlow("if (!usesOneOf)");
        body.addStatement("return schema");
        body.endControlFlow();
        body.addStatement("String sdl = $T.generateServiceSDLV2(schema)", SERVICE_SDL_PRINTER);
        // Future-proofing: if a graphql-java release starts printing the spec-built-in definition,
        // leave the served value untouched rather than appending a duplicate.
        body.beginControlFlow("if (sdl.contains($S))", "directive @oneOf");
        body.addStatement("return schema");
        body.endControlFlow();
        body.addStatement("String base = sdl.endsWith($S) ? sdl : sdl + $S", "\n", "\n");
        body.addStatement("String augmented = base + $S + DEFINITION + $S", "\n", "\n");
        body.addStatement("$T codeRegistry = schema.getCodeRegistry().transform(\n"
                + "    registryBuilder -> registryBuilder.dataFetcher(\n"
                + "        $T.coordinates($S, $S),\n"
                + "        new $T(augmented)))",
            CODE_REGISTRY, FIELD_COORDINATES, "_Service", "sdl", STATIC_DATA_FETCHER);
        body.addStatement("return schema.transform(schemaBuilder -> schemaBuilder.codeRegistry(codeRegistry))");

        var withOneOfDefinition = MethodSpec.methodBuilder(WITH_ONE_OF_DEFINITION_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(GRAPHQL_SCHEMA)
            .addParameter(GRAPHQL_SCHEMA, "schema")
            .addJavadoc("Returns {@code schema} with the {@code @oneOf} directive definition\n"
                + "reinstated on the runtime {@code _Service.sdl} value. No-op unless an input\n"
                + "object reports {@code isOneOf()} and the definition is not already present.\n"
                + "Re-prints the served SDL via {@code ServiceSDLPrinter.generateServiceSDLV2},\n"
                + "appends {@link #DEFINITION}, and reinstalls a {@code StaticDataFetcher} carrying\n"
                + "the augmented string on the {@code _Service.sdl} field.\n")
            .addCode(body.build())
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Reinstates the GraphQL {@code @oneOf} directive definition on the runtime\n"
                + "{@code _Service.sdl} value. Generated once per federation run that uses\n"
                + "{@code @oneOf}; see {@code OneOfDirectiveSdlGenerator} for emission semantics.\n")
            .addField(definition)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(withOneOfDefinition)
            .build();

        return List.of(spec);
    }
}
