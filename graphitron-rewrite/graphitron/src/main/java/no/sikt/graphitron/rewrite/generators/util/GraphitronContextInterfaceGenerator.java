package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
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

    private static final ClassName ENV               = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName DSL_CONTEXT       = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName VALIDATOR         = ClassName.get("jakarta.validation", "Validator");
    private static final ClassName VALIDATION        = ClassName.get("jakarta.validation", "Validation");

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
                + "share a DataLoader cache.\n"
                + "\n"
                + "<p>If your {@link #getDslContext} returns different contexts within one request\n"
                + "(e.g. selected per id), {@code getTenantId} MUST partition by the same key.\n"
                + "Graphitron uses the first key context's {@code DSLContext} for the entire batch,\n"
                + "so keys colliding into one loader silently fall back to whichever id was\n"
                + "submitted first. The default empty-string fallback assumes a single\n"
                + "{@code DSLContext} for the request.\n")
            .build();

        // Lazy default validator: holder-class idiom guarantees one-time initialisation per JVM
        // and avoids touching jakarta.validation classes at interface load time.
        // Nested types on an interface are implicitly public + static; JavaPoet still requires
        // both modifiers be set explicitly.
        var defaultValidatorHolder = TypeSpec.classBuilder("DefaultValidatorHolder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Lazy holder for the default {@link $T} (one per JVM). Initialised on the\n"
                + "first call to {@link GraphitronContext#getValidator}.\n", VALIDATOR)
            .addField(FieldSpec.builder(VALIDATOR, "INSTANCE",
                    Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.buildDefaultValidatorFactory().getValidator()", VALIDATION)
                .build())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .build();

        var getValidator = MethodSpec.methodBuilder("getValidator")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(VALIDATOR)
            .addParameter(ENV, "env")
            .addStatement("return DefaultValidatorHolder.INSTANCE")
            .addJavadoc("Returns the {@link $T} the wrapper's pre-execution validation step (R12 §5)\n"
                + "should use for this fetch. Defaults to the JVM's default validator factory\n"
                + "({@code Validation.buildDefaultValidatorFactory().getValidator()}); apps that\n"
                + "want a custom factory (per-tenant message interpolators, alternative providers,\n"
                + "etc.) override this method.\n", VALIDATOR)
            .build();

        var spec = TypeSpec.interfaceBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Per-request extension point that brokers runtime values (DSLContext, context\n"
                + "arguments, tenant id, validator) into generated fetchers. Apps supply an\n"
                + "implementation per request via\n"
                + "{@code ExecutionInput.newExecutionInput(...).graphQLContext(Map.of(GraphitronContext.class, impl))}.\n")
            .addMethod(getDslContext)
            .addMethod(getContextArgument)
            .addMethod(getTenantId)
            .addMethod(getValidator)
            .addType(defaultValidatorHolder)
            .build();

        return List.of(spec);
    }
}
