package no.sikt.graphitron.rewrite.generators.splitquery;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates {@link TypeSpec}s for split-source classes from {@link SplitSourceSpec}s.
 *
 * <p>Each spec produces one class (e.g. {@code LanguageFilmsSource}) containing a
 * {@code toSourceRows} method. The method signature is always:
 * <pre>{@code
 * public static List<RecordN<Integer, T1, ...>> toSourceRows(DSLContext ctx, List<Record> sources)
 * }</pre>
 *
 * <p>The first column is always {@code GRAPHITRON_INPUT_IDX} (1-based row position), which lets a
 * JOIN preserve input-to-output ordering. Column values are extracted directly from each source
 * record using jOOQ's typed {@code record.get(Field<T>)} — no cast required.
 *
 * <p>Example output for {@code Language.films} with FK {@code film.language_id → language.language_id}:
 * <pre>{@code
 * public static List<Record2<Integer, Integer>> toSourceRows(DSLContext ctx, List<Record> sources) {
 *     return IntStream.range(0, sources.size())
 *         .mapToObj(i -> ctx.newRecord(GRAPHITRON_INPUT_IDX, LANGUAGE.LANGUAGE_ID)
 *             .values(i + 1, sources.get(i).get(LANGUAGE.LANGUAGE_ID)))
 *         .toList();
 * }
 * }</pre>
 */
public class SplitSourceCodeGenerator {

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName INT_STREAM = ClassName.get("java.util.stream", "IntStream");
    private static final ClassName LIST = ClassName.get(List.class);
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");

    public TypeSpec generate(SplitSourceSpec spec) {
        String className = spec.parentTypeName() + capitalize(spec.fieldName()) + "Source";
        return TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildToSourceRowsMethod(spec))
            .build();
    }

    private MethodSpec buildToSourceRowsMethod(SplitSourceSpec spec) {
        var returnType = recordListType(spec);
        var sourcesType = ParameterizedTypeName.get(LIST, RECORD);

        return MethodSpec.methodBuilder("toSourceRows")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(DSL_CONTEXT, "ctx")
            .addParameter(sourcesType, "sources")
            .addCode(buildToSourceRowsBody(spec))
            .build();
    }

    /**
     * Generates the method body:
     * <pre>{@code
     * return IntStream.range(0, sources.size())
     *     .mapToObj(i -> ctx.newRecord(GRAPHITRON_INPUT_IDX, TABLE.COL1, TABLE.COL2)
     *         .values(i + 1, sources.get(i).get(TABLE.COL1), sources.get(i).get(TABLE.COL2)))
     *     .toList();
     * }</pre>
     */
    private CodeBlock buildToSourceRowsBody(SplitSourceSpec spec) {
        var newRecordCall = newRecordCallBlock(spec);
        var valuesArgs = valuesBlock(spec);

        return CodeBlock.builder()
            .add("return $T.range(0, sources.size())\n", INT_STREAM)
            .indent()
            .add(".mapToObj(i -> $L\n", newRecordCall)
            .indent()
            .add(".values($L))\n", valuesArgs)
            .unindent()
            .add(".toList();\n")
            .unindent()
            .build();
    }

    /** {@code ctx.newRecord(GRAPHITRON_INPUT_IDX, TABLE.COL1, ...)} */
    private CodeBlock newRecordCallBlock(SplitSourceSpec spec) {
        var b = CodeBlock.builder();
        b.add("ctx.newRecord(GRAPHITRON_INPUT_IDX");
        for (var f : spec.keyFields()) {
            b.add(", $L.$L", spec.parentTableJavaFieldName(), f.columnJavaName());
        }
        b.add(")");
        return b.build();
    }

    /** {@code i + 1, sources.get(i).get(TABLE.COL1), sources.get(i).get(TABLE.COL2)} */
    private CodeBlock valuesBlock(SplitSourceSpec spec) {
        var b = CodeBlock.builder();
        b.add("i + 1");
        for (var f : spec.keyFields()) {
            b.add(", sources.get(i).get($L.$L)", spec.parentTableJavaFieldName(), f.columnJavaName());
        }
        return b.build();
    }

    /**
     * Builds the return type {@code List<RecordN<Integer, T1, T2, ...>>} where N is
     * 1 (for GRAPHITRON_INPUT_IDX) plus the number of key fields.
     */
    private TypeName recordListType(SplitSourceSpec spec) {
        int n = 1 + spec.keyFields().size();
        var recordClass = ClassName.get("org.jooq", "Record" + n);
        var typeArgs = new TypeName[n];
        typeArgs[0] = ClassName.get(Integer.class);
        for (int i = 0; i < spec.keyFields().size(); i++) {
            var f = spec.keyFields().get(i);
            int dot = f.columnClass().lastIndexOf('.');
            String pkg = f.columnClass().substring(0, dot);
            String simple = f.columnClass().substring(dot + 1);
            typeArgs[i + 1] = ClassName.get(pkg, simple);
        }
        return ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(recordClass, typeArgs));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
