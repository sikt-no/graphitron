package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code GraphitronError} marker interface emitted at
 * {@code <outputPackage>.schema.GraphitronError}, once per code-generation run.
 *
 * <p>Every developer-supplied {@code @error} Java class implements this marker. Two reasons
 * the marker pulls its weight:
 *
 * <ul>
 *   <li>Typed lists at the channel boundary: the router builds a
 *       {@code List<? extends GraphitronError>} and hands it to the developer's payload
 *       constructor; without the marker that list would be {@code List<Object>} (a union
 *       channel mixes concrete types).</li>
 *   <li>The {@code Mapping.build} return type collapses to {@code GraphitronError} instead
 *       of {@code Object}; the typed-error router signature stays statically-typed
 *       end-to-end with no widening past the {@code errors} list's element type.</li>
 * </ul>
 *
 * <p>Emitted as generated output rather than depending on a runtime jar; preserves the
 * rewrite's standalone-build invariant (see {@code rewrite-design-principles.md}).
 *
 * <p>Spec: {@code error-handling-parity.md} §1, "Marker interface: GraphitronError".
 */
public final class GraphitronErrorInterfaceGenerator {

    public static final String CLASS_NAME = "GraphitronError";

    private GraphitronErrorInterfaceGenerator() {}

    public static List<TypeSpec> generate() {
        var listOfString = ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(String.class));

        var path = MethodSpec.methodBuilder("path")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(listOfString)
            .addJavadoc("The GraphQL response path the violation applies to,\n"
                + "as returned by {@code DataFetchingEnvironment.getExecutionStepInfo().getPath().toList()}.\n")
            .build();

        var message = MethodSpec.methodBuilder("message")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(String.class)
            .addJavadoc("A user-facing message; for matched handlers, the {@code description} from the\n"
                + "{@code @error} declaration when set, otherwise the matched exception's\n"
                + "{@code getMessage()}. Validation violations carry the constraint message as-is.\n")
            .build();

        var spec = TypeSpec.interfaceBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Marker interface implemented by every developer-supplied {@code @error}\n"
                + "Java class. The schema-emitted {@code ErrorRouter} returns instances of this\n"
                + "type from its {@code Mapping.build(path, message)} call.\n"
                + "\n"
                + "<p>Required signature on every {@code @error} class:\n"
                + "{@code public SomeError(java.util.List<String> path, String message)}.\n"
                + "Schema validation rejects an {@code @error} type whose backing class\n"
                + "is missing this constructor or this implements clause.\n")
            .addMethod(path)
            .addMethod(message)
            .build();

        return List.of(spec);
    }
}
