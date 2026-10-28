package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.OptionalInt;

/**
 * Generates the {@code ConnectionHelper} utility class, emitted once per code-generation run.
 *
 * <p>Contains the static methods that connection-type wiring registrations call ({@code edges},
 * {@code nodes}, {@code pageInfo}, the lazy {@code totalCount} and {@code facets},
 * {@code edgeNode}, {@code edgeCursor}) plus a nested {@code Edge} class carrying a
 * {@code Record} and its cursor string.
 *
 * <p>Cursor encoding, the format Graphitron 9 issued: a JSON array with one element per order
 * column, SQL {@code NULL} as {@code null} and every other value as a JSON string of the column's
 * database value (the record's value passed back through the column's converter, an enum as its
 * literal), encoded as
 * URL-safe Base64 without padding. Decoding reads any JSON scalar, since Graphitron 9 wrote some
 * values as bare numbers, and binds each element through
 * {@code ColumnValues.fromDatabase(column.getDataType(), element)}, which parses it as the column's
 * database type and runs the converter. Returns {@code Field<?>[]} so jOOQ's
 * {@code .seek(Field<?>...)} receives correctly-typed bind values. When no cursor is present
 * (absent, empty, or blank), {@code DSL.noField(field)} is returned per column, making
 * {@code .seek()} a no-op.
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

    private static final ClassName ENV              = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RESULT           = ClassName.get("org.jooq", "Result");
    private static final ClassName RECORD           = ClassName.get("org.jooq", "Record");
    private static final ClassName JOOQ_FIELD       = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL              = ClassName.get("org.jooq.impl", "DSL");
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
     * build the two lazy SQL resolvers ({@code totalCount}, {@code facets}) read the routed
     * {@code DSLContext} off the {@code ConnectionResult} carrier, bound there by the owning
     * fetcher per its classified tenant binding, so the aggregates run against the same source the
     * page rows came from.
     *
     * <p>With a {@code maxAllowedPageSize} the class carries a {@value #MAX_PAGE_SIZE} constant and
     * {@code pageRequest} serves a larger page size at it. Nothing downstream changes: the query
     * still fetches {@code pageSize + 1} rows, so the page flags report the remainder truthfully
     * and the client pages on. Without one the class is what it always was.
     */
    public static List<TypeSpec> generate(String outputPackage, boolean multiTenant,
                                          OptionalInt maxAllowedPageSize) {
        var connectionResultClass = ClassName.get(
            outputPackage + ".util", ConnectionResultClassGenerator.CLASS_NAME);
        // Client-error marker: pageRequest's client-mistake guards throw it so the
        // no-channel disposition surfaces the real message instead of redacting.
        var clientException = ClassName.get(outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.CLASS_NAME);
        var columnValuesClass = ClassName.get(outputPackage + ".util", ColumnValuesClassGenerator.CLASS_NAME);

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
        //   - selectFields: selection ∪ extraFields, name-deduped; drives .select(...)
        //   - extraFields:  the pure extra-ordering columns; drives cursor encoding in
        //     ConnectionResult (which must hash only the ordering columns, not the whole selection)
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
        //     decodeCursor and the PageRequest the ConnectionResult page flags read from see null
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
        // value as a JSON string of the column's database value, so the column's DataType alone
        // decides how decodeCursor reads it back (no type tag beside it). URL-safe Base64 without
        // padding, the format Graphitron 9 issued.
        var encodeCursor = MethodSpec.methodBuilder("encodeCursor")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(RECORD, "record")
            .addParameter(listOfField, "orderByColumns")
            .addStatement("$T json = new $T(\"[\")", StringBuilder.class, StringBuilder.class)
            .addCode("for (int i = 0; i < orderByColumns.size(); i++) {\n")
            .addCode("    if (i > 0) json.append(',');\n")
            .addCode("    Object value = databaseValue(record, orderByColumns.get(i));\n")
            .addCode("    if (value == null) json.append(\"null\");\n")
            .addCode("    else writeJsonString(json, value.toString());\n")
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
        var t = no.sikt.graphitron.javapoet.TypeVariableName.get("T");
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
        // RFC 8259 string escaping: the quote, the backslash and every character below U+0020, so
        // no text value can end an element early or read as anything but itself.
        var writeJsonString = MethodSpec.methodBuilder("writeJsonString")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(StringBuilder.class, "json")
            .addParameter(String.class, "text")
            .addStatement("json.append('\"')")
            .addCode("for (int i = 0; i < text.length(); i++) {\n")
            .addCode("    char c = text.charAt(i);\n")
            .addCode("    if (c == '\"' || c == '\\\\') json.append('\\\\').append(c);\n")
            .addCode("    else if (c == '\\n') json.append(\"\\\\n\");\n")
            .addCode("    else if (c == '\\t') json.append(\"\\\\t\");\n")
            .addCode("    else if (c < 0x20) json.append(String.format(\"\\\\u%04x\", (int) c));\n")
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
        // Otherwise reads the JSON array encodeCursor writes (and Graphitron 9 wrote, some numbers
        // bare) and binds each element as its column's value: null as a typed NULL, anything else
        // through ColumnValues.fromDatabase, which parses it as the column's database type and runs
        // the converter, the inverse of encodeCursor's databaseValue.
        // The decode body is wrapped in one try whose multi-catch classifies blame at the wire
        // boundary: Base64 decode, JSON read, strict-arity, and coercion failures are all pure
        // functions of the opaque client cursor, so they collapse to one GraphitronClientException
        // the no-channel disposition surfaces instead of redacting to a correlation-id 500. Any
        // other unchecked throw (e.g. an NPE from a buggy custom Converter) is a genuine server
        // fault and keeps propagating. Arity is strict in both directions: encodeCursor emits
        // exactly one element per order column, so any other count is a forged, corrupted, or
        // stale-across-schema-change cursor.
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
            .addCode("    $T<Object> values = readJsonArray(new String($T.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8));\n",
                LIST_CLASS, BASE64)
            .addCode("    if (values.size() != orderByColumns.size())\n")
            .addCode("        throw new IllegalArgumentException(\"cursor arity mismatch\");\n")
            .addCode("    for (int i = 0; i < orderByColumns.size(); i++) {\n")
            .addCode("        $T col = orderByColumns.get(i);\n", fieldWildcard)
            .addCode("        if (values.get(i) == null) {\n")
            .addCode("            seekFields[i] = $T.val((Object) null, col.getDataType());\n", DSL)
            .addCode("        } else {\n")
            // fromDatabase answers null for an element that is no value of the column (text its
            // database type does not parse, a number wider than the column); rejecting it here
            // collapses into the same client error as bad Base64 / wrong arity instead of silently
            // seeking on a null bound value. A cursor encodeCursor wrote always comes back.
            .addCode("            Object value = $T.fromDatabase(col.getDataType(), values.get(i));\n", columnValuesClass)
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
        // it. The carrier's count slot decides the statement: Scoped on a root connection, one
        // grouped count shared by the batch on a batched one. Returns null when the slot is
        // absent; the only producer of such a carrier is the validator-unreachable
        // empty-participants defensive path in MultiTablePolymorphicEmitter.buildRootConnectionFetcher.
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        var graphitronContextClass = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        // Multi-tenant: read the DSLContext the owning fetcher bound on the carrier (see the
        // two-arg generate javadoc); otherwise resolve per-request via GraphitronContext.
        CodeBlock lazyDslDeclaration = multiTenant
            ? CodeBlock.builder().addStatement("$T dsl = cr.dsl()", dslContextClass).build()
            : CodeBlock.builder()
                .addStatement("$T dsl = graphitronContext(env).getDslContext(env)", dslContextClass)
                .build();
        var totalCountMethod = MethodSpec.methodBuilder("totalCount")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Integer.class)
            .addParameter(ENV, "env")
            .addStatement("$T cr = env.getSource()", connectionResultClass)
            .addCode("if (cr.totalCount() == null) return null;\n")
            .addCode(lazyDslDeclaration)
            .addStatement("return cr.totalCount().count(dsl)")
            .build();

        // --- facets(DataFetchingEnvironment) → Map<String, List<Map<String, Object>>> ---
        // The facet sibling of totalCount. One UNION ALL of per-facet GROUP BY arms, each
        // under the connection filter minus that facet's own predicate (base AND every other
        // facet's predicate), value cast to TEXT to unify the arms and decoded back through the
        // column's DataType. Each arm reads its source, typed group column and count key off its
        // FacetSpec as the launcher bound them: a facet on the carrier's own table counts rows of
        // the carrier's FROM, a @reference facet counts distinct carrier keys over that FROM
        // inner-joined along its path, since a path that fans out repeats a carrier row once per
        // related row and the filter's EXISTS selects it once. Lazy on selection like totalCount (this resolver only runs when the
        // client selects facets; unselected facet fields contribute no arm). Returns null on a
        // carrier with no facet plan (the scatter / non-faceted contract, mirroring totalCount's
        // null-(table, condition) gate) and an empty map when no facet field is selected.
        //
        // Ordering happens in Java, after decode, not in SQL: the union's shared value column is
        // necessarily TEXT (heterogeneous facet columns unify on one type), so a SQL ORDER BY
        // could only sort lexicographically ("117" < "48"). The decode loop re-types every value,
        // the per-facet lists are one-entry-per-distinct-value small, and sorting there gives the
        // native order (count DESC, then Comparable value ASC, NULL bucket last; enums sort in
        // declaration order). The statement itself needs no ORDER BY at all; rows demultiplex by
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
            com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
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
            .addCode(lazyDslDeclaration)
            .addStatement("$T union = null", orderByStepOfRecord3)
            .addCode("for ($T f : specs) {\n", facetSpecClass)
            .addCode("    $T cond = cr.facetBaseCondition();\n", conditionClass)
            .addCode("    for ($T<String, $T> e : cr.facetConditions().entrySet()) {\n",
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

        var builder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addType(edgeClass)
            .addType(pageRequestClass)
            .addMethod(encodeCursor)
            .addMethod(databaseValue)
            .addMethod(writeJsonString)
            .addMethod(decodeCursor)
            .addMethod(readJsonArray)
            .addMethod(readJsonChar)
            .addMethod(readJsonScalar)
            .addMethod(readJsonString)
            .addMethod(reverseOrderBy)
            .addMethod(pageRequest)
            .addMethod(edgesMethod)
            .addMethod(nodesMethod)
            .addMethod(pageInfoMethod)
            .addMethod(totalCountMethod)
            .addMethod(facetsMethod)
            .addMethod(compareFacetEntries)
            .addMethod(compareFacetValues)
            .addMethod(graphitronContextShim)
            .addMethod(edgeNodeMethod)
            .addMethod(edgeCursorMethod);
        maxAllowedPageSize.ifPresent(max -> builder.addField(FieldSpec
            .builder(int.class, MAX_PAGE_SIZE, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$L", max)
            .build()));
        return List.of(builder.build());
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
