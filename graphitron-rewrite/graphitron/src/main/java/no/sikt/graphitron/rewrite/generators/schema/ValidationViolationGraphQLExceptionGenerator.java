package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code ValidationViolationGraphQLException} class emitted at
 * {@code <outputPackage>.schema.ValidationViolationGraphQLException}, once per
 * code-generation run.
 *
 * <p>An {@code AbortExecutionException} subclass that carries a list of
 * {@code GraphQLError}s; thrown by developer-supplied validation code (typically a
 * {@code RecordValidator.validate(...)} call) and routed by the C3 {@code ErrorRouter}
 * into typed {@code @error} instances when the channel declares
 * {@code {handler: VALIDATION}}.
 *
 * <p>Emitted as generated output rather than depending on
 * {@code no.sikt.graphql.exception.ValidationViolationGraphQLException} in
 * {@code graphitron-common}, preserving the rewrite's standalone-build invariant.
 *
 * <p>Spec: {@code error-handling-parity.md} §5, "ValidationViolationGraphQLException
 * fan-out (the VALIDATION handler runtime)".
 */
public final class ValidationViolationGraphQLExceptionGenerator {

    public static final String CLASS_NAME = "ValidationViolationGraphQLException";

    private ValidationViolationGraphQLExceptionGenerator() {}

    public static List<TypeSpec> generate() {
        var graphQLError = ClassName.get("graphql", "GraphQLError");
        var abortExecutionException = ClassName.get("graphql.execution", "AbortExecutionException");
        var collectionOfErrors = ParameterizedTypeName.get(
            ClassName.get("java.util", "Collection"), graphQLError);

        var ctor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(collectionOfErrors, "validationErrors")
            .addStatement("super(validationErrors)")
            .addJavadoc("Wraps the carried {@link $T}s and aborts execution; the\n"
                + "{@code ErrorRouter} fans them out into typed instances of the channel's\n"
                + "{@code VALIDATION}-marked {@code @error} type at the fetcher boundary.\n",
                graphQLError)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .superclass(abortExecutionException)
            .addJavadoc("Aborts execution carrying a list of validation violations as\n"
                + "{@link $T}s. Constructed by developer-supplied validation code (typically\n"
                + "from a {@code RecordValidator.validate(...)} call) and caught by the\n"
                + "schema-emitted {@code ErrorRouter} at the fetcher boundary; if the channel\n"
                + "declares a {@code {handler: VALIDATION}} entry, each carried\n"
                + "{@code GraphQLError} fans out into one typed {@code @error} instance via\n"
                + "the per-fetcher payload-factory contract.\n",
                graphQLError)
            .addMethod(ctor)
            .build();

        return List.of(spec);
    }
}
