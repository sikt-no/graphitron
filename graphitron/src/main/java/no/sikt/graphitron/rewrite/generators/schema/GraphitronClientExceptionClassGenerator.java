package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code GraphitronClientException} class emitted at
 * {@code <outputPackage>.schema.GraphitronClientException}, once per code-generation run.
 *
 * <p>The stably-named marker for a <em>client</em> mistake surfaced from a generated fetcher: a
 * malformed or wrong-type {@code @nodeId} filter value throws it carrying a human-readable
 * message. The no-channel fetcher catch arm routes it through the generated
 * {@code ErrorRouter.surfaceClientErrorOrRedact} (see {@link ErrorRouterClassGenerator}), which
 * surfaces an instance of this type as a real {@code GraphQLError} instead of redacting it to a
 * correlation id; genuine internal faults still redact. The stable name is also the
 * {@code instanceof} anchor a {@code GENERIC} {@code @error} handler matches with zero change at
 * the throw site.
 *
 * <p>Subclasses {@link graphql.GraphqlErrorException} so it <em>is</em> a {@link graphql.GraphQLError}
 * (channel-matchable, natively serialisable into the response {@code errors} array). Generated as
 * a source file rather than shipped in a runtime jar, preserving the rewrite's no-runtime-jar
 * invariant.
 */
public final class GraphitronClientExceptionClassGenerator {

    public static final String CLASS_NAME = "GraphitronClientException";

    private static final ClassName GRAPHQL_ERROR_EXCEPTION = ClassName.get("graphql", "GraphqlErrorException");

    private GraphitronClientExceptionClassGenerator() {}

    public static List<TypeSpec> generate() {
        var serialVersionUID = FieldSpec.builder(long.class, "serialVersionUID",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("1L")
            .build();

        var ctor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(String.class, "message")
            .addStatement("super($T.newErrorException().message(message))", GRAPHQL_ERROR_EXCEPTION)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(GRAPHQL_ERROR_EXCEPTION)
            .addJavadoc("Marker for a client-side input mistake surfaced from a generated fetcher\n"
                + "(e.g. a malformed or wrong-type {@code @nodeId} filter value). Surfaced raw by\n"
                + "{@code ErrorRouter.surfaceClientErrorOrRedact} rather than redacted; the stable\n"
                + "{@code instanceof} anchor a future query {@code @error} handler matches.\n")
            .addField(serialVersionUID)
            .addMethod(ctor)
            .build();

        return List.of(spec);
    }
}
