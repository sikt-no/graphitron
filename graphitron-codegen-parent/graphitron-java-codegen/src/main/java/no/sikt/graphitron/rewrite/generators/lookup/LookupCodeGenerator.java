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
 * {@code toInputRows} method. The method signature is always:
 * <pre>{@code
 * public static List<RecordN<Integer, T1, ...>> toInputRows(DSLContext ctx, Map<String, Object> arguments)
 * }</pre>
 *
 * <p>Receiving the full {@code arguments} map rather than a single extracted list allows the method
 * to pull multiple arguments together — both list arguments (one element per row) and scalar
 * arguments (broadcast to every row). The first column is always {@code GRAPHITRON_INPUT_IDX}
 * (1-based row position), which lets a JOIN preserve input-to-output ordering.
 *
 * <p>Two code paths are generated depending on {@link LookupSpec#inputArgName()}:
 * <ul>
 *   <li>Non-null — single {@code TableInputType} argument: extracts a
 *       {@code List<Map<String,Object>>} from {@code arguments}, iterates over it, and reads
 *       each field from the per-element map.</li>
 *   <li>Null — flat arguments: declares one local {@code List<T>} variable per list argument,
 *       uses the first list's size for the {@code IntStream}, and reads scalar arguments
 *       directly from {@code arguments} per row.</li>
 * </ul>
 */
public class LookupCodeGenerator {

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
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

    private MethodSpec buildToInputRowsMethod(LookupSpec spec) {
        var returnType = recordListType(spec);
        var paramType = ParameterizedTypeName.get(MAP, STRING, OBJECT);

        return MethodSpec.methodBuilder("toInputRows")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(returnType)
            .addParameter(DSL_CONTEXT, "ctx")
            .addParameter(paramType, "arguments")
            .addCode(buildToInputRowsBody(spec))
            .build();
    }

    private CodeBlock buildToInputRowsBody(LookupSpec spec) {
        return spec.inputArgName() != null
            ? buildInputTypeBody(spec)
            : buildFlatArgsBody(spec);
    }

    /**
     * Generates the body for the input-type-arg case:
     * <pre>{@code
     * List<Map<String, Object>> input = (List<Map<String, Object>>) arguments.get("input");
     * return IntStream.range(0, input.size())
     *     .mapToObj(i -> {
     *         var m = input.get(i);
     *         return DSL.newRecord(GRAPHITRON_INPUT_IDX, TABLE.COL1, TABLE.COL2)
     *             .values(i + 1, (Type1) m.get("field1"), (Type2) m.get("field2"));
     *     })
     *     .toList();
     * }</pre>
     */
    private CodeBlock buildInputTypeBody(LookupSpec spec) {
        var inputArgName = spec.inputArgName();
        var listType = ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(MAP, STRING, OBJECT));

        var newRecordCall = newRecordCallBlock(spec);
        var valuesArgs = inputTypeValuesBlock(spec);

        return CodeBlock.builder()
            .add("$T $L = ($T) arguments.get($S);\n", listType, inputArgName, listType, inputArgName)
            .add("return $T.range(0, $L.size())\n", INT_STREAM, inputArgName)
            .indent()
            .add(".mapToObj(i -> {\n")
            .indent()
            .add("var m = $L.get(i);\n", inputArgName)
            .add("return $L\n", newRecordCall)
            .indent()
            .add(".values($L);\n", valuesArgs)
            .unindent()
            .unindent()
            .add("})\n")
            .add(".toList();\n")
            .unindent()
            .build();
    }

    /**
     * Generates the body for the flat-args case:
     * <pre>{@code
     * List<String> ids = (List<String>) arguments.get("ids");
     * return IntStream.range(0, ids.size())
     *     .mapToObj(i -> DSL.newRecord(GRAPHITRON_INPUT_IDX, TABLE.TENANT_ID, TABLE.ID)
     *         .values(i + 1, (String) arguments.get("tenantId"), ids.get(i)))
     *     .toList();
     * }</pre>
     */
    private CodeBlock buildFlatArgsBody(LookupSpec spec) {
        var body = CodeBlock.builder();

        // Declare local variables for each list argument
        var listFields = spec.fields().stream().filter(LookupInputFieldSpec::list).toList();
        for (var f : listFields) {
            int dot = f.columnClass().lastIndexOf('.');
            String pkg = f.columnClass().substring(0, dot);
            String simple = f.columnClass().substring(dot + 1);
            var listType = ParameterizedTypeName.get(LIST, ClassName.get(pkg, simple));
            body.add("$T $L = ($T) arguments.get($S);\n", listType, f.argName(), listType, f.argName());
        }

        // Size expression from the first list field
        var firstList = listFields.isEmpty() ? null : listFields.get(0);
        String sizeExpr = firstList != null ? firstList.argName() + ".size()" : "0";

        var newRecordCall = newRecordCallBlock(spec);
        var valuesArgs = flatValuesBlock(spec);

        body.add("return $T.range(0, $L)\n", INT_STREAM, sizeExpr)
            .indent()
            .add(".mapToObj(i -> $L\n", newRecordCall)
            .indent()
            .add(".values($L))\n", valuesArgs)
            .unindent()
            .add(".toList();\n")
            .unindent();

        return body.build();
    }

    /** {@code ctx.newRecord(GRAPHITRON_INPUT_IDX, TABLE.COL1, ...)} */
    private CodeBlock newRecordCallBlock(LookupSpec spec) {
        var b = CodeBlock.builder();
        b.add("ctx.newRecord(GRAPHITRON_INPUT_IDX");
        for (var f : spec.fields()) {
            b.add(", $L.$L", spec.tableJavaFieldName(), f.columnJavaName());
        }
        b.add(")");
        return b.build();
    }

    /** {@code i + 1, (Type1) m.get("field1"), (Type2) m.get("field2")} for the input-type case. */
    private CodeBlock inputTypeValuesBlock(LookupSpec spec) {
        var b = CodeBlock.builder();
        b.add("i + 1");
        for (var f : spec.fields()) {
            int dot = f.columnClass().lastIndexOf('.');
            String pkg = f.columnClass().substring(0, dot);
            String simple = f.columnClass().substring(dot + 1);
            b.add(", ($T) m.get($S)", ClassName.get(pkg, simple), f.argName());
        }
        return b.build();
    }

    /**
     * {@code i + 1, (Type) arguments.get("scalarArg"), listArg.get(i), ...} for the flat case.
     * List args use the local variable; scalar args read from {@code arguments} with a cast.
     */
    private CodeBlock flatValuesBlock(LookupSpec spec) {
        var b = CodeBlock.builder();
        b.add("i + 1");
        for (var f : spec.fields()) {
            if (f.list()) {
                b.add(", $L.get(i)", f.argName());
            } else {
                int dot = f.columnClass().lastIndexOf('.');
                String pkg = f.columnClass().substring(0, dot);
                String simple = f.columnClass().substring(dot + 1);
                b.add(", ($T) arguments.get($S)", ClassName.get(pkg, simple), f.argName());
            }
        }
        return b.build();
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
