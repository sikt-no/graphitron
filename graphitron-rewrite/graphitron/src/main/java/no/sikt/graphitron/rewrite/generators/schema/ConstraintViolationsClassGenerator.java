package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code ConstraintViolations} helper class emitted at
 * {@code <outputPackage>.schema.ConstraintViolations}, once per code-generation run.
 *
 * <p>Translates a single {@link jakarta.validation.ConstraintViolation} into a
 * {@link graphql.GraphQLError} for the wrapper's pre-execution validator step (R12 §5).
 * The translation walks the violation's bean property path and concatenates it with the
 * field's response path and the bound input argument name to produce a GraphQL error
 * whose {@code path} segments alternate field names, list indices, and argument keys
 * exactly as the schema author would expect.
 *
 * <ul>
 *   <li>{@code getMessage()} → {@code message}. Jakarta's {@code MessageInterpolator} has
 *       already resolved the localised string by the time the violation surfaces.</li>
 *   <li>Property path → response path: {@code env.getExecutionStepInfo().getPath().toList()}
 *       prefix, then the SDL argument key, then each {@code Path.Node} in declaration order
 *       ({@code getName()} for {@code PROPERTY} / {@code BEAN}, {@code getIndex()} for
 *       {@code CONTAINER_ELEMENT}, {@code getKey()} for map entries).</li>
 * </ul>
 *
 * <p>Spec: {@code error-handling-parity.md} §5, "{@code ConstraintViolation} →
 * {@code GraphQLError} translation".
 */
public final class ConstraintViolationsClassGenerator {

    public static final String CLASS_NAME = "ConstraintViolations";

    private static final ClassName CONSTRAINT_VIOLATION = ClassName.get("jakarta.validation", "ConstraintViolation");
    private static final ClassName PATH                 = ClassName.get("jakarta.validation", "Path");
    private static final ClassName ELEMENT_KIND         = ClassName.get("jakarta.validation", "ElementKind");
    private static final ClassName ENV                  = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName GRAPHQL_ERROR        = ClassName.get("graphql", "GraphQLError");
    private static final ClassName GRAPHQL_ERROR_BUILDER= ClassName.get("graphql", "GraphqlErrorBuilder");
    private static final ClassName ARRAY_LIST           = ClassName.get("java.util", "ArrayList");

    private ConstraintViolationsClassGenerator() {}

    public static List<TypeSpec> generate() {
        var violationWildcard = ParameterizedTypeName.get(
            CONSTRAINT_VIOLATION, WildcardTypeName.subtypeOf(Object.class));
        var listOfObject = ParameterizedTypeName.get(
            ClassName.get(List.class), ClassName.get(Object.class));

        var toGraphQLError = MethodSpec.methodBuilder("toGraphQLError")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(GRAPHQL_ERROR)
            .addParameter(violationWildcard, "violation")
            .addParameter(ENV, "env")
            .addParameter(String.class, "argName")
            .addJavadoc("Translates a single {@link $T} into a {@link $T} carrying the\n"
                + "violation's localised message and a response-path prefix that splices the\n"
                + "field's execution path, the bound argument name, and the violation's bean\n"
                + "property path. The wrapper emits this call once per violation produced by\n"
                + "the pre-execution validator step (R12 §5).\n", CONSTRAINT_VIOLATION, GRAPHQL_ERROR)
            .addStatement("$T path = new $T<>(env.getExecutionStepInfo().getPath().toList())",
                listOfObject, ARRAY_LIST)
            .addStatement("path.add(argName)")
            .beginControlFlow("for ($T node : violation.getPropertyPath())", PATH.nestedClass("Node"))
            .addStatement("$T kind = node.getKind()", ELEMENT_KIND)
            // Distinct arms per node kind: PROPERTY/BEAN write the name, CONTAINER_ELEMENT
            // writes the int index (list element) or string key (map entry); other kinds are
            // skipped so the path stays clean.
            .beginControlFlow("if (kind == $T.CONTAINER_ELEMENT)", ELEMENT_KIND)
            .beginControlFlow("if (node.getIndex() != null)")
            .addStatement("path.add(node.getIndex())")
            .nextControlFlow("else if (node.getKey() != null)")
            .addStatement("path.add(node.getKey())")
            .endControlFlow()
            .nextControlFlow("else if (node.getName() != null)")
            .addStatement("path.add(node.getName())")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return $T.newError(env)\n"
                + "        .message(violation.getMessage())\n"
                + "        .path(path)\n"
                + "        .build()", GRAPHQL_ERROR_BUILDER)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Translation helper for the wrapper's pre-execution Jakarta validation step.\n"
                + "Each {@link $T} produced by {@code Validator.validate(input)} fans into one\n"
                + "{@link $T} placed directly into the payload's errors slot; graphql-java's\n"
                + "{@code TypeResolver} routes each entry to the channel's VALIDATION-marked\n"
                + "{@code @error} SDL type at serialisation time. See R12 §5.\n",
                CONSTRAINT_VIOLATION, GRAPHQL_ERROR)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(toGraphQLError)
            .build();

        return List.of(spec);
    }
}
