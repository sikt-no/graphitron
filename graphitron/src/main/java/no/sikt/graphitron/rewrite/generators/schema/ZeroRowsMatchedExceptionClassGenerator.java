package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * Generates the {@code ZeroRowsMatchedException} class emitted at
 * {@code <outputPackage>.schema.ZeroRowsMatchedException}, once per code-generation run.
 *
 * <p>The payload-returning bulk UPDATE throws it after running every input row's statement, when
 * one or more rows' keys matched nothing; the transaction rolls back. It carries the field
 * coordinate, the input row count and the zero-based positions that missed, so a consumer's
 * {@code FetcherErrorResponse.unmatchedMessage} or an {@code @error} handler can branch on the
 * counts without parsing the message. Indices and counts carry no input content.
 *
 * <p>Subclasses {@link IllegalStateException} and keeps "matched zero rows" in its message, so a
 * {@code GENERIC} handler that matched the earlier untyped guard by class and substring keeps
 * matching. It is deliberately not a {@link GraphitronClientExceptionClassGenerator client
 * exception}: a key that matches no row may be a row the caller may not see, so an unmatched miss
 * stays redacted. The indices are held as an {@code int[]}, so the exception stays serializable.
 * The constructor builds the message, so its shape is owned here rather than at each
 * throw site; the verb renders into the message only, which keeps the class verb-neutral.
 */
public final class ZeroRowsMatchedExceptionClassGenerator {

    public static final String CLASS_NAME = "ZeroRowsMatchedException";

    private static final TypeName INDEX_LIST = ParameterizedTypeName.get(List.class, Integer.class);

    private ZeroRowsMatchedExceptionClassGenerator() {}

    public static List<TypeSpec> generate() {
        var serialVersionUID = FieldSpec.builder(long.class, "serialVersionUID",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("1L")
            .build();

        var ctor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(String.class, "verb")
            .addParameter(String.class, "fieldCoordinate")
            .addParameter(INDEX_LIST, "missedRowIndices")
            .addParameter(int.class, "rowCount")
            .addStatement("super($S + verb + $S + missedRowIndices.size() + $S + rowCount + $S + fieldCoordinate"
                    + " + $S + missedRowIndices.get(0))",
                "@mutation(typeName: ", ") bulk input matched zero rows for ", " of ", " input rows of ",
                "; first at index ")
            .addStatement("this.fieldCoordinate = fieldCoordinate")
            .addStatement("this.missedRowIndices = missedRowIndices.stream().mapToInt($T::intValue).toArray()",
                Integer.class)
            .addStatement("this.rowCount = rowCount")
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(IllegalStateException.class)
            .addJavadoc("Thrown by a list-input mutation when one or more input rows' keys matched no row.\n"
                + "Every row's statement ran first and the transaction rolled back, so\n"
                + "{@link #missedRowIndices()} lists every miss. The message names the field, the counts\n"
                + "and the first missed index, never input content.\n")
            .addField(serialVersionUID)
            .addField(String.class, "fieldCoordinate", Modifier.PRIVATE, Modifier.FINAL)
            .addField(int[].class, "missedRowIndices", Modifier.PRIVATE, Modifier.FINAL)
            .addField(int.class, "rowCount", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(ctor)
            .addMethod(accessor("fieldCoordinate", TypeName.get(String.class), CodeBlock.of("fieldCoordinate"),
                "The field that threw, as {@code Type.field}.\n"))
            .addMethod(accessor("rowCount", TypeName.INT, CodeBlock.of("rowCount"), "The number of input rows.\n"))
            .addMethod(accessor("missedRowIndices", INDEX_LIST,
                CodeBlock.of("$T.stream(missedRowIndices).boxed().toList()", Arrays.class),
                "The zero-based input positions whose key matched no row, ascending; unmodifiable.\n"))
            .addMethod(accessor("missCount", TypeName.INT, CodeBlock.of("missedRowIndices.length"),
                "The number of input rows whose key matched no row.\n"))
            .build();

        return List.of(spec);
    }

    private static MethodSpec accessor(String name, TypeName type, CodeBlock expression, String javadoc) {
        return MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC)
            .returns(type)
            .addJavadoc(javadoc)
            .addStatement("return $L", expression)
            .build();
    }
}
