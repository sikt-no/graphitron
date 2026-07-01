package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;

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
 *   <li>{@code totalCount(env)} — issues {@code SELECT count(*)} against the parent field's
 *       {@code (table, condition)} carried on {@code ConnectionResult}; returns {@code null}
 *       when those are absent (Split-Connection scatter path)</li>
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

    public static List<TypeSpec> generate(String outputPackage) {
        var connectionResultClass = ClassName.get(
            outputPackage + ".util", ConnectionResultClassGenerator.CLASS_NAME);

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
        var sortField = ClassName.get("org.jooq", "SortField");
        var sortFieldWildcard = ParameterizedTypeName.get(sortField, WildcardTypeName.subtypeOf(Object.class));
        var listOfSortField = ParameterizedTypeName.get(LIST_CLASS, sortFieldWildcard);
        var fieldArray = ArrayTypeName.of(fieldWildcard);

        // --- PageRequest nested class ---
        // Carries the resolved pagination state from pageRequest(...) back to the fetcher body
        // and onward to ConnectionResult. Two field lists deliberately coexist:
        //   - selectFields: selection ∪ extraFields, name-deduped — drives .select(...)
        //   - extraFields:  the pure extra-ordering columns — drives cursor encoding in
        //     ConnectionResult (which must hash only the ordering columns, not the whole selection)
        // Emitted as a class with accessors (not a Java record) because the project's JavaPoet
        // fork does not yet expose recordBuilder.
        var pageRequestClass = buildPageRequestClass(listOfField, listOfSortField, fieldArray);

        // --- reverseOrderBy(List<SortField<?>>) → List<SortField<?>> ---
        // Private helper used only by pageRequest(...) for backward pagination. Uses jOOQ's
        // $field() and $sortOrder() model-API accessors (stable since 3.17) — the only way to
        // flip a SortField's direction without losing its expression. Moved here from per-
        // fetcher-class emission so every connection fetcher shares one implementation.
        var sortOrderClass = ClassName.get("org.jooq", "SortOrder");
        var reverseOrderBy = MethodSpec.methodBuilder("reverseOrderBy")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfSortField)
            .addParameter(listOfSortField, "orderBy")
            .addStatement("$T<$T<?>> reversed = new $T<>(orderBy.size())", ARRAY_LIST, sortField, ARRAY_LIST)
            .addCode("for ($T<?> sf : orderBy) {\n", sortField)
            .addCode("    reversed.add(sf.$$field().sort(\n")
            .addCode("        sf.$$sortOrder() == $T.DESC ? $T.ASC : $T.DESC));\n",
                sortOrderClass, sortOrderClass, sortOrderClass)
            .addCode("}\n")
            .addStatement("return reversed")
            .build();

        // --- pageRequest(Integer first, Integer last, String after, String before,
        //                 int defaultPageSize, List<SortField<?>> orderBy,
        //                 List<Field<?>> extraFields, List<Field<?>> selection) → PageRequest ---
        // Collapses every line of pagination boilerplate the fetcher used to inline:
        //   - first/last mutual-exclusion guard
        //   - backward/pageSize/cursor derivation
        //   - column-driven cursor decode (delegates to decodeCursor)
        //   - reverse ordering for backward pagination (delegates to reverseOrderBy)
        //   - name-based merge of extraFields into the selection (avoids Field.equals identity
        //     dependence — a name-matching column is treated as already selected).
        // Has no graphql-java dependency (four env-arg extractions stay on the fetcher side),
        // matching the purity contract on the entity-scoped *Conditions classes.
        var integer = ClassName.get(Integer.class);
        var pageRequestRef = ClassName.get("", "PageRequest");
        var pageRequest = MethodSpec.methodBuilder("pageRequest")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(pageRequestRef)
            .addParameter(integer, "first")
            .addParameter(integer, "last")
            .addParameter(String.class, "after")
            .addParameter(String.class, "before")
            .addParameter(int.class, "defaultPageSize")
            .addParameter(listOfSortField, "orderBy")
            .addParameter(listOfField, "extraFields")
            .addParameter(listOfField, "selection")
            .addCode("if (first != null && last != null)\n")
            .addCode("    throw new $T($S);\n",
                IllegalArgumentException.class, "first and last must not both be specified")
            .addStatement("boolean backward = last != null")
            .addStatement("int pageSize = backward ? last : (first != null ? first : defaultPageSize)")
            .addStatement("String cursor = backward ? before : after")
            .addStatement("$T seekFields = decodeCursor(cursor, extraFields)", fieldArray)
            .addStatement("$T effectiveOrderBy = backward ? reverseOrderBy(orderBy) : orderBy", listOfSortField)
            // Name-based merge: copy selection, then append every extra-ordering column not
            // already present. Matching on name avoids Field.equals identity mismatches when
            // the same column arrives through different jOOQ Field instances (e.g. $fields vs.
            // the ordering path).
            .addStatement("$T<$T> selectFields = new $T<>(selection)", ARRAY_LIST, fieldWildcard, ARRAY_LIST)
            .addStatement("$T<String> selectedNames = new $T<>()",
                ClassName.get("java.util", "HashSet"), ClassName.get("java.util", "HashSet"))
            .addCode("for ($T f : selection) selectedNames.add(f.getName());\n", fieldWildcard)
            .addCode("for ($T extra : extraFields) {\n", fieldWildcard)
            .addCode("    if (!selectedNames.contains(extra.getName())) selectFields.add(extra);\n")
            .addCode("}\n")
            .addStatement("return new $T(pageSize + 1, pageSize, backward, after, before,\n"
                + "            effectiveOrderBy, seekFields, selectFields, extraFields)",
                pageRequestRef)
            .build();

        // --- encodeCursor(Record, List<Field<?>>) ---
        // Column-driven: each value serialised via DataType (no hand-rolled type tags).
        var encodeCursor = MethodSpec.methodBuilder("encodeCursor")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(RECORD, "record")
            .addParameter(listOfField, "orderByColumns")
            .addStatement("$T sb = new $T()",
                ClassName.get("java.lang", "StringBuilder"),
                ClassName.get("java.lang", "StringBuilder"))
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
            .addCode("    $T col = orderByColumns.get(i);\n", fieldWildcard)
            .addCode("    if (\"\\u0001\".equals(tokens[i])) {\n")
            .addCode("        seekFields[i] = $T.val((Object) null, col.getDataType());\n", DSL)
            .addCode("    } else {\n")
            .addCode("        seekFields[i] = $T.val(col.getDataType().convert(tokens[i]), col.getDataType());\n", DSL)
            .addCode("    }\n")
            .addCode("}\n")
            .addStatement("return seekFields")
            .build();

        var listOfRecord = ParameterizedTypeName.get(LIST_CLASS, RECORD);

        // --- edges(DataFetchingEnvironment) → List<Edge> ---
        var edgeClassName = ClassName.get("", "Edge");
        var listOfEdge = ParameterizedTypeName.get(LIST_CLASS, edgeClassName);

        var edgesMethod = MethodSpec.methodBuilder("edges")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfEdge)
            .addParameter(ENV, "env")
            .addStatement("$T cr = env.getSource()", connectionResultClass)
            .addStatement("$T trimmed = cr.trimmedResult()", listOfRecord)
            .addStatement("$T<Edge> edges = new $T<>(trimmed.size())", ARRAY_LIST, ARRAY_LIST)
            .addCode("for ($T record : trimmed) {\n", RECORD)
            .addCode("    edges.add(new Edge(record, encodeCursor(record, cr.orderByColumns())));\n")
            .addCode("}\n")
            .addStatement("return edges")
            .build();

        // --- nodes(DataFetchingEnvironment) → List<Record> ---
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
            .addStatement("$T trimmed = cr.trimmedResult()", listOfRecord)
            .addStatement("String startCursor = trimmed.isEmpty() ? null : encodeCursor(trimmed.get(0), cr.orderByColumns())")
            .addStatement("String endCursor = trimmed.isEmpty() ? null : encodeCursor(trimmed.get(trimmed.size() - 1), cr.orderByColumns())")
            .addStatement("$T<String, Object> info = new $T<>()", hashMap, hashMap)
            .addStatement("info.put(\"hasNextPage\", cr.hasNextPage())")
            .addStatement("info.put(\"hasPreviousPage\", cr.hasPreviousPage())")
            .addStatement("info.put(\"startCursor\", startCursor)")
            .addStatement("info.put(\"endCursor\", endCursor)")
            .addStatement("return info")
            .build();

        // --- totalCount(DataFetchingEnvironment) → Integer ---
        // Lazy on selection: graphql-java only invokes the registered resolver when the client
        // selects totalCount, so the count SQL is skipped on every query that does not ask for
        // it. Returns null when (table, condition) are absent — the Split-Connection scatter
        // path supplies a ConnectionResult without (table, condition); selecting totalCount on
        // those carriers returns null until per-parent count plumbing lands (see roadmap follow-up).
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        var graphitronContextClass = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        var totalCountMethod = MethodSpec.methodBuilder("totalCount")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Integer.class)
            .addParameter(ENV, "env")
            .addStatement("$T cr = env.getSource()", connectionResultClass)
            .addCode("if (cr.table() == null || cr.condition() == null) return null;\n")
            .addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass)
            .addStatement("return dsl.selectCount().from(cr.table()).where(cr.condition()).fetchOne(0, $T.class)", Integer.class)
            .build();

        // Mirrors TypeFetcherGenerator.buildGraphitronContextHelper. Emitted on this helper so
        // totalCount can resolve the per-request DSLContext without leaning on a fetcher class.
        var graphitronContextShim = MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(graphitronContextClass)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($T.class)", graphitronContextClass)
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

        // decodeCursor binds wire-format String tokens via DataType.convert(Object), which
        // jOOQ deprecated for removal in 3.20.0. The recommended replacement does not accept
        // Object input, and the only public Object→T coercion path (org.jooq.tools.Convert)
        // is itself marked for removal. Suppress until jOOQ ships a public successor.
        var suppressRemoval = AnnotationSpec.builder(SuppressWarnings.class)
            .addMember("value", "{$S, $S}", "deprecation", "removal")
            .build();
        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(suppressRemoval)
            .addType(edgeClass)
            .addType(pageRequestClass)
            .addMethod(encodeCursor)
            .addMethod(decodeCursor)
            .addMethod(reverseOrderBy)
            .addMethod(pageRequest)
            .addMethod(edgesMethod)
            .addMethod(nodesMethod)
            .addMethod(pageInfoMethod)
            .addMethod(totalCountMethod)
            .addMethod(graphitronContextShim)
            .addMethod(edgeNodeMethod)
            .addMethod(edgeCursorMethod)
            .build();

        return List.of(spec);
    }

    private static TypeSpec buildPageRequestClass(
            ParameterizedTypeName listOfField,
            ParameterizedTypeName listOfSortField,
            ArrayTypeName fieldArray) {
        var cls = TypeSpec.classBuilder("PageRequest")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addField(int.class, "limit", Modifier.PRIVATE, Modifier.FINAL)
            .addField(int.class, "pageSize", Modifier.PRIVATE, Modifier.FINAL)
            .addField(boolean.class, "backward", Modifier.PRIVATE, Modifier.FINAL)
            .addField(String.class, "after", Modifier.PRIVATE, Modifier.FINAL)
            .addField(String.class, "before", Modifier.PRIVATE, Modifier.FINAL)
            .addField(listOfSortField, "effectiveOrderBy", Modifier.PRIVATE, Modifier.FINAL)
            .addField(fieldArray, "seekFields", Modifier.PRIVATE, Modifier.FINAL)
            .addField(listOfField, "selectFields", Modifier.PRIVATE, Modifier.FINAL)
            .addField(listOfField, "extraFields", Modifier.PRIVATE, Modifier.FINAL);

        cls.addMethod(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(int.class, "limit")
            .addParameter(int.class, "pageSize")
            .addParameter(boolean.class, "backward")
            .addParameter(String.class, "after")
            .addParameter(String.class, "before")
            .addParameter(listOfSortField, "effectiveOrderBy")
            .addParameter(fieldArray, "seekFields")
            .addParameter(listOfField, "selectFields")
            .addParameter(listOfField, "extraFields")
            .addStatement("this.limit = limit")
            .addStatement("this.pageSize = pageSize")
            .addStatement("this.backward = backward")
            .addStatement("this.after = after")
            .addStatement("this.before = before")
            .addStatement("this.effectiveOrderBy = effectiveOrderBy")
            .addStatement("this.seekFields = seekFields")
            .addStatement("this.selectFields = selectFields")
            .addStatement("this.extraFields = extraFields")
            .build());

        accessor(cls, "limit",            int.class);
        accessor(cls, "pageSize",         int.class);
        accessor(cls, "backward",         boolean.class);
        accessor(cls, "after",            String.class);
        accessor(cls, "before",           String.class);
        accessor(cls, "effectiveOrderBy", listOfSortField);
        accessor(cls, "seekFields",       fieldArray);
        accessor(cls, "selectFields",     listOfField);
        accessor(cls, "extraFields",      listOfField);

        return cls.build();
    }

    private static void accessor(TypeSpec.Builder cls, String name, Class<?> type) {
        cls.addMethod(MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC)
            .returns(type)
            .addStatement("return $L", name)
            .build());
    }

    private static void accessor(TypeSpec.Builder cls, String name, no.sikt.graphitron.javapoet.TypeName type) {
        cls.addMethod(MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC)
            .returns(type)
            .addStatement("return $L", name)
            .build());
    }
}
