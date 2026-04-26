package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.TypeVariableName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code GraphitronContext} interface file, emitted once per code-generation run
 * alongside other rewrite output.
 *
 * <p>The emitted interface is the per-app DFE-aware extension point for fetchers:
 * generated fetchers retrieve an implementation via
 * {@code env.getGraphQlContext().get(GraphitronContext.class)} and call
 * {@code getDslContext(env)}, {@code getContextArgument(env, name)}, or
 * {@code getTenantId(env)} at resolve time.
 *
 * <p>Emitted as generated output rather than depending on
 * {@code no.sikt.graphql.GraphitronContext} in {@code graphitron-common} so that emitted code
 * depends only on the app's own build output plus jOOQ and graphql-java.
 */
public class GraphitronContextInterfaceGenerator {

    public static final String CLASS_NAME = "GraphitronContext";

    private static final ClassName ENV         = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");

    public static List<TypeSpec> generate() {
        var getDslContext = MethodSpec.methodBuilder("getDslContext")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(DSL_CONTEXT)
            .addParameter(ENV, "env")
            .addJavadoc("Returns the jOOQ {@code DSLContext} Graphitron should use for this fetch.\n")
            .build();

        var T = TypeVariableName.get("T");
        var getContextArgument = MethodSpec.methodBuilder("getContextArgument")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(T)
            .returns(T)
            .addParameter(ENV, "env")
            .addParameter(String.class, "name")
            .addJavadoc("Resolves the named {@code contextArgument} value (see {@code @condition}\n"
                + "and {@code @tableMethod} directives) for this fetch.\n")
            .build();

        var getTenantId = MethodSpec.methodBuilder("getTenantId")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(String.class)
            .addParameter(ENV, "env")
            .addStatement("return $S", "")
            .addJavadoc("Returns the tenant identifier for the current request, or an empty string\n"
                + "when tenant scoping does not apply. Graphitron concatenates this with the field\n"
                + "path to build DataLoader names; two tenants issuing the same query must not\n"
                + "share a DataLoader cache.\n")
            .build();

        var spec = TypeSpec.interfaceBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Per-request extension point that brokers runtime values (DSLContext, context\n"
                + "arguments, tenant id) into generated fetchers. Apps supply an implementation per\n"
                + "request via\n"
                + "{@code ExecutionInput.newExecutionInput(...).graphQLContext(Map.of(GraphitronContext.class, impl))}.\n")
            .addMethod(getDslContext)
            .addMethod(getContextArgument)
            .addMethod(getTenantId)
            .build();

        return List.of(spec);
    }
}
