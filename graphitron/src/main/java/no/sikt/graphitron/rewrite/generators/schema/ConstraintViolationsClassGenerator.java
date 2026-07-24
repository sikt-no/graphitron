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
 * <p>The generated method translates one {@code jakarta.validation.ConstraintViolation} into a
 * {@link graphql.GraphQLError} for the wrapper's pre-execution validator step: the violation's
 * message (already interpolated by Jakarta's {@code MessageInterpolator}), a {@code path} that
 * splices the field's execution path, the bound argument name, and the bean property path, and
 * an {@code extensions} map carrying the violated constraint annotation's simple name under
 * {@code "constraint"}. Schema authors opt into client visibility of the constraint name by
 * declaring an {@code extensions}-shaped field on their VALIDATION-handled {@code @error} type;
 * the per-handler source-class accessor check
 * ({@link no.sikt.graphitron.rewrite.walker.internal.HandlerAccessorCheck}) confirms at
 * classify time that {@code GraphQLError.getExtensions()} satisfies the read.
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
    private static final ClassName LINKED_HASH_MAP      = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName MAP                  = ClassName.get("java.util", "Map");

    private ConstraintViolationsClassGenerator() {}

    public static List<TypeSpec> generate() {
        var violationWildcard = ParameterizedTypeName.get(
            CONSTRAINT_VIOLATION, WildcardTypeName.subtypeOf(Object.class));
        var listOfObject = ParameterizedTypeName.get(
            ClassName.get(List.class), ClassName.get(Object.class));
        var mapOfStringObject = ParameterizedTypeName.get(
            MAP, ClassName.get(String.class), ClassName.get(Object.class));

        var toGraphQLError = MethodSpec.methodBuilder("toGraphQLError")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(GRAPHQL_ERROR)
            .addParameter(violationWildcard, "violation")
            .addParameter(ENV, "env")
            .addParameter(String.class, "argName")
            .addJavadoc("Translates a single {@link $T} into a {@link $T} carrying the\n"
                + "violation's localised message, a response-path prefix that splices the\n"
                + "field's execution path, the bound argument name, and the violation's bean\n"
                + "property path, and an {@code extensions} map keyed by {@code \"constraint\"}\n"
                + "with the violated annotation's simple name (e.g. {@code \"NotBlank\"}). The\n"
                + "wrapper emits this call once per violation produced by the pre-execution\n"
                + "validator step.\n", CONSTRAINT_VIOLATION, GRAPHQL_ERROR)
            .addStatement("$T path = new $T<>(env.getExecutionStepInfo().getPath().toList())",
                listOfObject, ARRAY_LIST)
            .addStatement("path.add(argName)")
            .beginControlFlow("for ($T node : violation.getPropertyPath())", PATH.nestedClass("Node"))
            .addStatement("$T kind = node.getKind()", ELEMENT_KIND)
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
            // Allocated unconditionally; schemas that don't declare an extensions-shaped field
            // on their @error type never expose it (graphql-java's PropertyDataFetcher only
            // reads SDL-declared fields).
            .addStatement("$T extensions = new $T<>()", mapOfStringObject, LINKED_HASH_MAP)
            .addStatement("extensions.put(\"constraint\", violation.getConstraintDescriptor()"
                + ".getAnnotation().annotationType().getSimpleName())")
            .addStatement("return $T.newError(env)\n"
                + "        .message(violation.getMessage())\n"
                + "        .path(path)\n"
                + "        .extensions(extensions)\n"
                + "        .build()", GRAPHQL_ERROR_BUILDER)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Translation helper for the wrapper's pre-execution Jakarta validation step.\n"
                + "Each {@link $T} produced by {@code Validator.validate(input)} fans into one\n"
                + "{@link $T} placed directly into the payload's errors slot; graphql-java's\n"
                + "{@code TypeResolver} routes each entry to the channel's VALIDATION-marked\n"
                + "{@code @error} SDL type at serialisation time.\n",
                CONSTRAINT_VIOLATION, GRAPHQL_ERROR)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(toGraphQLError)
            .build();

        return List.of(spec);
    }
}
