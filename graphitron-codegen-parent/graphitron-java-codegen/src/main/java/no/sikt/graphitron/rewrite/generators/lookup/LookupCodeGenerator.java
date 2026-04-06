package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;

/**
 * Generates {@link TypeSpec}s for lookup classes from {@link LookupSpec}s.
 *
 * <p>Each spec produces one class (e.g. {@code CustomerLookup}) containing a
 * {@code toInputRows} method that maps a {@code List<Map<String, Object>>} — the graphql-java
 * representation of a list input argument — into a {@code List<RecordN<Integer, T1, ...>>}.
 * The first record column is always {@code GRAPHITRON_INPUT_IDX} (row position, 1-based), which
 * lets the generated JOIN preserve input-to-output ordering.
 */
public class LookupCodeGenerator {

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName INT_STREAM = ClassName.get("java.util.stream", "IntStream");
    private static final ClassName MAP = ClassName.get(Map.class);
    private static final ClassName LIST = ClassName.get(List.class);
    private static final ClassName STRING = ClassName.get(String.class);
    private static final ClassName OBJECT = ClassName.get(Object.class);

    public TypeSpec generate(LookupSpec spec) {
        return TypeSpec.classBuilder(spec.typeName() + "Lookup")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(buildToInputRowsMethod(spec))
            .build();
    }

    /**
     * Generates the {@code toInputRows} method:
     * <pre>{@code
     * public static List<Record3<Integer, Integer, String>> toInputRows(
     *         List<Map<String, Object>> inputs) {
     *     return IntStream.range(0, inputs.size())
     *         .mapToObj(i -> {
     *             var m = inputs.get(i);
     *             return DSL.newRecord(GRAPHITRON_INPUT_IDX, TABLE.COL1, TABLE.COL2)
     *                 .values(i + 1, (Type1) m.get("field1"), (Type2) m.get("field2"));
     *         })
     *         .toList();
     * }
     * }</pre>
     */
    private MethodSpec buildToInputRowsMethod(LookupSpec spec) {
        var returnType = recordListType(spec);
        var paramType = ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(MAP, STRING, OBJECT));

        var body = buildToInputRowsBody(spec);

        return MethodSpec.methodBuilder("toInputRows")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(paramType, "inputs")
            .addCode(body)
            .build();
    }

    private CodeBlock buildToInputRowsBody(LookupSpec spec) {
        var newRecordArgs = CodeBlock.builder();
        newRecordArgs.add("GRAPHITRON_INPUT_IDX");
        for (var f : spec.fields()) {
            newRecordArgs.add(", $L.$L", spec.tableJavaFieldName(), f.columnJavaName());
        }

        var valuesArgs = CodeBlock.builder();
        valuesArgs.add("i + 1");
        for (var f : spec.fields()) {
            int dot = f.columnClass().lastIndexOf('.');
            String pkg = f.columnClass().substring(0, dot);
            String simple = f.columnClass().substring(dot + 1);
            valuesArgs.add(", ($T) m.get($S)", ClassName.get(pkg, simple), f.argName());
        }

        return CodeBlock.builder()
            .add("return $T.range(0, inputs.size())\n", INT_STREAM)
            .indent()
            .add(".mapToObj(i -> {\n")
            .indent()
            .add("var m = inputs.get(i);\n")
            .add("return $T.newRecord($L)\n", DSL, newRecordArgs.build())
            .indent()
            .add(".values($L);\n", valuesArgs.build())
            .unindent()
            .unindent()
            .add("})\n")
            .add(".toList();\n")
            .unindent()
            .build();
    }

    /**
     * Builds the return type {@code List<RecordN<Integer, T1, T2, ...>>} where N is
     * 1 (for GRAPHITRON_INPUT_IDX) plus the number of input fields.
     */
    private TypeName recordListType(LookupSpec spec) {
        int n = 1 + spec.fields().size();
        var recordClass = ClassName.get("org.jooq", "Record" + n);
        var typeArgs = new TypeName[n];
        typeArgs[0] = ClassName.get(Integer.class);
        for (int i = 0; i < spec.fields().size(); i++) {
            var f = spec.fields().get(i);
            int dot = f.columnClass().lastIndexOf('.');
            String pkg = f.columnClass().substring(0, dot);
            String simple = f.columnClass().substring(dot + 1);
            typeArgs[i + 1] = ClassName.get(pkg, simple);
        }
        return ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(recordClass, typeArgs));
    }
}
