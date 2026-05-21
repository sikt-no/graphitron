package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code GraphitronContext} sealed interface plus its sole permitted implementation
 * {@code GraphitronContextImpl} (nested inside the interface to inherit same-compilation-unit
 * permits), emitted once per code-generation run alongside other rewrite output.
 *
 * <p>The emitted interface is the per-app per-request contract every generated DataFetcher reads
 * from. R190 sealed the interface and concentrated the per-request wiring behind
 * {@code Graphitron.newExecutionInput(...)}: apps no longer implement {@code GraphitronContext}
 * directly; the factory populates the per-request {@code GraphQLContext}, and the singleton
 * {@code GraphitronContextImpl} reads from it.
 *
 * <p>The impl is emitted as a nested {@code final class} inside the interface; Java's sealed-type
 * rules implicitly permit subclasses declared in the same compilation unit, so no explicit
 * {@code permits} clause is needed (and javapoet does not emit one). The "same package" placement
 * the design called for falls out automatically: a nested public-static class is reachable as
 * {@code GraphitronContext.GraphitronContextImpl} in the schema package.
 *
 * <p>Emitted as generated output rather than depending on
 * {@code no.sikt.graphql.GraphitronContext} in {@code graphitron-common} so that emitted code
 * depends only on the app's own build output plus jOOQ and graphql-java.
 */
public class GraphitronContextInterfaceGenerator {

    public static final String CLASS_NAME = "GraphitronContext";
    public static final String IMPL_CLASS_NAME = "GraphitronContextImpl";

    private static final ClassName ENV               = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName DSL_CONTEXT       = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName VALIDATOR         = ClassName.get("jakarta.validation", "Validator");
    private static final ClassName VALIDATION        = ClassName.get("jakarta.validation", "Validation");

    public static List<TypeSpec> generate() {
        var getDslContext = MethodSpec.methodBuilder("getDslContext")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(DSL_CONTEXT)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($T.class)", DSL_CONTEXT)
            .addJavadoc("Returns the per-request jOOQ {@code DSLContext}. Reads from the\n"
                + "{@code GraphQLContext} populated by {@code Graphitron.newExecutionInput(...)}\n"
                + "under the {@code DSLContext.class} typed key.\n")
            .build();

        var getContextArgument = MethodSpec.methodBuilder("getContextArgument")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addParameter(String.class, "name")
            .addStatement("Object value = env.getGraphQlContext().get(name)")
            .beginControlFlow("if (value == null)")
            .addStatement("throw new $T($S + name + $S)",
                IllegalStateException.class,
                "context value '",
                "' was not supplied; call Graphitron.newExecutionInput(...) to populate it")
            .endControlFlow()
            .addStatement("return value")
            .addJavadoc("Resolves the named {@code contextArgument} value (see {@code @condition},\n"
                + "{@code @service} and {@code @tableMethod} directives) for this fetch. The default\n"
                + "reads the value from the request's {@code GraphQLContext} under the given key; a\n"
                + "missing entry throws {@link IllegalStateException} naming the contextArgument and\n"
                + "pointing at {@code Graphitron.newExecutionInput(...)}. The cast to the expected\n"
                + "Java type happens at the generated call site, so a wrong-typed entry surfaces as a\n"
                + "{@link ClassCastException} there.\n"
                + "\n"
                + "<p><b>Server-log surface only.</b> The framework's redact path replaces the prose\n"
                + "message with a correlation-id reference before it reaches the consumer; the typed\n"
                + "{@code Graphitron.newExecutionInput(...)} factory is the load-bearing diagnostic\n"
                + "for missing or wrong-typed contextArguments. Both throw paths here are only\n"
                + "reachable when a consumer hand-rolls an {@code ExecutionInput.Builder} outside the\n"
                + "factory.\n")
            .build();

        // Lazy default validator: holder-class idiom guarantees one-time initialisation per JVM
        // and avoids touching jakarta.validation classes at interface load time.
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
            .addJavadoc("Returns the {@link $T} the wrapper's pre-execution validation step\n"
                + "should use for this fetch. Defaults to the JVM's default validator factory\n"
                + "({@code Validation.buildDefaultValidatorFactory().getValidator()}). A future\n"
                + "Mojo-driven hook will configure a custom factory; with the sealed interface,\n"
                + "apps no longer override this method directly.\n", VALIDATOR)
            .build();

        // Nested singleton impl: a final class inside a sealed interface is in the same
        // compilation unit, so Java's sealed-type rules implicitly permit it without an explicit
        // permits clause. Static + final + private ctor + public INSTANCE field gives a JVM-wide
        // singleton with no per-request state.
        var impl = TypeSpec.classBuilder(IMPL_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(ClassName.bestGuess(CLASS_NAME))
            .addJavadoc("Stateless singleton implementation of the sealed {@link $L}. Carries no\n"
                + "per-request state; every method reads from the per-request\n"
                + "{@code GraphQLContext} populated by {@code Graphitron.newExecutionInput(...)}.\n",
                CLASS_NAME)
            .addField(FieldSpec.builder(ClassName.bestGuess(IMPL_CLASS_NAME), "INSTANCE",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $L()", IMPL_CLASS_NAME)
                .build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .build())
            .build();

        var contextSpec = TypeSpec.interfaceBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.SEALED)
            .addJavadoc("Sealed per-request contract every generated DataFetcher reads from. Apps\n"
                + "supply per-request runtime values (DSLContext, contextArgument values) via\n"
                + "{@code Graphitron.newExecutionInput(...)}; the factory populates the\n"
                + "per-request {@code GraphQLContext} and stashes the singleton\n"
                + "{@link $L} under {@code GraphitronContext.class}. Apps no longer implement\n"
                + "this interface; the sealed declaration is what enforces the discipline.\n",
                IMPL_CLASS_NAME)
            .addMethod(getDslContext)
            .addMethod(getContextArgument)
            .addMethod(getValidator)
            .addType(defaultValidatorHolder)
            .addType(impl)
            .build();

        return List.of(contextSpec);
    }
}
