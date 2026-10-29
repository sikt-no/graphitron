package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ValueShape;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code ConstraintViolations} helper class emitted at
 * {@code <outputPackage>.schema.ConstraintViolations}, once per code-generation run.
 *
 * <p>The generated method translates one {@code jakarta.validation.ConstraintViolation} into a
 * {@link graphql.GraphQLError} for the wrapper's pre-execution validator step: the violation's
 * message (already interpolated by Jakarta's {@code MessageInterpolator}), a {@code path} that
 * splices the field's execution path, the argument path (the argument name, then the element index
 * when the argument is a list validated element by element), and the property path translated from
 * the Java members of the class the service binds to the SDL input fields they were bound from
 * (a {@code Paths} tree, built per bound class by {@link #pathsInitializer}), and
 * an {@code extensions} map carrying the violated constraint annotation's simple name under
 * {@code "constraint"}. Schema authors opt into client visibility of the constraint name by
 * declaring an {@code extensions}-shaped field on their VALIDATION-handled {@code @error} type;
 * the per-handler source-class accessor check
 * ({@link no.sikt.graphitron.rewrite.walker.internal.HandlerAccessorCheck}) confirms at
 * classify time that {@code GraphQLError.getExtensions()} satisfies the read.
 *
 * <p>A second public method, {@code sizeExceeded}, builds the same shape for the column-length
 * check a DML mutation runs before its statement. Both delegate to one private builder, so there
 * is one copy of the wire shape of a validation error, classification
 * {@link graphql.ErrorType#ValidationError} included.
 */
public final class ConstraintViolationsClassGenerator {

    public static final String CLASS_NAME = "ConstraintViolations";

    /** The nested translation type, {@code ConstraintViolations.Paths}. */
    public static final String PATHS_CLASS = "Paths";

    private static final ClassName CONSTRAINT_VIOLATION = ClassName.get("jakarta.validation", "ConstraintViolation");
    private static final ClassName PATH                 = ClassName.get("jakarta.validation", "Path");
    private static final ClassName ELEMENT_KIND         = ClassName.get("jakarta.validation", "ElementKind");
    private static final ClassName ENV                  = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName GRAPHQL_ERROR        = ClassName.get("graphql", "GraphQLError");
    private static final ClassName GRAPHQL_ERROR_BUILDER= ClassName.get("graphql", "GraphqlErrorBuilder");
    private static final ClassName ERROR_TYPE           = ClassName.get("graphql", "ErrorType");
    private static final ClassName ARRAY_LIST           = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP      = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName MAP                  = ClassName.get("java.util", "Map");
    private static final ClassName HASH_MAP             = ClassName.get("java.util", "HashMap");

    private ConstraintViolationsClassGenerator() {}

    public static List<TypeSpec> generate(String outputPackage) {
        var violationWildcard = ParameterizedTypeName.get(
            CONSTRAINT_VIOLATION, WildcardTypeName.subtypeOf(Object.class));
        var listOfObject = ParameterizedTypeName.get(
            ClassName.get(List.class), ClassName.get(Object.class));
        var mapOfStringObject = ParameterizedTypeName.get(
            MAP, ClassName.get(String.class), ClassName.get(Object.class));

        var paths = pathsClass(outputPackage);
        var listOfString = ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(String.class));
        var pathsType = pathsType(paths, listOfString);

        var toGraphQLError = MethodSpec.methodBuilder("toGraphQLError")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(GRAPHQL_ERROR)
            .addParameter(violationWildcard, "violation")
            .addParameter(ENV, "env")
            .addParameter(listOfObject, "argPath")
            .addParameter(paths, "paths")
            .addJavadoc("Translates a single {@link $T} into a {@link $T} carrying the\n"
                + "violation's localised message, a response-path prefix that splices the\n"
                + "field's execution path, {@code argPath}, and the violation's property path, and an\n"
                + "{@code extensions} map keyed by {@code \"constraint\"} with the violated\n"
                + "annotation's simple name (e.g. {@code \"NotBlank\"}). {@code argPath} is the argument\n"
                + "name, followed by the element index when a list argument is validated element by\n"
                + "element.\n"
                + "\n"
                + "<p>{@code paths} names, for each Java member of the validated class, the SDL input\n"
                + "fields it was bound from, so the path names what the client sent. A member\n"
                + "{@code paths} does not know keeps its Java name, as does everything below it, and\n"
                + "{@code null} keeps every name. An element index or map key is read from whichever\n"
                + "node carries it. A class-level constraint adds no name.\n", CONSTRAINT_VIOLATION, GRAPHQL_ERROR)
            .addStatement("$T path = new $T<>(argPath)", listOfObject, ARRAY_LIST)
            .addStatement("$T current = paths", paths)
            .beginControlFlow("for ($T node : violation.getPropertyPath())", PATH.nestedClass("Node"))
            .beginControlFlow("if (node.isInIterable())")
            .beginControlFlow("if (node.getIndex() != null)")
            .addStatement("path.add(node.getIndex())")
            .nextControlFlow("else if (node.getKey() != null)")
            .addStatement("path.add(node.getKey())")
            .endControlFlow()
            .endControlFlow()
            .beginControlFlow("if (node.getKind() == $T.CONTAINER_ELEMENT || node.getName() == null)", ELEMENT_KIND)
            .addStatement("continue")
            .endControlFlow()
            .addStatement("$T sdl = current == null ? null : current.names.get(node.getName())", listOfString)
            .beginControlFlow("if (sdl == null)")
            .addStatement("path.add(node.getName())")
            .addStatement("current = null")
            .nextControlFlow("else")
            .addStatement("path.addAll(sdl)")
            .addStatement("current = current.children.get(node.getName())")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return validationError(env, path, violation.getMessage(), violation.getConstraintDescriptor()"
                + ".getAnnotation().annotationType().getSimpleName())")
            .build();

        var sizeExceeded = MethodSpec.methodBuilder("sizeExceeded")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(GRAPHQL_ERROR)
            .addParameter(ENV, "env")
            .addParameter(listOfObject, "argPath")
            .addParameter(int.class, "max")
            .addJavadoc("A {@link $T} for a written string longer than its column's declared length\n"
                + "{@code max}, raised by a DML mutation's pre-execution length\n"
                + "check. {@code argPath} is the argument name followed by the input path to the\n"
                + "offending field (list indices included). The wire shape is\n"
                + "{@link #toGraphQLError}'s, with {@code \"Size\"} as the constraint name.\n",
                GRAPHQL_ERROR)
            .addStatement("return validationError(env, argPath, $S + max, $S)",
                "size must be between 0 and ", "Size")
            .build();

        // The one copy of a validation error's wire shape, so a length error cannot drift from a
        // Jakarta one: the execution-path prefix, the extensions map, the ValidationError
        // classification and the builder call.
        var validationError = MethodSpec.methodBuilder("validationError")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(GRAPHQL_ERROR)
            .addParameter(ENV, "env")
            .addParameter(listOfObject, "argPath")
            .addParameter(String.class, "message")
            .addParameter(String.class, "constraint")
            .addStatement("$T path = new $T<>(env.getExecutionStepInfo().getPath().toList())",
                listOfObject, ARRAY_LIST)
            .addStatement("path.addAll(argPath)")
            // Allocated unconditionally; schemas that don't declare an extensions-shaped field
            // on their @error type never expose it (graphql-java's PropertyDataFetcher only
            // reads SDL-declared fields).
            .addStatement("$T extensions = new $T<>()", mapOfStringObject, LINKED_HASH_MAP)
            .addStatement("extensions.put(\"constraint\", constraint)")
            .addStatement("return $T.newError(env)\n"
                + "        .message(message)\n"
                + "        .path(path)\n"
                + "        .errorType($T.ValidationError)\n"
                + "        .extensions(extensions)\n"
                + "        .build()", GRAPHQL_ERROR_BUILDER, ERROR_TYPE)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Translation helper for the wrapper's pre-execution Jakarta validation step.\n"
                + "Each {@link $T} produced by {@code Validator.validate(input)} fans into one\n"
                + "{@link $T} placed directly into the payload's errors slot; graphql-java's\n"
                + "{@code TypeResolver} routes each entry to the channel's VALIDATION-marked\n"
                + "{@code @error} SDL type at serialisation time. A DML mutation without an errors\n"
                + "field returns the same errors from its column-length check as top-level field\n"
                + "errors.\n",
                CONSTRAINT_VIOLATION, GRAPHQL_ERROR)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(toGraphQLError)
            .addMethod(sizeExceeded)
            .addMethod(validationError)
            .addType(pathsType)
            .build();

        return List.of(spec);
    }

    /** {@code <outputPackage>.schema.ConstraintViolations.Paths}. */
    public static ClassName pathsClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", CLASS_NAME).nestedClass(PATHS_CLASS);
    }

    /**
     * {@code ConstraintViolations.Paths}: for each Java member of a validated class, the SDL input
     * fields it was bound from, and the translation for the member's own class where it binds one.
     * Built once per bound class, at class initialisation, through {@code bind}.
     */
    private static TypeSpec pathsType(ClassName paths, ParameterizedTypeName listOfString) {
        var names = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfString);
        var children = ParameterizedTypeName.get(MAP, ClassName.get(String.class), paths);
        return TypeSpec.classBuilder(PATHS_CLASS)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("How a validated class's Java members name the SDL input fields they were bound\n"
                + "from. Graphitron builds one per class a {@code @service} method takes, once.\n")
            .addField(FieldSpec.builder(names, "names", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", HASH_MAP).build())
            .addField(FieldSpec.builder(children, "children", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", HASH_MAP).build())
            .addMethod(MethodSpec.methodBuilder("bind")
                .addModifiers(Modifier.PUBLIC)
                .returns(paths)
                .addParameter(String.class, "member")
                .addParameter(listOfString, "sdlFields")
                .addParameter(paths, "child")
                .addJavadoc("Names {@code member} by {@code sdlFields}; {@code child} translates below it,\n"
                    + "or is {@code null} where the member binds no class.\n")
                .addStatement("names.put(member, sdlFields)")
                .beginControlFlow("if (child != null)")
                .addStatement("children.put(member, child)")
                .endControlFlow()
                .addStatement("return this")
                .build())
            .build();
    }

    /**
     * The expression that builds a bound class's {@code ConstraintViolations.Paths}: a projection of
     * its {@link ValueShape.FieldBinding} tree. Each binding names its Java member
     * ({@link ValueShape.FieldBinding#javaFieldName()}) by its whole
     * {@link ValueShape.FieldBinding#accessPath()}, more than one segment where the field flattened
     * out of a grouping input, and descends into a member that binds a class itself, directly or as
     * list elements.
     */
    public static CodeBlock pathsInitializer(ValueShape.BoundClass bound, String outputPackage) {
        var b = CodeBlock.builder().add("new $T()", pathsClass(outputPackage));
        for (var field : bound.fields()) {
            var sdl = CodeBlock.builder().add("$T.of(", List.class);
            for (int i = 0; i < field.accessPath().size(); i++) {
                sdl.add(i == 0 ? "$S" : ", $S", field.accessPath().get(i));
            }
            sdl.add(")");
            var child = switch (field.shape()) {
                case ValueShape.BoundClass nested -> pathsInitializer(nested, outputPackage);
                case ValueShape.ListOf list when list.elementShape() instanceof ValueShape.BoundClass nested ->
                    pathsInitializer(nested, outputPackage);
                default -> CodeBlock.of("null");
            };
            b.add(".bind($S, $L, $L)", field.javaFieldName(), sdl.build(), child);
        }
        return b.build();
    }
}
