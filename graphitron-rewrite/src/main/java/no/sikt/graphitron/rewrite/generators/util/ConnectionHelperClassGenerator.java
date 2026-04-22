package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.RewriteConfig;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the {@code ConnectionHelper} utility class, emitted once per code-generation run.
 *
 * <p>Contains static methods used by connection-type wiring registrations:
 * <ul>
 *   <li>{@code edges(env)} — trims result to page size, wraps each Record into an Edge</li>
 *   <li>{@code nodes(env)} — trims result to page size, returns Records directly</li>
 *   <li>{@code pageInfo(env)} — computes hasNextPage, hasPreviousPage, startCursor, endCursor</li>
 *   <li>{@code edgeNode(env)} — returns the Record from an Edge</li>
 *   <li>{@code edgeCursor(env)} — returns the cursor string from an Edge</li>
 * </ul>
 *
 * <p>Also contains a nested {@code Edge} record class carrying a {@code Record} and cursor string.
 *
 * <p>Cursor encoding: column values are joined with {@code \u0000} (NUL) as separator;
 * SQL {@code NULL} is encoded as {@code \u0001} (SOH). The joined string is Base64-encoded.
 * PostgreSQL strings cannot contain NUL bytes, so no escaping is needed. Decoding splits on
 * {@code \u0000} and uses {@code field.getDataType().convert(token)} for type-safe
 * round-tripping. Returns {@code Field<?>[]} so jOOQ's {@code .seek(Field<?>...)} receives
 * correctly-typed bind values. When no cursor is present, {@code DSL.noField(field)} is
 * returned per column — making {@code .seek()} a no-op.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public class ConnectionHelperClassGenerator {

    public static final String CLASS_NAME = "ConnectionHelper";

    private static final ClassName ENV              = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RESULT           = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD           = ClassName.get("org.jooq", "Record");
    private static final ClassName JOOQ_FIELD       = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL              = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName LIST_CLASS       = ClassName.get(List.class);
    private static final ClassName MAP              = ClassName.get("java.util", "Map");
    private static final ClassName ARRAY_LIST       = ClassName.get("java.util", "ArrayList");
    private static final ClassName BASE64           = ClassName.get("java.util", "Base64");

    public static List<TypeSpec> generate() {
        var connectionResultClass = ClassName.get(
            RewriteConfig.outputPackage() + ".rewrite", ConnectionResultClassGenerator.CLASS_NAME);

        // --- Edge nested class ---
        var edgeClass = TypeSpec.classBuilder("Edge")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addField(RECORD, "record", Modifier.PRIVATE, Modifier.FINAL)
            .addField(String.class, "cursor", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(RECORD, "record")
                .addParameter(String.class, "cursor")
                .addStatement("this.record = record")
                .addStatement("this.cursor = cursor")
                .build())
            .addMethod(MethodSpec.methodBuilder("record")
                .addModifiers(Modifier.PUBLIC)
                .returns(RECORD)
                .addStatement("return record")
                .build())
            .addMethod(MethodSpec.methodBuilder("cursor")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return cursor")
                .build())
            .build();

        var fieldWildcard = ParameterizedTypeName.get(JOOQ_FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST_CLASS, fieldWildcard);

        // --- encodeCursor(Record, List<Field<?>>) ---
        // Column-driven: each value serialised via DataType (no hand-rolled type tags).
        var encodeCursor = MethodSpec.methodBuilder("encodeCursor")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(RECORD, "record")
            .addParameter(listOfField, "orderByColumns")
            .addStatement("var sb = new $T()", ClassName.get("java.lang", "StringBuilder"))
            .addCode("for (int i = 0; i < orderByColumns.size(); i++) {\n")
            .addCode("    if (i > 0) sb.append(\"\\u0000\");\n")
            .addCode("    Object val = record.get(orderByColumns.get(i));\n")
            .addCode("    sb.append(val == null ? \"\\u0001\" : val.toString());\n")
            .addCode("}\n")
            .addStatement("return $T.getEncoder().encodeToString(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))", BASE64)
            .build();

        // --- decodeCursor(String cursor, List<Field<?>>) → Field<?>[] ---
        // Returns DSL.noField(col) per column when cursor is null (seek no-op).
        // Returns DSL.val(DataType.convert(token), DataType) per column when cursor is present.
        var decodeCursor = MethodSpec.methodBuilder("decodeCursor")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ArrayTypeName.of(fieldWildcard))
            .addParameter(String.class, "cursor")
            .addParameter(listOfField, "orderByColumns")
            .addStatement("$T[] seekFields = new $T[orderByColumns.size()]", fieldWildcard, fieldWildcard)
            .addCode("if (cursor == null) {\n")
            .addCode("    for (int i = 0; i < orderByColumns.size(); i++)\n")
            .addCode("        seekFields[i] = $T.noField(orderByColumns.get(i));\n", DSL)
            .addCode("    return seekFields;\n")
            .addCode("}\n")
            .addStatement("String[] tokens = new String($T.getDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8).split(\"\\u0000\", -1)", BASE64)
            .addCode("for (int i = 0; i < orderByColumns.size(); i++) {\n")
            .addCode("    $T col = orderByColumns.get(i);\n", JOOQ_FIELD)
            .addCode("    if (\"\\u0001\".equals(tokens[i])) {\n")
            .addCode("        seekFields[i] = $T.val((Object) null, col.getDataType());\n", DSL)
            .addCode("    } else {\n")
            .addCode("        seekFields[i] = $T.val(col.getDataType().convert(tokens[i]), col.getDataType());\n", DSL)
            .addCode("    }\n")
            .addCode("}\n")
            .addStatement("return seekFields")
            .build();

        // --- edges(DataFetchingEnvironment) → List<Edge> ---
        var edgeClassName = ClassName.get("", "Edge");
        var listOfEdge = ParameterizedTypeName.get(LIST_CLASS, edgeClassName);

        var edgesMethod = MethodSpec.methodBuilder("edges")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfEdge)
            .addParameter(ENV, "env")
            .addStatement("$T cr = env.getSource()", connectionResultClass)
            .addStatement("var trimmed = cr.trimmedResult()")
            .addStatement("var edges = new $T<Edge>(trimmed.size())", ARRAY_LIST)
            .addCode("for ($T record : trimmed) {\n", RECORD)
            .addCode("    edges.add(new Edge(record, encodeCursor(record, cr.orderByColumns())));\n")
            .addCode("}\n")
            .addStatement("return edges")
            .build();

        // --- nodes(DataFetchingEnvironment) → List<Record> ---
        var listOfRecord = ParameterizedTypeName.get(LIST_CLASS, RECORD);

        var nodesMethod = MethodSpec.methodBuilder("nodes")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(ENV, "env")
            .addStatement("$T cr = env.getSource()", connectionResultClass)
            .addStatement("return cr.trimmedResult()")
            .build();

        // --- pageInfo(DataFetchingEnvironment) → Map<String, Object> ---
        var mapStringObject = ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class));

        var hashMap = ClassName.get("java.util", "LinkedHashMap");
        var pageInfoMethod = MethodSpec.methodBuilder("pageInfo")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(mapStringObject)
            .addParameter(ENV, "env")
            .addStatement("$T cr = env.getSource()", connectionResultClass)
            .addStatement("var trimmed = cr.trimmedResult()")
            .addStatement("String startCursor = trimmed.isEmpty() ? null : encodeCursor(trimmed.get(0), cr.orderByColumns())")
            .addStatement("String endCursor = trimmed.isEmpty() ? null : encodeCursor(trimmed.get(trimmed.size() - 1), cr.orderByColumns())")
            .addStatement("var info = new $T<String, Object>()", hashMap)
            .addStatement("info.put(\"hasNextPage\", cr.hasNextPage())")
            .addStatement("info.put(\"hasPreviousPage\", cr.hasPreviousPage())")
            .addStatement("info.put(\"startCursor\", startCursor)")
            .addStatement("info.put(\"endCursor\", endCursor)")
            .addStatement("return info")
            .build();

        // --- edgeNode(DataFetchingEnvironment) → Record ---
        var edgeNodeMethod = MethodSpec.methodBuilder("edgeNode")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(RECORD)
            .addParameter(ENV, "env")
            .addStatement("return (($L) env.getSource()).record()", "Edge")
            .build();

        // --- edgeCursor(DataFetchingEnvironment) → String ---
        var edgeCursorMethod = MethodSpec.methodBuilder("edgeCursor")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(ENV, "env")
            .addStatement("return (($L) env.getSource()).cursor()", "Edge")
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addType(edgeClass)
            .addMethod(encodeCursor)
            .addMethod(decodeCursor)
            .addMethod(edgesMethod)
            .addMethod(nodesMethod)
            .addMethod(pageInfoMethod)
            .addMethod(edgeNodeMethod)
            .addMethod(edgeCursorMethod)
            .build();

        return List.of(spec);
    }
}
