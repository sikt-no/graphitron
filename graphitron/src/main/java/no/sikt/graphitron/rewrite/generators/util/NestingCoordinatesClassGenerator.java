package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.NestingReach;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code NestingCoordinates} utility class, emitted once per code-generation run
 * with the schema's nesting coordinates baked in.
 *
 * <p>A field declared on a nesting type is written into an ancestor's SQL row by its spliced
 * projection unit, under an alias qualified by the result keys from the row's owner down to its
 * nesting occurrence ({@link no.sikt.graphitron.rewrite.model.AliasScope.Spliced}). The write side
 * threads that path through the nested units' {@code $project} calls; the read side cannot know it
 * statically, because one fetcher is registered per nesting type while the type can sit at
 * different depths under different anchors. {@code rkPath(env)} composes it at runtime instead:
 * starting from the reading field's parent step, it walks the execution step chain upward, and for
 * every step whose field coordinate ({@code <object type>.<field>}) is a nesting coordinate
 * prepends that step's result key and the path delimiter; it stops at the first step that is not.
 * The rule is keyed on the field coordinate, not the declaring type, because nesting is a
 * field-level classification: the first nesting step's object type is the anchor itself.
 *
 * <p>The walk's preconditions are validator facts: a nesting field is never list-wrapped (the
 * list-wrapper rejection on nesting fields in
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator}), so no list-element step sits
 * mid-chain; nesting under a record-carrier source is rejected
 * ({@link no.sikt.graphitron.rewrite.model.ReachableSourceShape#REJECTED}), and in the
 * {@link no.sikt.graphitron.rewrite.model.ReachableSourceShape#DISPATCHED} mix the class-backed
 * arm never takes the {@code Record} read. Federation {@code _entities},
 * {@code Query.node(s)}, interface parents and the connection {@code node} / {@code nodes} steps
 * stop the walk on their own, since none of them is a nesting coordinate.
 *
 * <p>The coordinate set is per schema, which is why this is not a static of the schema-invariant
 * {@code SelectionOccurrences}; the method's signature is the same for every schema. The set comes
 * from {@link NestingReach#nestingCoordinates()}.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public final class NestingCoordinatesClassGenerator {

    public static final String CLASS_NAME = "NestingCoordinates";

    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName STEP = ClassName.get("graphql.execution", "ExecutionStepInfo");
    private static final ClassName SET = ClassName.get("java.util", "Set");

    private NestingCoordinatesClassGenerator() {}

    public static List<TypeSpec> generate(NestingReach reach) {
        var setOfString = ParameterizedTypeName.get(SET, ClassName.get(String.class));
        var literals = reach.nestingCoordinates().stream()
            .map(coordinate -> CodeBlock.of("$S", coordinate))
            .collect(CodeBlock.joining(",\n"));
        var coordinates = FieldSpec.builder(setOfString, "COORDINATES",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Every nesting edge, as {@code <parent type>.<field>}.\n")
            .initializer(reach.nestingCoordinates().isEmpty()
                ? CodeBlock.of("$T.of()", SET)
                : CodeBlock.builder().add("$T.of(\n", SET).indent().add(literals).unindent().add(")").build())
            .build();

        var rkPath = MethodSpec.methodBuilder("rkPath")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(ENV, "env")
            .addJavadoc("The result-key alias path of the row the reading field's source record is:\n"
                + "the result key of every enclosing nesting step, outermost first, each followed\n"
                + "by the path delimiter; empty when the parent step is not a nesting field.\n")
            .addStatement("$T path = new $T()", StringBuilder.class, StringBuilder.class)
            .addStatement("$T step = env.getExecutionStepInfo().getParent()", STEP)
            .beginControlFlow("while (step != null && step.getField() != null && step.getObjectType() != null\n"
                + "&& COORDINATES.contains(step.getObjectType().getName() + $S + step.getField().getName()))", ".")
            .addStatement("path.insert(0, step.getField().getResultKey() + $S)",
                ReservedAliases.RESULT_KEY_PATH_DELIMITER)
            .addStatement("step = step.getParent()")
            .endControlFlow()
            .addStatement("return path.toString()")
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addField(coordinates)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(rkPath)
            .build();
        return List.of(spec);
    }
}
