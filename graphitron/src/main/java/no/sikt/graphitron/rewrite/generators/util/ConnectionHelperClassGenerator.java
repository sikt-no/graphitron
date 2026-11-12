package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.OptionalInt;

/**
 * Generates the {@code ConnectionHelper} utility class, emitted once per code-generation run.
 *
 * <p>The utility the connection producers build their connection classes with: the page request
 * ({@code pageRequest}), the page flags ({@code trim}, {@code hasNextPage},
 * {@code hasPreviousPage}), the cursor function {@code cursorOf}, the facet bucketing
 * {@code facetBuckets}, the request's {@code DSLContext} a single-tenant count or facets closure
 * runs on ({@code dsl}), and the nested count and facet types: {@value #TOTAL_COUNT_CLASS_NAME}
 * (the count behind {@code totalCount}), {@value #SCOPED_CLASS_NAME} (a root connection's own
 * {@code count(*)}), {@value #BATCH_COUNTS_CLASS_NAME} (one grouped count per DataLoader batch,
 * handing out a {@value #SLOT_CLASS_NAME} per parent) and {@value #FACET_SPEC_CLASS_NAME} (one
 * facet arm). It resolves no field: the store-rendered {@code Connections} classes do.
 *
 * <p>Cursor encoding, the bytes Graphitron 9 issued on Oracle: a JSON array with one element per
 * order column and no whitespace, SQL {@code NULL} as {@code null} and every other value as
 * {@code ColumnValues.toText} of the column's database value (the record's value passed back
 * through the column's converter, an enum as its literal), which writes it the way Oracle's
 * {@code JSON_ARRAY} renders the column's SQL type: a number or boolean bare, a temporal value as
 * an ISO date-time, anything else as a JSON string. Encoded as URL-safe Base64 without padding.
 * Decoding reads any JSON scalar, so a cursor that wrote every value as a string still reads, and
 * binds each element through
 * {@code ColumnValues.fromDatabase(column.getDataType(), element)}, which parses it as the column's
 * database type and runs the converter, or, for a column with no SQL type, hands the element to
 * the converter as the database value. Returns {@code Field<?>[]} so jOOQ's
 * {@code .seek(Field<?>...)} receives correctly-typed bind values. When no cursor is present
 * (absent, empty, or blank), {@code DSL.noField(field)} is returned per column, making
 * {@code .seek()} a no-op.
 *
 * <p>Decoding also reads a second format, the one the Graphitron 10 release candidates wrote,
 * so a cursor a client stored under one keeps its place after the upgrade: each order column's
 * user-side value as its {@code toString()} (U+0001 for SQL {@code NULL}), joined by U+0000, in
 * standard Base64. Its tokens are read as the release candidates read them, coerced on the
 * column's converted type. The decoder tells the formats apart before reading: by the Base64
 * alphabet when the cursor holds a character only one alphabet has, else by the decoded text (a
 * JSON cursor opens with {@code [} and holds no raw U+0000), and text that looks like JSON is read
 * as JSON first and as a release-candidate cursor only when that fails. Encoding writes only the
 * JSON form.
 *
 * <p>Every malformed-input failure of decode (bad Base64, a malformed JSON array, wrong element
 * arity, an element that is no value of its column) is rethrown as a {@code GraphitronClientException} carrying
 * {@code cursor is not valid (was: "<echo>")}, echo capped at 100 characters; any other
 * unchecked throw from decode is a server fault and propagates. The {@code decodeCursor}
 * emission below carries the full blame contract.
 *
 * <p>Generated as a source file so consuming projects have no runtime dependency on Graphitron.
 */
public class ConnectionHelperClassGenerator {

    public static final String CLASS_NAME = "ConnectionHelper";

    /** The nested count interface the {@code totalCount} resolver calls. */
    public static final String TOTAL_COUNT_CLASS_NAME = "TotalCount";

    /** The nested {@code TotalCount} counting {@code (table, condition)} with its own statement. */
    public static final String SCOPED_CLASS_NAME = "Scoped";

    /** The nested per-batch memo whose slots share one grouped count. */
    public static final String BATCH_COUNTS_CLASS_NAME = "BatchCounts";

    /** The {@code TotalCount} a {@code BatchCounts} hands out per parent index, nested in it. */
    public static final String SLOT_CLASS_NAME = "Slot";

    /** The nested runtime entry for one facet arm. */
    public static final String FACET_SPEC_CLASS_NAME = "FacetSpec";

    private static final ClassName ENV              = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RESULT           = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD           = ClassName.get("org.jooq", "Record");
    private static final ClassName JOOQ_FIELD       = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL              = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName DSL_CONTEXT      = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName CONDITION        = ClassName.get("org.jooq", "Condition");
    private static final ClassName JOOQ_TABLE       = ClassName.get("org.jooq", "Table");
    private static final ClassName FUNCTION         = ClassName.get("java.util.function", "Function");
    private static final ClassName LIST_CLASS       = ClassName.get(List.class);
    private static final ClassName MAP              = ClassName.get("java.util", "Map");
    private static final ClassName ARRAY_LIST       = ClassName.get("java.util", "ArrayList");
    private static final ClassName BASE64           = ClassName.get("java.util", "Base64");
    private static final ClassName ENUM_TYPE        = ClassName.get("org.jooq", "EnumType");
    private static final ClassName DATA_TYPE_EXCEPTION = ClassName.get("org.jooq.exception", "DataTypeException");

    /** The name of the constant {@code pageRequest} lowers an oversized page size to. */
    public static final String MAX_PAGE_SIZE = "MAX_PAGE_SIZE";

    public static List<TypeSpec> generate(String outputPackage) {
        return generate(outputPackage, false, OptionalInt.empty());
    }

    /**
     * Canonical form carrying the tenancy bit and the build's maximum page size. In a multi-tenant
     * build the producers' count and facets closures capture the routed {@code DSLContext} their
     * page ran on, so the aggregates run against the same source the page rows came from, and the
     * class carries no per-request {@code dsl}.
     *
     * <p>With a {@code maxAllowedPageSize} the class carries a {@value #MAX_PAGE_SIZE} constant and
     * {@code pageRequest} serves a larger page size at it. Nothing downstream changes: the query
     * still fetches {@code pageSize + 1} rows, so the page flags report the remainder truthfully
     * and the client pages on. Without one the class is what it always was.
     */
    public static List<TypeSpec> generate(String outputPackage, boolean multiTenant,
                                          OptionalInt maxAllowedPageSize) {
        // Client-error marker: pageRequest's client-mistake guards throw it so the
        // no-channel disposition surfaces the real message instead of redacting.
        var clientException = ClassName.get(outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.CLASS_NAME);
        var columnValuesClass = ClassName.get(outputPackage + ".util", ColumnValuesClassGenerator.CLASS_NAME);

        var fieldWildcard = ParameterizedTypeName.get(JOOQ_FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfField = ParameterizedTypeName.get(LIST_CLASS, fieldWildcard);
        var sortField = ClassName.get("org.jooq", "SortField");
        var sortFieldWildcard = ParameterizedTypeName.get(sortField, WildcardTypeName.subtypeOf(Object.class));
        var listOfSortField = ParameterizedTypeName.get(LIST_CLASS, sortFieldWildcard);
        var fieldArray = ArrayTypeName.of(fieldWildcard);
        var tableWildcard = ParameterizedTypeName.get(JOOQ_TABLE, WildcardTypeName.subtypeOf(Object.class));
        var totalCountRef = ClassName.get(outputPackage + ".util", CLASS_NAME, TOTAL_COUNT_CLASS_NAME);

        // --- PageRequest nested class ---
        // Carries the resolved pagination state from pageRequest(...) back to the producer, which
        // builds its connection from it. Two field lists deliberately coexist:
        //   - selectFields: selection ∪ extraFields, name-deduped; drives .select(...)
        //   - extraFields:  the pure extra-ordering columns; drives the cursor function, which
        //     must hash only the ordering columns, not the whole selection
        // Emitted as a class with accessors (not a Java record) because the project's JavaPoet
        // fork does not expose recordBuilder.
        var pageRequestClass = buildPageRequestClass(listOfField, listOfSortField, fieldArray);

        // --- reverseOrderBy(List<SortField<?>>) → List<SortField<?>> ---
        // Private helper used only by pageRequest(...) for backward pagination; shared by every
        // connection fetcher. Uses jOOQ's $field(), $sortOrder() and $nullOrdering() model-API
        // accessors, the only way to flip a SortField's direction without losing its expression.
        // A stated null placement is mirrored with the direction (NULLS LAST becomes NULLS FIRST),
        // so the backward query is the exact reverse of the forward one and the NULL rows end up
        // last again once trimmedResult re-reverses the page; a field with none stays without one.
        var sortOrderClass = ClassName.get("org.jooq", "SortOrder");
        var nullOrderingClass = ClassName.get("org.jooq.impl", "QOM", "NullOrdering");
        var reverseOrderBy = MethodSpec.methodBuilder("reverseOrderBy")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfSortField)
            .addParameter(listOfSortField, "orderBy")
            .addStatement("$T<$T<?>> reversed = new $T<>(orderBy.size())", ARRAY_LIST, sortField, ARRAY_LIST)
            .addCode("for ($T<?> sf : orderBy) {\n", sortField)
            .addCode("    $T<?> flipped = sf.$$field().sort(\n", sortField)
            .addCode("        sf.$$sortOrder() == $T.DESC ? $T.ASC : $T.DESC);\n",
                sortOrderClass, sortOrderClass, sortOrderClass)
            .addCode("    if (sf.$$nullOrdering() != null) {\n")
            .addCode("        flipped = flipped.$$nullOrdering(sf.$$nullOrdering() == $T.NULLS_LAST\n", nullOrderingClass)
            .addCode("            ? $T.NULLS_FIRST : $T.NULLS_LAST);\n", nullOrderingClass, nullOrderingClass)
            .addCode("    }\n")
            .addCode("    reversed.add(flipped);\n")
            .addCode("}\n")
            .addStatement("return reversed")
            .build();

        // --- pageRequest(...) → PageRequest ---
        // The shared pagination entry point for every connection fetcher:
        //   - an empty or blank after/before reads as absent, ahead of everything else, so both
        //     decodeCursor and the PageRequest the page flags read from see null
        //   - client-mistake guards (first/last mutual exclusion, negative first/last,
        //     derived-limit overflow) throw GraphitronClientException so the real message
        //     reaches the client instead of a redacted correlation-id 500
        //   - backward/pageSize/cursor derivation, cursor decode (decodeCursor) and reverse
        //     ordering for backward pagination (reverseOrderBy)
        //   - name-based merge of extraFields into the selection (rationale at the merge loop)
        // Takes no DataFetchingEnvironment (env-arg extraction stays on the fetcher side),
        // matching the purity contract on the entity-scoped *Conditions classes.
        var integer = ClassName.get(Integer.class);
        var pageRequestRef = ClassName.get("", "PageRequest");
        var pageRequestBuilder = MethodSpec.methodBuilder("pageRequest")
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
            .addCode("if (after != null && after.isBlank()) after = null;\n")
            .addCode("if (before != null && before.isBlank()) before = null;\n")
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
            .addStatement("int pageSize = backward ? last : (first != null ? first : defaultPageSize)");
        // The build's maximum, ahead of the overflow guard so a maximum turns first: MAX_VALUE
        // into a full page rather than an error. Every value is clamped alike, a client's first
        // or last and a default no client sent.
        if (maxAllowedPageSize.isPresent()) {
            pageRequestBuilder.addCode("if (pageSize > $L) pageSize = $L;\n",
                MAX_PAGE_SIZE, MAX_PAGE_SIZE);
        }
        var pageRequest = pageRequestBuilder
            // Guard the derived value PostgreSQL actually enforces: limit = pageSize + 1 >= 0.
            // At MAX_VALUE the + 1 wraps to Integer.MIN_VALUE and reaches SQL as a negative
            // LIMIT; guarding here (not per input) also covers a pathological defaultPageSize.
            .addCode("if (pageSize == Integer.MAX_VALUE)\n")
            .addCode("    throw new $T($S);\n",
                clientException, "page size must be less than 2147483647")
            .addStatement("String cursor = backward ? before : after")
            .addStatement("$T seekFields = decodeCursor(cursor, extraFields)", fieldArray)
            .addStatement("$T effectiveOrderBy = backward ? reverseOrderBy(orderBy) : orderBy", listOfSortField)
            // Matching on name (not Field.equals) avoids identity mismatches when the same
            // column arrives through different jOOQ Field instances (e.g. $project vs. the
            // ordering path).
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
        // Column-driven: one JSON array element per order column, SQL NULL as null and every other
        // value as ColumnValues.toText of the column's database value, which the column's SQL
        // DataType alone decides: Oracle's JSON_ARRAY rendering, the bytes Graphitron 9 issued
        // there. The same DataType decides how decodeCursor reads it back, so no type tag rides
        // beside it. URL-safe Base64 without padding.
        var columnTextClass = columnValuesClass.nestedClass("Text");
        var encodeCursor = MethodSpec.methodBuilder("encodeCursor")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(RECORD, "record")
            .addParameter(listOfField, "orderByColumns")
            .addStatement("$T json = new $T(\"[\")", StringBuilder.class, StringBuilder.class)
            .addCode("for (int i = 0; i < orderByColumns.size(); i++) {\n")
            .addCode("    if (i > 0) json.append(',');\n")
            .addCode("    $T column = orderByColumns.get(i);\n", fieldWildcard)
            .addCode("    Object value = databaseValue(record, column);\n")
            .addCode("    if (value == null) {\n")
            .addCode("        json.append(\"null\");\n")
            .addCode("        continue;\n")
            .addCode("    }\n")
            .addCode("    $T text = $T.toText(column.getDataType(), value);\n", columnTextClass, columnValuesClass)
            .addCode("    if (text.literal()) json.append(text.text());\n")
            .addCode("    else writeJsonString(json, text.text());\n")
            .addCode("}\n")
            .addStatement("json.append(']')")
            .addStatement("return $T.getUrlEncoder().withoutPadding().encodeToString("
                + "json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))", BASE64)
            .build();

        // --- databaseValue(Record, Field<T>) ---
        // The record's value passed back through the column's converter (jOOQ's identity converter
        // when it has none), so a converter-backed column contributes its database type's text,
        // which is what Graphitron 9's SQL-side jsonArray carried. An enum's database text is its
        // literal ('NC-17'), not the Java constant's name toString() gives, and it is the literal
        // the column's DataType parses back. Generic over the column's type so the wildcard capture
        // type-checks in Java 17.
        var t = com.palantir.javapoet.TypeVariableName.get("T");
        var databaseValue = MethodSpec.methodBuilder("databaseValue")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addTypeVariable(t)
            .returns(Object.class)
            .addParameter(RECORD, "record")
            .addParameter(ParameterizedTypeName.get(JOOQ_FIELD, t), "column")
            .addStatement("$T value = record.get(column)", t)
            .addStatement("if (value == null) return null")
            .addStatement("Object database = column.getDataType().getConverter().to(value)")
            .addStatement("return database instanceof $T ? (($T) database).getLiteral() : database",
                ENUM_TYPE, ENUM_TYPE)
            .build();

        // --- writeJsonString(StringBuilder, String) ---
        // Oracle's JSON_ARRAY string escaping, so a string element is byte-identical to Graphitron
        // 9's: the quote and the backslash, the short escapes \b \f \n \r \t, every other character
        // below U+0020 and U+007F as a four-digit escape in uppercase hex, everything else raw. No text value
        // can end an element early or read as anything but itself.
        var writeJsonString = MethodSpec.methodBuilder("writeJsonString")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(StringBuilder.class, "json")
            .addParameter(String.class, "text")
            .addStatement("json.append('\"')")
            .addCode("for (int i = 0; i < text.length(); i++) {\n")
            .addCode("    char c = text.charAt(i);\n")
            .addCode("    if (c == '\"' || c == '\\\\') json.append('\\\\').append(c);\n")
            .addCode("    else if (c == '\\b') json.append(\"\\\\b\");\n")
            .addCode("    else if (c == '\\f') json.append(\"\\\\f\");\n")
            .addCode("    else if (c == '\\n') json.append(\"\\\\n\");\n")
            .addCode("    else if (c == '\\r') json.append(\"\\\\r\");\n")
            .addCode("    else if (c == '\\t') json.append(\"\\\\t\");\n")
            .addCode("    else if (c < 0x20 || c == 0x7f) json.append(String.format(\"\\\\u%04X\", (int) c));\n")
            .addCode("    else json.append(c);\n")
            .addCode("}\n")
            .addStatement("json.append('\"')")
            .build();

        // --- readJsonArray(String) → List<Object> ---
        // Parses exactly one JSON array of scalars: a string becomes its unescaped text, a number
        // its literal text (so the column's own type does the numeric conversion and a 19-digit
        // value loses nothing), true/false a Boolean and null a null. Anything else (an object, a
        // nested array, trailing content, a bad escape, input that ends early) is malformed, and
        // every such failure is an IllegalArgumentException so it reaches decodeCursor's catch as
        // the client's mistake rather than escaping as a server fault.
        var listOfObject = ParameterizedTypeName.get(LIST_CLASS, ClassName.get(Object.class));
        var readJsonArray = MethodSpec.methodBuilder("readJsonArray")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfObject)
            .addParameter(String.class, "text")
            .addStatement("int[] at = {0}")
            .addCode("if (readJsonChar(text, at, true) != '[')\n")
            .addCode("    throw new IllegalArgumentException(\"cursor is not a JSON array\");\n")
            .addStatement("$T<Object> values = new $T<>()", LIST_CLASS, ARRAY_LIST)
            .addStatement("char c = readJsonChar(text, at, true)")
            .addCode("if (c != ']') {\n")
            .addCode("    while (true) {\n")
            .addCode("        values.add(readJsonScalar(text, at, c));\n")
            .addCode("        c = readJsonChar(text, at, true);\n")
            .addCode("        if (c == ']') break;\n")
            .addCode("        if (c != ',') throw new IllegalArgumentException(\"cursor array is malformed\");\n")
            .addCode("        c = readJsonChar(text, at, true);\n")
            .addCode("    }\n")
            .addCode("}\n")
            .addCode("while (at[0] < text.length() && \" \\t\\n\\r\".indexOf(text.charAt(at[0])) >= 0) at[0]++;\n")
            .addCode("if (at[0] != text.length())\n")
            .addCode("    throw new IllegalArgumentException(\"cursor has content after its array\");\n")
            .addStatement("return values")
            .build();

        // --- readJsonChar(String, int[], boolean) → char ---
        // The next character, past JSON whitespace when asked, advancing the position; running
        // off the end is malformed input.
        var readJsonChar = MethodSpec.methodBuilder("readJsonChar")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(char.class)
            .addParameter(String.class, "text")
            .addParameter(int[].class, "at")
            .addParameter(boolean.class, "skipWhitespace")
            .addCode("while (skipWhitespace && at[0] < text.length() && \" \\t\\n\\r\".indexOf(text.charAt(at[0])) >= 0) at[0]++;\n")
            .addCode("if (at[0] >= text.length())\n")
            .addCode("    throw new IllegalArgumentException(\"cursor ends early\");\n")
            .addStatement("return text.charAt(at[0]++)")
            .build();

        // --- readJsonScalar(String, int[], char) → Object ---
        // One array element whose first character, first, is already consumed.
        var readJsonScalar = MethodSpec.methodBuilder("readJsonScalar")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(String.class, "text")
            .addParameter(int[].class, "at")
            .addParameter(char.class, "first")
            .addCode("if (first == '\"') return readJsonString(text, at);\n")
            .addCode("if (first == 't' || first == 'f' || first == 'n') {\n")
            .addCode("    String literal = first == 't' ? \"true\" : first == 'f' ? \"false\" : \"null\";\n")
            .addCode("    if (!text.startsWith(literal, at[0] - 1))\n")
            .addCode("        throw new IllegalArgumentException(\"cursor element is malformed\");\n")
            .addCode("    at[0] += literal.length() - 1;\n")
            .addCode("    return first == 'n' ? null : Boolean.valueOf(first == 't');\n")
            .addCode("}\n")
            .addCode("if (first != '-' && (first < '0' || first > '9'))\n")
            .addCode("    throw new IllegalArgumentException(\"cursor element is not a JSON scalar\");\n")
            .addStatement("int end = at[0]")
            .addCode("while (end < text.length() && \"0123456789+-.eE\".indexOf(text.charAt(end)) >= 0) end++;\n")
            .addStatement("String number = text.substring(at[0] - 1, end)")
            .addCode("if (!number.matches(\"-?(0|[1-9][0-9]*)(\\\\.[0-9]+)?([eE][+-]?[0-9]+)?\"))\n")
            .addCode("    throw new IllegalArgumentException(\"cursor element is not a JSON number\");\n")
            .addStatement("at[0] = end")
            .addStatement("return number")
            .build();

        // --- readJsonString(String, int[]) → String ---
        // The rest of a JSON string whose opening quote is already consumed, unescaped. A
        // four-hex-digit escape is one UTF-16 unit, so a surrogate pair written as two escapes
        // comes back whole.
        var readJsonString = MethodSpec.methodBuilder("readJsonString")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "text")
            .addParameter(int[].class, "at")
            .addStatement("$T out = new $T()", StringBuilder.class, StringBuilder.class)
            .addCode("while (true) {\n")
            .addCode("    char c = readJsonChar(text, at, false);\n")
            .addCode("    if (c == '\"') return out.toString();\n")
            .addCode("    if (c < 0x20) throw new IllegalArgumentException(\"cursor string holds a control character\");\n")
            .addCode("    if (c != '\\\\') {\n")
            .addCode("        out.append(c);\n")
            .addCode("        continue;\n")
            .addCode("    }\n")
            .addCode("    char escape = readJsonChar(text, at, false);\n")
            .addCode("    if (escape == '\"' || escape == '\\\\' || escape == '/') out.append(escape);\n")
            .addCode("    else if (escape == 'b') out.append('\\b');\n")
            .addCode("    else if (escape == 'f') out.append('\\f');\n")
            .addCode("    else if (escape == 'n') out.append('\\n');\n")
            .addCode("    else if (escape == 'r') out.append('\\r');\n")
            .addCode("    else if (escape == 't') out.append('\\t');\n")
            .addCode("    else if (escape == 'u') {\n")
            .addCode("        int code = 0;\n")
            .addCode("        for (int k = 0; k < 4; k++) {\n")
            .addCode("            int digit = \"0123456789abcdef\".indexOf(Character.toLowerCase(readJsonChar(text, at, false)));\n")
            .addCode("            if (digit < 0) throw new IllegalArgumentException(\"cursor string has a bad escape\");\n")
            .addCode("            code = code * 16 + digit;\n")
            .addCode("        }\n")
            .addCode("        out.append((char) code);\n")
            .addCode("    } else {\n")
            .addCode("        throw new IllegalArgumentException(\"cursor string has a bad escape\");\n")
            .addCode("    }\n")
            .addCode("}\n")
            .build();

        // --- decodeCursor(String cursor, List<Field<?>>) → Field<?>[] ---
        // Returns DSL.noField(col) per column when cursor is null (seek no-op); pageRequest has
        // already turned an empty or blank cursor into null.
        // Otherwise reads one of two formats, chosen from the cursor string before reading it:
        //   - the JSON array encodeCursor writes (and Graphitron 9 wrote), URL-safe Base64 without
        //     padding, read by readJsonCursor;
        //   - the Graphitron 10 release candidates' format, each order column's user-side value as
        //     its toString() (U+0001 for SQL NULL) joined by U+0000 in standard Base64, read by
        //     readReleaseCandidateCursor exactly as the release candidates read it, so a cursor a
        //     client stored under one keeps its place after the upgrade.
        // The choice: the alphabet first, when it speaks (+, / or = only in standard Base64, - or
        // _ only in the URL-safe alphabet, both at once is malformed); then the decoded text, since
        // a JSON cursor opens with '[' and never holds a raw U+0000 while a release-candidate cursor
        // over two or more columns always does; and text that looks like JSON is read as JSON first,
        // falling back to the release-candidate reading only when the JSON reading fails. A cursor
        // the alphabet routed gets no second reading.
        // Accepted residue: a release-candidate cursor whose bytes are also a valid JSON cursor for
        // the same connection (a single string order column whose value is itself the text of a
        // one-element JSON array, such as ["xy"], with Base64 holding no + or / and no padding) is
        // byte-identical to the JSON cursor for xy, and the JSON reading wins, because every cursor
        // encodeCursor writes has to read right.
        // The decode body is wrapped in one try whose multi-catch classifies blame at the wire
        // boundary: Base64 decode, JSON read, strict-arity, and coercion failures are all pure
        // functions of the opaque client cursor, so they collapse to one GraphitronClientException
        // the no-channel disposition surfaces instead of redacting to a correlation-id 500. Both
        // readers throw only IllegalArgumentException or DataTypeException on malformed input. Any
        // other unchecked throw (e.g. an NPE from a buggy custom Converter) is a genuine server
        // fault and keeps propagating. Arity is strict in both directions in both formats: a cursor
        // carries exactly one element per order column, so any other count is a forged, corrupted,
        // or stale-across-schema-change cursor.
        var decodeCursor = MethodSpec.methodBuilder("decodeCursor")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ArrayTypeName.of(fieldWildcard))
            .addParameter(String.class, "cursor")
            .addParameter(listOfField, "orderByColumns")
            .addCode("if (cursor == null) {\n")
            .addCode("    $T[] seekFields = new $T[orderByColumns.size()];\n", fieldWildcard, fieldWildcard)
            .addCode("    for (int i = 0; i < orderByColumns.size(); i++)\n")
            .addCode("        seekFields[i] = $T.noField(orderByColumns.get(i));\n", DSL)
            .addCode("    return seekFields;\n")
            .addCode("}\n")
            .addCode("try {\n")
            .addCode("    boolean standard = cursor.indexOf('+') >= 0 || cursor.indexOf('/') >= 0 || cursor.indexOf('=') >= 0;\n")
            .addCode("    boolean urlSafe = cursor.indexOf('-') >= 0 || cursor.indexOf('_') >= 0;\n")
            .addCode("    if (standard && urlSafe)\n")
            .addCode("        throw new IllegalArgumentException(\"cursor mixes Base64 alphabets\");\n")
            .addCode("    if (standard)\n")
            .addCode("        return readReleaseCandidateCursor(new String($T.getDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8), orderByColumns);\n",
                BASE64)
            .addCode("    String text = new String($T.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);\n",
                BASE64)
            .addCode("    if (urlSafe) return readJsonCursor(text, orderByColumns);\n")
            .addCode("    if (text.indexOf('\\u0000') >= 0 || !text.startsWith(\"[\")) return readReleaseCandidateCursor(text, orderByColumns);\n")
            .addCode("    try {\n")
            .addCode("        return readJsonCursor(text, orderByColumns);\n")
            .addCode("    } catch (IllegalArgumentException | $T notJson) {\n", DATA_TYPE_EXCEPTION)
            .addCode("        return readReleaseCandidateCursor(text, orderByColumns);\n")
            .addCode("    }\n")
            .addCode("} catch (IllegalArgumentException | $T e) {\n", DATA_TYPE_EXCEPTION)
            .addCode("    String echo = cursor.length() > 100 ? cursor.substring(0, 100) + \"\\u2026\" : cursor;\n")
            .addCode("    throw new $T($S + echo + $S);\n",
                clientException, "cursor is not valid (was: \"", "\")")
            .addCode("}\n")
            .build();

        // --- readJsonCursor(String text, List<Field<?>>) → Field<?>[] ---
        // The decoded text of a JSON cursor, bound one element per order column: null as a typed
        // NULL, anything else through ColumnValues.fromDatabase, which parses it as the column's
        // database type and runs the converter, the inverse of encodeCursor's databaseValue. A
        // column with no SQL type has no database type to parse as, so its element, the database
        // value's text, goes to the converter as it is.
        var readJsonCursor = MethodSpec.methodBuilder("readJsonCursor")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ArrayTypeName.of(fieldWildcard))
            .addParameter(String.class, "text")
            .addParameter(listOfField, "orderByColumns")
            .addStatement("$T<Object> values = readJsonArray(text)", LIST_CLASS)
            .addCode("if (values.size() != orderByColumns.size())\n")
            .addCode("    throw new IllegalArgumentException(\"cursor arity mismatch\");\n")
            .addStatement("$T[] seekFields = new $T[orderByColumns.size()]", fieldWildcard, fieldWildcard)
            .addCode("for (int i = 0; i < orderByColumns.size(); i++) {\n")
            .addCode("    $T col = orderByColumns.get(i);\n", fieldWildcard)
            .addCode("    if (values.get(i) == null) {\n")
            .addCode("        seekFields[i] = $T.val((Object) null, col.getDataType());\n", DSL)
            .addCode("    } else {\n")
            // fromDatabase answers null for an element that is no value of the column (text its
            // database type does not parse or its converter refuses, a number wider than the
            // column); rejecting it here
            // collapses into the same client error as bad Base64 / wrong arity instead of silently
            // seeking on a null bound value. A cursor encodeCursor wrote always comes back.
            .addCode("        Object value = $T.fromDatabase(col.getDataType(), values.get(i));\n", columnValuesClass)
            .addCode("        if (value == null)\n")
            .addCode("            throw new IllegalArgumentException(\"cursor token not coercible\");\n")
            .addCode("        seekFields[i] = $T.val(value, col.getDataType());\n", DSL)
            .addCode("    }\n")
            .addCode("}\n")
            .addStatement("return seekFields")
            .build();

        // --- readReleaseCandidateCursor(String text, List<Field<?>>) → Field<?>[] ---
        // The decoded text of a cursor a Graphitron 10 release candidate issued, read as they read
        // it: split on U+0000 keeping trailing empty tokens, strict arity, U+0001 as a typed NULL,
        // every other token coerced on the column's own (converted) type. The token is the text of
        // the user-side value, record.get(column).toString(), so it is read through the converted
        // type rather than ColumnValues; an enum token is the Java constant's name, which parses only
        // where it equals the literal, exactly as before. DSL.val(token, type).getValue() is the
        // non-deprecated equivalent of the DataType.convert the release candidates called; a null
        // from it is a token that is no value of its column, rejected like the JSON reading's.
        var readReleaseCandidateCursor = MethodSpec.methodBuilder("readReleaseCandidateCursor")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ArrayTypeName.of(fieldWildcard))
            .addParameter(String.class, "text")
            .addParameter(listOfField, "orderByColumns")
            .addStatement("String[] tokens = text.split(\"\\u0000\", -1)")
            .addCode("if (tokens.length != orderByColumns.size())\n")
            .addCode("    throw new IllegalArgumentException(\"cursor arity mismatch\");\n")
            .addStatement("$T[] seekFields = new $T[orderByColumns.size()]", fieldWildcard, fieldWildcard)
            .addCode("for (int i = 0; i < orderByColumns.size(); i++) {\n")
            .addCode("    $T col = orderByColumns.get(i);\n", fieldWildcard)
            .addCode("    if (\"\\u0001\".equals(tokens[i])) {\n")
            .addCode("        seekFields[i] = $T.val((Object) null, col.getDataType());\n", DSL)
            .addCode("    } else {\n")
            .addCode("        Object value = $T.val((Object) tokens[i], col.getDataType()).getValue();\n", DSL)
            .addCode("        if (value == null)\n")
            .addCode("            throw new IllegalArgumentException(\"cursor token not coercible\");\n")
            .addCode("        seekFields[i] = $T.val(value, col.getDataType());\n", DSL)
            .addCode("    }\n")
            .addCode("}\n")
            .addStatement("return seekFields")
            .build();

        var listOfRecord = ParameterizedTypeName.get(LIST_CLASS, RECORD);

        // --- trim(List<Record>, int, boolean) → List<Record> ---
        // The page's rows: the over-fetched row dropped, re-reversed for backward pagination (the
        // backward query runs in reverse order).
        var trimMethod = MethodSpec.methodBuilder("trim")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(listOfRecord, "rows")
            .addParameter(int.class, "pageSize")
            .addParameter(boolean.class, "backward")
            .addStatement("$T raw = rows.size() <= pageSize ? rows : rows.subList(0, pageSize)", listOfRecord)
            .addCode("if (!backward) return raw;\n")
            .addStatement("$T<$T> rev = new $T<>(raw.size())", ARRAY_LIST, RECORD, ARRAY_LIST)
            .addCode("for (int i = raw.size() - 1; i >= 0; i--) rev.add(raw.get(i));\n")
            .addStatement("return rev")
            .build();

        // --- hasNextPage(List<Record>, int, String, boolean) → boolean ---
        // Forward: the query over-fetched. Backward: an after cursor was supplied.
        var hasNextPageMethod = MethodSpec.methodBuilder("hasNextPage")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(listOfRecord, "rows")
            .addParameter(int.class, "pageSize")
            .addParameter(String.class, "afterCursor")
            .addParameter(boolean.class, "backward")
            .addStatement("return backward ? afterCursor != null : rows.size() > pageSize")
            .build();

        // --- hasPreviousPage(List<Record>, int, String, boolean) → boolean ---
        // Forward: an after cursor was supplied. Backward: the query over-fetched.
        var hasPreviousPageMethod = MethodSpec.methodBuilder("hasPreviousPage")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(listOfRecord, "rows")
            .addParameter(int.class, "pageSize")
            .addParameter(String.class, "afterCursor")
            .addParameter(boolean.class, "backward")
            .addStatement("return backward ? rows.size() > pageSize : afterCursor != null")
            .build();

        // --- cursorOf(List<Field<?>>) → Function<Record, String> ---
        // The cursor of a row over the given order columns, as encodeCursor writes it.
        var cursorOfMethod = MethodSpec.methodBuilder("cursorOf")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(FUNCTION, RECORD, ClassName.get(String.class)))
            .addParameter(listOfField, "columns")
            .addStatement("return record -> encodeCursor(record, columns)")
            .build();

        var mapStringObject = ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class));
        var hashMap = ClassName.get("java.util", "LinkedHashMap");
        var graphitronContextClass = ClassName.get(outputPackage + ".schema", "GraphitronContext");

        // --- facetBuckets(DSLContext, env, base, facetConditions, facetSpecs) → buckets by facet ---
        // One UNION ALL of per-facet GROUP BY arms, each under the connection filter minus that
        // facet's own predicate (base AND every other facet's predicate), value cast to TEXT to
        // unify the arms and decoded back through the column's DataType. Each arm reads its source,
        // typed group column and count key off its FacetSpec as the producer bound them: a facet on
        // the connection's own table counts rows of its FROM, a @reference facet counts distinct
        // connection keys over that FROM inner-joined along its path, since a path that fans out
        // repeats a row once per related row and the filter's EXISTS selects it once. Lazy on
        // selection: the producer's facets closure runs it only when the client selects facets,
        // and unselected facet fields contribute no arm; an empty map when none is selected.
        //
        // Ordering happens in Java, after decode, not in SQL: the union's shared value column is
        // necessarily TEXT (heterogeneous facet columns unify on one type), so a SQL ORDER BY
        // could only sort lexicographically ("117" < "48"). The decode loop re-types every value,
        // the per-facet lists are one-entry-per-distinct-value small, and sorting there gives the
        // native order (count DESC, then Comparable value ASC, NULL bucket last; enums sort in
        // declaration order). The statement itself needs no ORDER BY at all; rows demultiplex by
        // the facet label column.
        var facetSpecClass = ClassName.get(outputPackage + ".util", CLASS_NAME, FACET_SPEC_CLASS_NAME);
        var listOfFacetSpec = ParameterizedTypeName.get(LIST_CLASS, facetSpecClass);
        var listOfEntryMap = ParameterizedTypeName.get(LIST_CLASS, mapStringObject);
        var facetsReturn = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfEntryMap);
        var selectedFieldClass = ClassName.get("graphql.schema", "SelectedField");
        var record3 = ParameterizedTypeName.get(ClassName.get("org.jooq", "Record3"),
            ClassName.get(String.class), ClassName.get(String.class), ClassName.get(Integer.class));
        var orderByStepOfRecord3 = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "SelectOrderByStep"), record3);
        var conditionClass = ClassName.get("org.jooq", "Condition");
        var jooqFieldWildcard = ParameterizedTypeName.get(JOOQ_FIELD,
            com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
        // selectedFacetSpecs: the specs whose label the client selected as an immediate field of
        // the facets object, in spec order.
        var selectedFacetSpecs = MethodSpec.methodBuilder("selectedFacetSpecs")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfFacetSpec)
            .addParameter(ENV, "env")
            .addParameter(listOfFacetSpec, "facetSpecs")
            .addStatement("$T<String> selected = new $T<>()",
                ClassName.get("java.util", "Set"), ClassName.get("java.util", "LinkedHashSet"))
            .addCode("for ($T sf : env.getSelectionSet().getImmediateFields()) {\n", selectedFieldClass)
            .addCode("    selected.add(sf.getName());\n")
            .addCode("}\n")
            .addStatement("$T<$T> specs = new $T<>()", LIST_CLASS, facetSpecClass, ARRAY_LIST)
            .addCode("for ($T f : facetSpecs) {\n", facetSpecClass)
            .addCode("    if (selected.contains(f.label())) specs.add(f);\n")
            .addCode("}\n")
            .addStatement("return specs")
            .build();

        // The env is read only for the selected immediate fields.
        var facetBucketsMethod = MethodSpec.methodBuilder("facetBuckets")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(facetsReturn)
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(ENV, "env")
            .addParameter(CONDITION, "base")
            .addParameter(ParameterizedTypeName.get(MAP, ClassName.get(String.class), CONDITION), "facetConditions")
            .addParameter(listOfFacetSpec, "facetSpecs")
            .addStatement("$T<$T> specs = selectedFacetSpecs(env, facetSpecs)", LIST_CLASS, facetSpecClass)
            .addCode("if (specs.isEmpty()) return $T.of();\n", MAP)
            .addStatement("$T union = null", orderByStepOfRecord3)
            .addCode("for ($T f : specs) {\n", facetSpecClass)
            .addCode("    $T cond = base;\n", conditionClass)
            .addCode("    for ($T<String, $T> e : facetConditions.entrySet()) {\n",
                ClassName.get("java.util", "Map", "Entry"), conditionClass)
            .addCode("        if (!e.getKey().equals(f.label())) cond = cond.and(e.getValue());\n")
            .addCode("    }\n")
            .addCode("    $T col = f.column();\n", jooqFieldWildcard)
            .addCode("    if (!f.valueNullable()) cond = cond.and(col.isNotNull());\n")
            .addCode("    $T cnt = f.countKey().length == 0 ? $T.count() : $T.countDistinct(f.countKey());\n",
                ParameterizedTypeName.get(JOOQ_FIELD, ClassName.get(Integer.class)), DSL, DSL)
            .addCode("    $T arm = dsl.select($T.inline(f.label()).as(\"facet\"),"
                + " col.cast(String.class).as(\"value\"), cnt.as(\"cnt\"))\n", orderByStepOfRecord3, DSL)
            .addCode("        .from(f.source()).where(cond).groupBy(col);\n")
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
            .addCode("    $T col = f.column();\n", jooqFieldWildcard)
            // DSL.val(Object, DataType<T>).getValue() is the non-deprecated wire→typed coercion:
            // the column's Converter applies, null in, null out (a preserved NULL bucket stays null).
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
        // decoded (typed) values is the point of sorting here rather than in SQL; see the facets
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

        // Mirrors TypeFetcherGenerator.buildGraphitronContextHelper. Emitted on this helper so
        // dsl can resolve the per-request DSLContext without leaning on a fetcher class.
        var graphitronContextShim = MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(graphitronContextClass)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($T.class)", graphitronContextClass)
            .build();

        // --- dsl(DataFetchingEnvironment) → DSLContext ---
        // The request's context, as a single-tenant producer's count and facets closures resolve
        // it when they run; a multi-tenant producer's closures capture the routed context its
        // page ran on instead, so the multi-tenant class has no such method.
        var dslMethod = MethodSpec.methodBuilder("dsl")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(ENV, "env")
            .addStatement("return graphitronContext(env).getDslContext(env)")
            .build();

        var builder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addType(pageRequestClass)
            .addType(facetSpecClass(fieldWildcard, tableWildcard))
            .addType(totalCountInterface())
            .addType(scopedClass(totalCountRef, tableWildcard))
            .addType(batchCountsClass(outputPackage, totalCountRef, tableWildcard))
            .addMethod(encodeCursor)
            .addMethod(databaseValue)
            .addMethod(writeJsonString)
            .addMethod(decodeCursor)
            .addMethod(readJsonCursor)
            .addMethod(readReleaseCandidateCursor)
            .addMethod(readJsonArray)
            .addMethod(readJsonChar)
            .addMethod(readJsonScalar)
            .addMethod(readJsonString)
            .addMethod(reverseOrderBy)
            .addMethod(pageRequest)
            .addMethod(trimMethod)
            .addMethod(hasNextPageMethod)
            .addMethod(hasPreviousPageMethod)
            .addMethod(cursorOfMethod)
            .addMethod(facetBucketsMethod)
            .addMethod(selectedFacetSpecs)
            .addMethod(compareFacetEntries)
            .addMethod(compareFacetValues);
        if (!multiTenant) {
            builder.addMethod(graphitronContextShim).addMethod(dslMethod);
        }
        maxAllowedPageSize.ifPresent(max -> builder.addField(FieldSpec
            .builder(int.class, MAX_PAGE_SIZE, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$L", max)
            .build()));
        return List.of(builder.build());
    }

    private static TypeSpec facetSpecClass(ParameterizedTypeName fieldWildcard,
                                           ParameterizedTypeName tableWildcard) {
        // FacetSpec: the runtime entry for one facet arm. The GraphQL-facing label (the facets
        // field name), the arm's source (the carrier's FROM, or that FROM inner-joined along a
        // @reference path), the typed GROUP BY column on that source, whether the facet value is
        // nullable (a non-null value appends an IS NOT NULL scrub to its arm), and the count key:
        // empty counts rows, non-empty counts distinct carrier keys (a joined source can repeat
        // a carrier row). All resolved by the launcher; the helper reads them without branching
        // on what kind of facet it is.
        var fieldArray = ArrayTypeName.of(fieldWildcard);
        return TypeSpec.classBuilder(FACET_SPEC_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addField(FieldSpec.builder(String.class, "label", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(tableWildcard, "source", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(fieldWildcard, "column", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(boolean.class, "valueNullable", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(fieldArray, "countKey", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(String.class, "label")
                .addParameter(tableWildcard, "source")
                .addParameter(fieldWildcard, "column")
                .addParameter(boolean.class, "valueNullable")
                .addParameter(fieldArray, "countKey")
                .addStatement("this.label = label")
                .addStatement("this.source = source")
                .addStatement("this.column = column")
                .addStatement("this.valueNullable = valueNullable")
                .addStatement("this.countKey = countKey")
                .build())
            .addMethod(MethodSpec.methodBuilder("label")
                .addModifiers(Modifier.PUBLIC).returns(String.class)
                .addStatement("return label").build())
            .addMethod(MethodSpec.methodBuilder("source")
                .addModifiers(Modifier.PUBLIC).returns(tableWildcard)
                .addStatement("return source").build())
            .addMethod(MethodSpec.methodBuilder("column")
                .addModifiers(Modifier.PUBLIC).returns(fieldWildcard)
                .addStatement("return column").build())
            .addMethod(MethodSpec.methodBuilder("valueNullable")
                .addModifiers(Modifier.PUBLIC).returns(boolean.class)
                .addStatement("return valueNullable").build())
            .addMethod(MethodSpec.methodBuilder("countKey")
                .addModifiers(Modifier.PUBLIC).returns(fieldArray)
                .addStatement("return countKey").build())
            .build();
    }

    private static TypeSpec totalCountInterface() {
        return TypeSpec.interfaceBuilder(TOTAL_COUNT_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("The count behind a connection's {@code totalCount}, run on the {@code DSLContext} the\n"
                + "resolver hands in. Never returns {@code null}.\n")
            .addMethod(MethodSpec.methodBuilder("count")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(Integer.class)
                .addParameter(DSL_CONTEXT, "dsl")
                .build())
            .build();
    }

    private static TypeSpec scopedClass(ClassName totalCountRef, TypeName tableWildcard) {
        return TypeSpec.classBuilder(SCOPED_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(totalCountRef)
            .addJavadoc("A root connection's count: one {@code count(*)} over the page query's own source and\n"
                + "predicate.\n")
            .addField(FieldSpec.builder(tableWildcard, "table", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(CONDITION, "condition", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tableWildcard, "table")
                .addParameter(CONDITION, "condition")
                .addStatement("this.table = table")
                .addStatement("this.condition = condition")
                .build())
            .addMethod(MethodSpec.methodBuilder("count")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Integer.class)
                .addParameter(DSL_CONTEXT, "dsl")
                .addStatement("return dsl.selectCount().from(table).where(condition).fetchOne(0, $T.class)",
                    Integer.class)
                .build())
            .build();
    }

    /**
     * The per-batch count memo, the one mutable, lazy object in the generated runtime (the
     * connections and the scatter helpers are otherwise immutable). A batched producer creates one
     * per DataLoader batch over its cursor-independent source, which projects the parent's batch
     * index for every row of every parent, and hands slot {@code i} to parent {@code i}'s
     * connection's count closure. The first slot to resolve runs {@code select idx, count(*) ... group by idx} once;
     * later slots read the filled array. An index with no group row counts 0, which is what a
     * per-parent {@code count(*)} returns for an empty connection, so a slot never returns
     * {@code null}. A failed grouped statement is kept and rethrown by every later slot, so each
     * parent's {@code totalCount} reports the failure at its own path without re-running it.
     *
     * <p>The memo leans on one invariant: every connection of a batch would count on the same
     * context, so whichever resolves first may supply the {@code DSLContext} for all. In a
     * multi-tenant build the producer captures its own routed context in every count closure of
     * the batch and a loader batch holds one tenant; in a single-tenant build there is one context
     * per request and DataLoaders are request-scoped. Synchronized because the connections of one
     * batch may resolve on different threads under an executor strategy.
     *
     * <p>Producers: {@link no.sikt.graphitron.rewrite.generators.SplitRowsMethodEmitter#buildScatterConnectionByIdxHelper()}
     * and the batched connection rows method of
     * {@link no.sikt.graphitron.rewrite.generators.MultiTablePolymorphicEmitter}.
     */
    private static TypeSpec batchCountsClass(String outputPackage, ClassName totalCountRef, TypeName tableWildcard) {
        var batchCountsRef = ClassName.get(outputPackage + ".util", CLASS_NAME, BATCH_COUNTS_CLASS_NAME);
        var fieldOfInteger = ParameterizedTypeName.get(JOOQ_FIELD, ClassName.get(Integer.class));
        var record2 = ParameterizedTypeName.get(ClassName.get("org.jooq", "Record2"),
            ClassName.get(Integer.class), ClassName.get(Integer.class));

        var slotClass = TypeSpec.classBuilder(SLOT_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(totalCountRef)
            .addJavadoc("One parent's view of the batch's grouped count.\n")
            .addField(FieldSpec.builder(batchCountsRef, "counts", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(int.class, "index", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(batchCountsRef, "counts")
                .addParameter(int.class, "index")
                .addStatement("this.counts = counts")
                .addStatement("this.index = index")
                .build())
            .addMethod(MethodSpec.methodBuilder("count")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Integer.class)
                .addParameter(DSL_CONTEXT, "dsl")
                .addStatement("return counts.count(dsl, index)")
                .build())
            .build();

        return TypeSpec.classBuilder(BATCH_COUNTS_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("The counts of one DataLoader batch, computed by one grouped statement the first time any\n"
                + "of its slots resolves {@code totalCount}, then answered from memory. The only mutable,\n"
                + "lazy object in the generated runtime: it relies on every connection of the batch counting\n"
                + "on the same {@code DSLContext}, which holds because a batch is request-scoped and, in a\n"
                + "multi-tenant build, routed to one tenant. An index with no group row counts 0; a failed\n"
                + "statement is rethrown by every later slot rather than re-run.\n")
            .addField(FieldSpec.builder(tableWildcard, "source", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(fieldOfInteger, "idx", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(int.class, "keyCount", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(int[].class, "counts", Modifier.PRIVATE).build())
            .addField(FieldSpec.builder(RuntimeException.class, "failure", Modifier.PRIVATE).build())
            .addType(slotClass)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tableWildcard, "source")
                .addParameter(fieldOfInteger, "idx")
                .addParameter(int.class, "keyCount")
                .addStatement("this.source = source")
                .addStatement("this.idx = idx")
                .addStatement("this.keyCount = keyCount")
                .build())
            .addMethod(MethodSpec.methodBuilder("slot")
                .addModifiers(Modifier.PUBLIC)
                .returns(totalCountRef)
                .addParameter(int.class, "index")
                .addStatement("return new $T(this, index)", batchCountsRef.nestedClass(SLOT_CLASS_NAME))
                .build())
            .addMethod(MethodSpec.methodBuilder("count")
                .addModifiers(Modifier.PRIVATE, Modifier.SYNCHRONIZED)
                .returns(int.class)
                .addParameter(DSL_CONTEXT, "dsl")
                .addParameter(int.class, "index")
                .addCode(CodeBlock.builder()
                    .addStatement("if (failure != null) throw failure")
                    .beginControlFlow("if (counts == null)")
                    .addStatement("int[] filled = new int[keyCount]")
                    .beginControlFlow("try")
                    .beginControlFlow("for ($T r : dsl.select(idx, $T.count()).from(source).groupBy(idx).fetch())",
                        record2, DSL)
                    .addStatement("filled[r.value1()] = r.value2()")
                    .endControlFlow()
                    .nextControlFlow("catch ($T e)", RuntimeException.class)
                    .addStatement("failure = e")
                    .addStatement("throw e")
                    .endControlFlow()
                    .addStatement("counts = filled")
                    .endControlFlow()
                    .addStatement("return counts[index]")
                    .build())
                .build())
            .build();
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

    private static void accessor(TypeSpec.Builder cls, String name, com.palantir.javapoet.TypeName type) {
        cls.addMethod(MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC)
            .returns(type)
            .addStatement("return $L", name)
            .build());
    }
}
