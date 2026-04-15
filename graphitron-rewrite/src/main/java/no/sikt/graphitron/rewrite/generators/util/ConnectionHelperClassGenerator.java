package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
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
 * <p>Cursor encoding: Base64-encoded JSON array of column values with type tags.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public class ConnectionHelperClassGenerator {

    public static final String CLASS_NAME = "ConnectionHelper";

    private static final ClassName ENV              = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RESULT           = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD           = ClassName.get("org.jooq", "Record");
    private static final ClassName JOOQ_FIELD       = ClassName.get("org.jooq", "Field");
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

        // --- encodeCursor(Record, List<Field<?>>) ---
        var fieldWildcard = ParameterizedTypeName.get(JOOQ_FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST_CLASS, fieldWildcard);

        var encodeCursor = MethodSpec.methodBuilder("encodeCursor")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(RECORD, "record")
            .addParameter(listOfField, "orderByColumns")
            .addStatement("var sb = new StringBuilder(\"[\")")
            .addCode("for (int i = 0; i < orderByColumns.size(); i++) {\n")
            .addCode("    if (i > 0) sb.append(\",\");\n")
            .addCode("    Object val = record.get(orderByColumns.get(i));\n")
            .addCode("    if (val == null) { sb.append(\"\\\"n:\\\"\"); continue; }\n")
            .addCode("    String tag = switch (val) {\n")
            .addCode("        case Integer ignored -> \"i\";\n")
            .addCode("        case Long ignored -> \"l\";\n")
            .addCode("        case String ignored -> \"s\";\n")
            .addCode("        case java.math.BigDecimal ignored -> \"d\";\n")
            .addCode("        case Short ignored -> \"h\";\n")
            .addCode("        case java.sql.Timestamp ignored -> \"t\";\n")
            .addCode("        case java.time.LocalDateTime ignored -> \"T\";\n")
            .addCode("        case Boolean ignored -> \"b\";\n")
            .addCode("        default -> \"s\";\n")
            .addCode("    };\n")
            .addCode("    sb.append('\"').append(tag).append(':').append(val).append('\"');\n")
            .addCode("}\n")
            .addStatement("sb.append(\"]\")")
            .addStatement("return $T.getEncoder().encodeToString(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))", BASE64)
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

        // --- decodeCursor(String) → Object[] ---
        var decodeCursor = MethodSpec.methodBuilder("decodeCursor")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object[].class)
            .addParameter(String.class, "cursor")
            .addStatement("String json = new String($T.getDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8)", BASE64)
            .addCode("// Parse the JSON array of type-tagged values\n")
            .addStatement("json = json.strip()")
            .addStatement("if (!json.startsWith(\"[\") || !json.endsWith(\"]\")) throw new IllegalArgumentException(\"Invalid cursor format\")")
            .addStatement("json = json.substring(1, json.length() - 1)")
            .addStatement("if (json.isEmpty()) return new Object[0]")
            .addCode("// Quote-aware split: don't split on commas inside quoted tokens\n")
            .addCode("java.util.List<String> tokenList = new java.util.ArrayList<>();\n")
            .addCode("boolean inQuotes = false;\n")
            .addCode("int segStart = 0;\n")
            .addCode("for (int k = 0; k < json.length(); k++) {\n")
            .addCode("    char ch = json.charAt(k);\n")
            .addCode("    if (ch == '\"') inQuotes = !inQuotes;\n")
            .addCode("    else if (ch == ',' && !inQuotes) {\n")
            .addCode("        tokenList.add(json.substring(segStart, k).strip());\n")
            .addCode("        segStart = k + 1;\n")
            .addCode("    }\n")
            .addCode("}\n")
            .addCode("tokenList.add(json.substring(segStart).strip());\n")
            .addStatement("Object[] values = new Object[tokenList.size()]")
            .addCode("for (int i = 0; i < tokenList.size(); i++) {\n")
            .addCode("    String part = tokenList.get(i);\n")
            .addCode("    if (part.startsWith(\"\\\"\")) part = part.substring(1, part.length() - 1);\n")
            .addCode("    if (part.equals(\"n:\")) { values[i] = null; continue; }\n")
            .addCode("    int colon = part.indexOf(':');\n")
            .addCode("    String tag = part.substring(0, colon);\n")
            .addCode("    String val = part.substring(colon + 1);\n")
            .addCode("    values[i] = switch (tag) {\n")
            .addCode("        case \"i\" -> Integer.parseInt(val);\n")
            .addCode("        case \"l\" -> Long.parseLong(val);\n")
            .addCode("        case \"h\" -> Short.parseShort(val);\n")
            .addCode("        case \"d\" -> new java.math.BigDecimal(val);\n")
            .addCode("        case \"b\" -> Boolean.parseBoolean(val);\n")
            .addCode("        case \"t\" -> java.sql.Timestamp.valueOf(val);\n")
            .addCode("        case \"T\" -> java.time.LocalDateTime.parse(val);\n")
            .addCode("        default -> val;\n")
            .addCode("    };\n")
            .addCode("}\n")
            .addStatement("return values")
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
