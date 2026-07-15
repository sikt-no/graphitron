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
 *   <li>{@code totalCount(env)} — issues {@code SELECT count(*)} against the field's
 *       {@code (table, condition)} carried on {@code ConnectionResult}; returns {@code null}
 *       when those are absent (only the polymorphic empty-participants defensive path)</li>
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
 * <p>A present cursor must split back into exactly one token per order-by column: encoding always
 * emits N NUL-joined tokens, so strict arity in both directions (too few and too many) rejects
 * any cursor this generator did not emit. Every malformed-input failure of decode (bad Base64,
 * wrong arity, a token not coercible to its column type) is caught and rethrown as a
 * {@code GraphitronClientException} carrying {@code cursor is not valid (was: "<echo>")}, so the
 * opaque token reads back to the client as a client error rather than a redacted 500; the echo is
 * capped at 100 characters. Any other unchecked throw from decode is treated as a server fault and
 * left to propagate.
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
    private static final ClassName DATA_TYPE_EXCEPTION = ClassName.get("org.jooq.exception", "DataTypeException");

    public static List<TypeSpec> generate(String outputPackage) {
        var connectionResultClass = ClassName.get(
            outputPackage + ".util", ConnectionResultClassGenerator.CLASS_NAME);
        // R378 client-error marker: pageRequest's client-mistake guards throw it so the
        // no-channel disposition surfaces the real message instead of redacting (R415).
        var clientException = ClassName.get(outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.CLASS_NAME);

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
        //   - client-mistake guards: first/last mutual exclusion, negative first/last, and the
        //     derived-limit overflow — all thrown as GraphitronClientException so the real
        //     message reaches the client instead of a redacted correlation-id 500 (R415)
        //   - backward/pageSize/cursor derivation
        //   - column-driven cursor decode (delegates to decodeCursor)
        //   - reverse ordering for backward pagination (delegates to reverseOrderBy)
        //   - name-based merge of extraFields into the selection (avoids Field.equals identity
        //     dependence — a name-matching column is treated as already selected).
        // Takes no DataFetchingEnvironment (four env-arg extractions stay on the fetcher side),
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
                clientException, "first and last must not both be specified")
            .addCode("if (first != null && first < 0)\n")
            .addCode("    throw new $T($S + first + $S);\n",
                clientException, "first must not be negative (was: ", ")")
            .addCode("if (last != null && last < 0)\n")
            .addCode("    throw new $T($S + last + $S);\n",
                clientException, "last must not be negative (was: ", ")")
            .addStatement("boolean backward = last != null")
            .addStatement("int pageSize = backward ? last : (first != null ? first : defaultPageSize)")
            // Guard the derived value PostgreSQL actually enforces: limit = pageSize + 1 >= 0.
            // At MAX_VALUE the + 1 wraps to Integer.MIN_VALUE and reaches SQL as a negative
            // LIMIT; guarding here (not per input) also covers a pathological defaultPageSize.
            .addCode("if (pageSize == Integer.MAX_VALUE)\n")
            .addCode("    throw new $T($S);\n",
                clientException, "page size must be less than 2147483647")
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
        // The decode body is wrapped in one try whose multi-catch classifies blame at the wire
        // boundary (R479): Base64 decode, strict-arity, and convert failures are all pure functions
        // of the opaque client cursor, so they collapse to one GraphitronClientException the
        // no-channel disposition surfaces instead of redacting to a correlation-id 500 (R415). Any
        // other unchecked throw (e.g. an NPE from a buggy custom Converter) is a genuine server
        // fault and keeps propagating. Arity is strict in both directions: encodeCursor emits
        // exactly N NUL-joined tokens and PostgreSQL strings cannot contain NUL, so any other token
        // count is a forged, corrupted, or stale-across-schema-change cursor this generator never
        // emitted.
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
            .addCode("try {\n")
            .addCode("    String[] tokens = new String($T.getDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8).split(\"\\u0000\", -1);\n", BASE64)
            .addCode("    if (tokens.length != orderByColumns.size())\n")
            .addCode("        throw new IllegalArgumentException(\"cursor arity mismatch\");\n")
            .addCode("    for (int i = 0; i < orderByColumns.size(); i++) {\n")
            .addCode("        $T col = orderByColumns.get(i);\n", fieldWildcard)
            .addCode("        if (\"\\u0001\".equals(tokens[i])) {\n")
            .addCode("            seekFields[i] = $T.val((Object) null, col.getDataType());\n", DSL)
            .addCode("        } else {\n")
            // jOOQ's DataType.convert is lenient: an uncoercible wire token yields null rather
            // than throwing DataTypeException, so a null here (the sentinel branch above already
            // handled a genuine SQL NULL) means the token is malformed. Rejecting it collapses
            // into the same client error as bad Base64 / wrong arity instead of silently seeking
            // on a null bound value. A legitimate token always round-trips (encode is
            // value.toString(), decode is convert of it), so this never fires on a real cursor.
            .addCode("            Object value = col.getDataType().convert(tokens[i]);\n")
            .addCode("            if (value == null)\n")
            .addCode("                throw new IllegalArgumentException(\"cursor token not coercible\");\n")
            .addCode("            seekFields[i] = $T.val(value, col.getDataType());\n", DSL)
            .addCode("        }\n")
            .addCode("    }\n")
            .addCode("    return seekFields;\n")
            .addCode("} catch (IllegalArgumentException | $T e) {\n", DATA_TYPE_EXCEPTION)
            .addCode("    String echo = cursor.length() > 100 ? cursor.substring(0, 100) + \"\\u2026\" : cursor;\n")
            .addCode("    throw new $T($S + echo + $S);\n",
                clientException, "cursor is not valid (was: \"", "\")")
            .addCode("}\n")
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
        // it. Returns null when (table, condition) are absent — the only remaining producer of
        // such a carrier is the validator-unreachable empty-participants defensive path in
        // MultiTablePolymorphicEmitter.buildRootConnectionFetcher (new ConnectionResult(List.of(),
        // page, null, null)); every reachable path binds a real pair.
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

        // --- facets(DataFetchingEnvironment) → Map<String, List<Map<String, Object>>> ---
        // R13: the facet sibling of totalCount. One UNION ALL of per-facet GROUP BY arms, each
        // under the connection filter minus that facet's own predicate (base AND every other
        // facet's predicate), value cast to TEXT to unify the arms and decoded back through the
        // column's DataType. Lazy on selection like totalCount (this resolver only runs when the
        // client selects facets; unselected facet fields contribute no arm). Returns null on a
        // carrier with no facet plan (the scatter / non-faceted contract, mirroring totalCount's
        // null-(table, condition) gate) and an empty map when no facet field is selected.
        //
        // Ordering happens in Java, after decode, not in SQL: the union's shared value column is
        // necessarily TEXT (heterogeneous facet columns unify on one type), so a SQL ORDER BY
        // could only sort lexicographically ("117" < "48"). The decode loop re-types every value,
        // the per-facet lists are one-entry-per-distinct-value small, and sorting there gives the
        // native order (count DESC, then Comparable value ASC, NULL bucket last; enums sort in
        // declaration order). The statement itself needs no ORDER BY at all — rows demultiplex by
        // the facet label column.
        var facetSpecClass = connectionResultClass.nestedClass("FacetSpec");
        var listOfEntryMap = ParameterizedTypeName.get(LIST_CLASS, mapStringObject);
        var facetsReturn = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfEntryMap);
        var selectedFieldClass = ClassName.get("graphql.schema", "SelectedField");
        var record3 = ParameterizedTypeName.get(ClassName.get("org.jooq", "Record3"),
            ClassName.get(String.class), ClassName.get(String.class), ClassName.get(Integer.class));
        var orderByStepOfRecord3 = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "SelectOrderByStep"), record3);
        var conditionClass = ClassName.get("org.jooq", "Condition");
        var jooqFieldWildcard = ParameterizedTypeName.get(JOOQ_FIELD,
            no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class));
        var facetsMethod = MethodSpec.methodBuilder("facets")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(facetsReturn)
            .addParameter(ENV, "env")
            .addStatement("$T cr = env.getSource()", connectionResultClass)
            .addCode("if (cr.facetSpecs() == null || cr.table() == null || cr.facetBaseCondition() == null) return null;\n")
            .addStatement("$T<String> selected = new $T<>()",
                ClassName.get("java.util", "Set"), ClassName.get("java.util", "LinkedHashSet"))
            .addCode("for ($T sf : env.getSelectionSet().getImmediateFields()) {\n", selectedFieldClass)
            .addCode("    selected.add(sf.getName());\n")
            .addCode("}\n")
            .addStatement("$T<$T> specs = new $T<>()", LIST_CLASS, facetSpecClass, ARRAY_LIST)
            .addCode("for ($T f : cr.facetSpecs()) {\n", facetSpecClass)
            .addCode("    if (selected.contains(f.label())) specs.add(f);\n")
            .addCode("}\n")
            .addCode("if (specs.isEmpty()) return $T.of();\n", MAP)
            .addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass)
            .addStatement("$T union = null", orderByStepOfRecord3)
            .addStatement("$T<String, $T> colByLabel = new $T<>()",
                ClassName.get("java.util", "Map"), jooqFieldWildcard, hashMap)
            .addCode("for ($T f : specs) {\n", facetSpecClass)
            .addCode("    $T cond = cr.facetBaseCondition();\n", conditionClass)
            .addCode("    for ($T<String, $T> e : cr.facetConditions().entrySet()) {\n",
                ClassName.get("java.util", "Map", "Entry"), conditionClass)
            .addCode("        if (!e.getKey().equals(f.label())) cond = cond.and(e.getValue());\n")
            .addCode("    }\n")
            .addCode("    $T col = facetColumn(cr.table(), f.columnName());\n", jooqFieldWildcard)
            .addCode("    colByLabel.put(f.label(), col);\n")
            .addCode("    if (!f.valueNullable()) cond = cond.and(col.isNotNull());\n")
            .addCode("    $T arm = dsl.select($T.inline(f.label()).as(\"facet\"),"
                + " col.cast(String.class).as(\"value\"), $T.count().as(\"cnt\"))\n", orderByStepOfRecord3, DSL, DSL)
            .addCode("        .from(cr.table()).where(cond).groupBy(col);\n")
            .addCode("    union = union == null ? arm : union.unionAll(arm);\n")
            .addCode("}\n")
            .addStatement("$T<$T> rows = union.fetch()", RESULT, record3)
            .addStatement("$T<String, $T> byLabel = new $T<>()",
                ClassName.get("java.util", "Map"), facetSpecClass, hashMap)
            .addStatement("$T<String, $T> out = new $T<>()",
                ClassName.get("java.util", "Map"), listOfEntryMap, hashMap)
            .addCode("for ($T f : specs) {\n", facetSpecClass)
            .addCode("    byLabel.put(f.label(), f);\n")
            .addCode("    out.put(f.label(), new $T<>());\n", ARRAY_LIST)
            .addCode("}\n")
            .addCode("for ($T row : rows) {\n", record3)
            .addCode("    $T f = byLabel.get(row.value1());\n", facetSpecClass)
            .addCode("    if (f == null) continue;\n")
            .addCode("    $T col = colByLabel.get(f.label());\n", jooqFieldWildcard)
            // DSL.val(Object, DataType<T>).getValue() is the non-deprecated wire→typed coercion,
            // the same form decodeCursor's replacement uses (R384): the column's Converter applies,
            // null in, null out (a preserved NULL bucket stays null).
            .addCode("    Object typed = row.value2() == null ? null"
                + " : $T.val((Object) row.value2(), col.getDataType()).getValue();\n", DSL)
            .addCode("    $T<String, Object> entry = new $T<>();\n", hashMap, hashMap)
            .addCode("    entry.put(\"value\", typed);\n")
            .addCode("    entry.put(\"count\", row.value3());\n")
            .addCode("    out.get(f.label()).add(entry);\n")
            .addCode("}\n")
            .addCode("for ($T list : out.values()) {\n", listOfEntryMap)
            .addCode("    list.sort($L::compareFacetEntries);\n", CLASS_NAME)
            .addCode("}\n")
            .addStatement("return out")
            .build();

        // Per-facet result order: count DESC, then the decoded value's natural order. Comparing
        // decoded (typed) values is the point of sorting here rather than in SQL — see the facets
        // method comment.
        var compareFacetEntries = MethodSpec.methodBuilder("compareFacetEntries")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(int.class)
            .addParameter(mapStringObject, "a")
            .addParameter(mapStringObject, "b")
            .addStatement("int byCount = Integer.compare((Integer) b.get(\"count\"), (Integer) a.get(\"count\"))")
            .addCode("if (byCount != 0) return byCount;\n")
            .addStatement("return compareFacetValues(a.get(\"value\"), b.get(\"value\"))")
            .build();

        // Same-class Comparable values compare natively (Integer numerically, enums in
        // declaration order); the NULL bucket sorts last; anything else falls back to text.
        var compareFacetValues = MethodSpec.methodBuilder("compareFacetValues")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(int.class)
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "{$S, $S}", "unchecked", "rawtypes")
                .build())
            .addParameter(Object.class, "a")
            .addParameter(Object.class, "b")
            .addCode("if (a == null) return b == null ? 0 : 1;\n")
            .addCode("if (b == null) return -1;\n")
            .addCode("if (a instanceof Comparable && a.getClass() == b.getClass()) {\n")
            .addCode("    return ((Comparable) a).compareTo(b);\n")
            .addCode("}\n")
            .addStatement("return String.valueOf(a).compareTo(String.valueOf(b))")
            .build();

        // Runtime column resolution for a facet's @field(name:) value: jOOQ's Table.field(String)
        // is case-sensitive, while directive values may differ in case from the generated names,
        // so fall back to a case-insensitive scan before failing loudly.
        var facetColumnHelper = MethodSpec.methodBuilder("facetColumn")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(jooqFieldWildcard)
            .addParameter(ParameterizedTypeName.get(ClassName.get("org.jooq", "Table"),
                no.sikt.graphitron.javapoet.WildcardTypeName.subtypeOf(Object.class)), "table")
            .addParameter(String.class, "columnName")
            .addStatement("$T col = table.field(columnName)", jooqFieldWildcard)
            .addCode("if (col != null) return col;\n")
            .addCode("for ($T f : table.fields()) {\n", jooqFieldWildcard)
            .addCode("    if (f.getName().equalsIgnoreCase(columnName)) return f;\n")
            .addCode("}\n")
            .addStatement("throw new IllegalStateException(\"facet column '\" + columnName"
                + " + \"' not found on table '\" + table.getName() + \"'\")")
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
            .addMethod(facetsMethod)
            .addMethod(compareFacetEntries)
            .addMethod(compareFacetValues)
            .addMethod(facetColumnHelper)
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
