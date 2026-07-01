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
 * malformed or wrong-type {@code @nodeId} filter value (R378) throws it carrying a human-readable
 * message. Two roles, both load-bearing:
 *
 * <ul>
 *   <li><b>Surfacing marker.</b> The no-channel fetcher catch arm routes through
 *       {@code ErrorRouter.surfaceClientErrorOrRedact}, which walks the cause chain and surfaces an
 *       instance of this type as a real {@code GraphQLError} (its message reaches the client)
 *       instead of redacting it to a correlation id. Genuine internal faults, which are not
 *       instances of this type, still redact.</li>
 *   <li><b>Stable {@code @error} anchor.</b> When bare-entity query fields gain a payload object
 *       that can host {@code @error} handlers (R397), a {@code GENERIC} handler matching this
 *       class routes the same throw through the channel with zero change at the throw site
 *       ({@code ExceptionMapping.match} is {@code instanceof}). Future client-error producers
 *       subtype it.</li>
 * </ul>
 *
 * <p>Subclasses {@link graphql.GraphqlErrorException} so it <em>is</em> a {@link graphql.GraphQLError}
 * (channel-matchable, natively serialisable into the response {@code errors} array) and carries a
 * message through the library's builder. Generated as a source file rather than shipped in a
 * runtime jar, preserving the rewrite's no-runtime-jar invariant (the same reason {@code ErrorRouter}
 * is generated).
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
