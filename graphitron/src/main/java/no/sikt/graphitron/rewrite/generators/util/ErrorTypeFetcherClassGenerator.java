package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates the per-{@code @error}-type {@code <ErrorType>Fetchers} class, carrying the
 * {@code path} and {@code message} reads as named {@code public static} methods. The schema's
 * {@code registerFetchers} wiring references {@code <ErrorType>Fetchers::path} /
 * {@code ::message} in place of the inline lambdas previously emitted by
 * {@code GraphitronSchemaClassGenerator}.
 *
 * <p>An {@code @error} type declares the required {@code path: [String!]!} / {@code message: String!}
 * and may declare extra fields; only {@code path} and {@code message} are reified here. The source
 * object can be a {@code Throwable} (no {@code getPath()}) or a {@code GraphQLError}
 * (has {@code getPath()} / {@code getMessage()}). {@code path} synthesises from the GraphQL
 * execution context for non-{@code GraphQLError} sources so the non-null contract holds regardless
 * of handler kind; {@code message} routes universally through {@code getMessage()}. Extra fields
 * are read at runtime by graphql-java's {@code PropertyDataFetcher} (registered by
 * {@code GraphitronSchemaClassGenerator}, remapped when the field carries {@code @field(name:)}),
 * not through this class.
 */
public final class ErrorTypeFetcherClassGenerator {

    private static final ClassName ENV           = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphQLError");
    private static final ClassName THROWABLE     = ClassName.get(Throwable.class);
    private static final ClassName STRING_CN     = ClassName.get(String.class);

    private ErrorTypeFetcherClassGenerator() {}

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        var out = new ArrayList<TypeSpec>();
        schema.types().values().stream()
            .filter(t -> t instanceof GraphitronType.ErrorType)
            .map(t -> (GraphitronType.ErrorType) t)
            .sorted(Comparator.comparing(GraphitronType.ErrorType::name))
            .forEach(et -> out.add(TypeSpec.classBuilder(et.name() + "Fetchers")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(pathMethod())
                .addMethod(messageMethod())
                .build()));
        return out;
    }

    private static MethodSpec pathMethod() {
        return MethodSpec.methodBuilder("path")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("Object src = env.getSource()")
            .beginControlFlow("if (src instanceof $T ge)", GRAPHQL_ERROR)
            .addStatement("return ge.getPath() == null ? java.util.List.of() : "
                + "ge.getPath().stream().map($T::valueOf).toList()", STRING_CN)
            .endControlFlow()
            .addStatement("return env.getExecutionStepInfo().getPath().toList().stream()"
                + ".map($T::valueOf).toList()", STRING_CN)
            .build();
    }

    private static MethodSpec messageMethod() {
        return MethodSpec.methodBuilder("message")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(ENV, "env")
            .addStatement("Object src = env.getSource()")
            .beginControlFlow("if (src instanceof $T ge)", GRAPHQL_ERROR)
            .addStatement("return ge.getMessage()")
            .endControlFlow()
            .beginControlFlow("if (src instanceof $T thr)", THROWABLE)
            .addStatement("return thr.getMessage()")
            .endControlFlow()
            .addStatement("return null")
            .build();
    }
}
