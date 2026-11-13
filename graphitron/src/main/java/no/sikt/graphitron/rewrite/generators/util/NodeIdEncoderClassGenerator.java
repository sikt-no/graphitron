package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.emit.NodeIdEncoderQuery;
import no.sikt.graphitron.emit.NodeIdHelperNames;
import no.sikt.graphitron.model.derive.StoreLegacyKeyShapes.LegacyKeyShape;
import no.sikt.graphitron.model.jooq.ColumnRef;

import javax.lang.model.element.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Generates the {@code NodeIdEncoder} utility class: encode + decode helpers for Relay node IDs,
 * emitted once per code-generation run.
 *
 * <p>Wire format (matches the legacy {@code no.sikt.graphql.NodeIdStrategy} encoding, so IDs
 * round-trip between the two generators):
 * <pre>{@code
 * "typeId:v1,v2,..."  ->  base64-url (no padding, UTF-8)
 * }</pre>
 * Commas inside values are escaped as {@code %2C}. Each value is written as its column's Java
 * type's {@code toString()} and parsed back as that type, so a converter-backed key round-trips
 * through its user type (see {@code ColumnValues.exact}). The generic {@code encode} returns
 * {@code null} when any value is {@code null}, so the GraphQL field resolves to {@code null}
 * rather than emitting a malformed ID.
 *
 * <p>For each node of the graph, read from the store by {@link NodeIdEncoderQuery}, the generator
 * emits static {@code encode<TypeName>} / {@code decode<TypeName>} helpers, a federation stub's
 * among them. The typeId is baked into the helper name, so call sites pass typed key values, not
 * the wire string. {@code decode<TypeName>} returns {@code null} uniformly on malformed input,
 * typeId mismatch or a key value that is not exactly one the encoder writes for a value of its
 * column (the {@code ColumnValues} exact rule, see {@link ColumnValuesClassGenerator}); carrier
 * consumers wrap that null through the {@code CallSiteExtraction.NodeIdDecodeKeys} arms. Every
 * call site names a helper through {@link NodeIdHelperNames}, as this does.
 *
 * <p>Every decode surface reaches a key slot through {@code decodeColumns}, which is therefore where
 * a refused slot over a converter's user type is told apart from a malformed one: when the column's
 * converter does not give the parsed value back ({@code ColumnValues.folded}), it logs a warning
 * naming the column, once per column at {@code WARN} and at {@code DEBUG} after that, so an id that
 * resolves to {@code null} because the converter is narrower than the column is diagnosable. Both
 * values in the message are client-supplied, so they are logged with control characters escaped and
 * capped in length.
 *
 * <p>A type id may carry a legacy key shape ({@link LegacyKeyShape}): the column list its ids were
 * encoded from before the key was narrowed. {@code decodeColumns} converts an id of such a type id to
 * the current shape before its arity check, through the public {@code toCurrentId}, so every decode
 * surface accepts both shapes with no call-site change and every id emitted stays in the current
 * one. A legacy id's values are each checked against its legacy column exactly as a current id's
 * are, the current key's values are picked out and re-encoded, and the rest are discarded. The
 * first conversion of each type id is logged at {@code INFO} and later ones at {@code DEBUG}, so a
 * schema owner can tell when the old ids are gone; no client value is logged.
 *
 * <p>The generated class is {@code final} with a private constructor and only static methods, so
 * consumers cannot override the encoding: a single canonical wire format across every generated
 * dispatcher is what keeps nodeIds durable across schema evolution.
 */
public class NodeIdEncoderClassGenerator {

    public static final String CLASS_NAME = "NodeIdEncoder";

    /** The nested class holding one type id's legacy key shape. */
    private static final String LEGACY_SHAPE_CLASS = "LegacyKeyShape";

    private static final ClassName BASE64      = ClassName.get(Base64.class);
    private static final ClassName CHARSETS    = ClassName.get(StandardCharsets.class);
    private static final ClassName DSL         = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName SQL_DIALECT = ClassName.get("org.jooq", "SQLDialect");
    private static final ClassName DATA_TYPE   = ClassName.get("org.jooq", "DataType");
    private static final ClassName FIELD       = ClassName.get("org.jooq", "Field");
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphqlErrorException");
    private static final ClassName OBJECTS     = ClassName.get("java.util", "Objects");
    private static final ClassName LOGGER      = ClassName.get("org.slf4j", "Logger");
    private static final ClassName LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");

    /** The longest rendering of a client-supplied value the fold warning logs. */
    private static final int LOG_SAFE_LIMIT = 64;

    /**
     * Emits the encoder class with an {@code encode<TypeName>} / {@code decode<TypeName>} pair per
     * node, in the order {@code nodes} gives them. {@code outputPackage}
     * locates the sibling {@code ColumnValues} every key slot is checked through, and
     * {@code legacyKeyShapes} are the type ids whose old ids {@code decodeColumns} converts.
     */
    public static List<TypeSpec> generate(List<NodeIdEncoderQuery.Node> nodes, String outputPackage,
                                          List<LegacyKeyShape> legacyKeyShapes) {
        var columnValues = ClassName.get(outputPackage + ".util", ColumnValuesClassGenerator.CLASS_NAME);
        var privateCtor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

        var encode = MethodSpec.methodBuilder("encode")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "typeId")
            .addParameter(Object[].class, "values")
            .varargs(true)
            .addStatement("$T sb = new StringBuilder(typeId).append(':')", StringBuilder.class)
            .beginControlFlow("for (int i = 0; i < values.length; i++)")
                .addStatement("Object v = values[i]")
                .addStatement("if (v == null) return null")
                .addStatement("if (i > 0) sb.append(',')")
                .addStatement("sb.append(v.toString().replace($S, $S))", ",", "%2C")
            .endControlFlow()
            .addStatement("return $T.getUrlEncoder().withoutPadding().encodeToString(sb.toString().getBytes($T.UTF_8))",
                BASE64, CHARSETS)
            .build();

        var peekTypeId = MethodSpec.methodBuilder("peekTypeId")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "base64Id")
            .addStatement("if (base64Id == null) return null")
            .beginControlFlow("try")
                .addStatement("$T raw = new String($T.getUrlDecoder().decode(base64Id), $T.UTF_8)",
                    String.class, BASE64, CHARSETS)
                .addStatement("int colon = raw.indexOf(':')")
                .addStatement("return colon < 0 ? null : raw.substring(0, colon)")
            .nextControlFlow("catch ($T e)", RuntimeException.class)
                .addStatement("return null")
            .endControlFlow()
            .build();

        // Public: documented runtime API returning the raw wire segments. Generated code reads ids
        // through decodeColumns instead, which checks each segment against its column.
        var decodeValues = MethodSpec.methodBuilder("decodeValues")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String[].class)
            .addParameter(String.class, "expectedTypeId")
            .addParameter(String.class, "base64Id")
            .addStatement("if (base64Id == null) return null")
            .addStatement("$T raw", String.class)
            .beginControlFlow("try")
                .addStatement("raw = new String($T.getUrlDecoder().decode(base64Id), $T.UTF_8)", BASE64, CHARSETS)
            .nextControlFlow("catch ($T e)", RuntimeException.class)
                .addStatement("return null")
            .endControlFlow()
            .addStatement("int colon = raw.indexOf(':')")
            .addStatement("if (colon < 0) return null")
            .addStatement("if (!expectedTypeId.equals(raw.substring(0, colon))) return null")
            .addStatement("$T parts = raw.substring(colon + 1).split($S, -1)", String[].class, ",")
            .beginControlFlow("for (int i = 0; i < parts.length; i++)")
                .addStatement("parts[i] = parts[i].replace($S, $S)", "%2C", ",")
            .endControlFlow()
            .addStatement("return parts")
            .build();

        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(ClassName.OBJECT));
        var decodeColumns = MethodSpec.methodBuilder("decodeColumns")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object[].class)
            .addParameter(String.class, "expectedTypeId")
            .addParameter(String.class, "base64Id")
            .addParameter(com.palantir.javapoet.ArrayTypeName.of(fieldWildcard), "columns")
            .varargs(true)
            .addJavadoc("The id's key values as values of {@code columns}, in key order, or {@code null} when\n"
                + "the id is malformed, names another type, has the wrong arity, or carries a value that\n"
                + "is not exactly what {@link #encode} writes for a value of its column.\n")
            .addCode("// An id of a type id with a legacy key shape is read in the current shape.\n")
            .beginControlFlow("if (LEGACY_KEY_SHAPES.containsKey(expectedTypeId) && expectedTypeId.equals(peekTypeId(base64Id)))")
                .addStatement("base64Id = toCurrentId(base64Id)")
            .endControlFlow()
            .addStatement("$T values = decodeValues(expectedTypeId, base64Id)", String[].class)
            .addStatement("if (values == null || values.length != columns.length) return null")
            .addStatement("return checkSlots(values, columns)")
            .build();

        // The per-slot check every decoded value passes, the current shape's and a legacy one's alike.
        var checkSlots = MethodSpec.methodBuilder("checkSlots")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object[].class)
            .addParameter(String[].class, "values")
            .addParameter(ArrayTypeName.of(fieldWildcard), "columns")
            .addStatement("Object[] decoded = new Object[values.length]")
            .beginControlFlow("for (int i = 0; i < values.length; i++)")
                .addStatement("decoded[i] = $T.exact(columns[i].getDataType(), values[i])", columnValues)
                .beginControlFlow("if (decoded[i] == null)")
                    .addStatement("reportFold(columns[i], values[i])")
                    .addStatement("return null")
                .endControlFlow()
            .endControlFlow()
            .addStatement("return decoded")
            .build();

        var legacyShapeClass = ClassName.get(columnValues.packageName(), CLASS_NAME, LEGACY_SHAPE_CLASS);
        var legacyShapeType = TypeSpec.classBuilder(LEGACY_SHAPE_CLASS)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("A type id's legacy key shape: its columns in the order old ids carry their values,\n"
                + "and for each current key position the legacy position its value is read from.\n")
            .addField(ArrayTypeName.of(fieldWildcard), "columns", Modifier.FINAL)
            .addField(int[].class, "keptAt", Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addParameter(ArrayTypeName.of(fieldWildcard), "columns")
                .addParameter(int[].class, "keptAt")
                .addStatement("this.columns = columns")
                .addStatement("this.keptAt = keptAt")
                .build())
            .build();
        var shapeMap = ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class),
            legacyShapeClass);
        var legacyShapesField = FieldSpec.builder(shapeMap, "LEGACY_KEY_SHAPES",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("legacyKeyShapes()")
            .build();
        var legacyShapesBuilder = MethodSpec.methodBuilder("legacyKeyShapes")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(shapeMap)
            .addStatement("$T shapes = new $T<>()", shapeMap, HashMap.class);
        for (LegacyKeyShape shape : legacyKeyShapes) {
            legacyShapesBuilder.addStatement("shapes.put($S, new $T(new $T { $L }, new int[] { $L }))",
                shape.typeId(), legacyShapeClass, ArrayTypeName.of(fieldWildcard),
                columnReferences(shape),
                shape.keptAt().stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        var legacyShapes = legacyShapesBuilder
            .addStatement("return $T.copyOf(shapes)", Map.class)
            .build();
        var convertedTypeIdsField = FieldSpec.builder(stringSetType(), "CONVERTED_TYPE_IDS",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.newKeySet()", ConcurrentHashMap.class)
            .build();

        var toCurrentId = MethodSpec.methodBuilder("toCurrentId")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "base64Id")
            .addJavadoc("The id in its type's current key shape: unchanged unless it is in the legacy shape of a\n"
                + "type id that declares one, the id the row's current encode produces when it is, and\n"
                + "{@code null} when it is a legacy-shape id carrying a value that is not one of its column.\n")
            .addStatement("$T typeId = peekTypeId(base64Id)", String.class)
            .addStatement("$T shape = typeId == null ? null : LEGACY_KEY_SHAPES.get(typeId)", legacyShapeClass)
            .addStatement("if (shape == null) return base64Id")
            .addStatement("$T values = decodeValues(typeId, base64Id)", String[].class)
            .addStatement("if (values == null || values.length != shape.columns.length) return base64Id")
            .addStatement("if (checkSlots(values, shape.columns) == null) return null")
            .addStatement("Object[] current = new Object[shape.keptAt.length]")
            .beginControlFlow("for (int i = 0; i < current.length; i++)")
                .addStatement("current[i] = values[shape.keptAt[i]]")
            .endControlFlow()
            .beginControlFlow("if (CONVERTED_TYPE_IDS.add(typeId))")
                .addStatement("LOGGER.info($S, typeId)",
                    "Converted a node id of type id {} from its legacy key shape; remove legacyKeyColumns once no such line is logged.")
            .nextControlFlow("else")
                .addStatement("LOGGER.debug($S, typeId)", "Converted a node id of type id {} from its legacy key shape.")
            .endControlFlow()
            .addStatement("return encode(typeId, current)")
            .build();

        var stringSet = ParameterizedTypeName.get(ClassName.get(Set.class), ClassName.get(String.class));
        var loggerField = FieldSpec.builder(LOGGER, "LOGGER", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.getLogger($L.class)", LOGGER_FACTORY, CLASS_NAME)
            .build();
        // Bounded by the key columns the generated code names: every decodeColumns caller passes
        // generated table constants.
        var foldedColumnsField = FieldSpec.builder(stringSet, "FOLDED_COLUMNS",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.newKeySet()", ConcurrentHashMap.class)
            .build();

        var reportFold = MethodSpec.methodBuilder("reportFold")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(fieldWildcard, "column")
            .addParameter(String.class, "text")
            .addStatement("Object folded = $T.folded(column.getDataType(), text)", columnValues)
            .addStatement("if (folded == null) return")
            .addStatement("$T name = column.getQualifiedName().toString()", String.class)
            .addStatement("$T message = $S\n+ $S\n+ $S", String.class,
                "Node id key column {} has a converter that does not give back the value {} (it comes back as {}), ",
                "so node ids carrying that value decode as malformed. A key column's converter must take every ",
                "value the column holds to the database type and back unchanged.")
            .beginControlFlow("if (FOLDED_COLUMNS.add(name))")
                .addStatement("LOGGER.warn(message, name, logSafe(text), logSafe(folded))")
            .nextControlFlow("else")
                .addStatement("LOGGER.debug(message, name, logSafe(text), logSafe(folded))")
            .endControlFlow()
            .build();

        var logSafe = MethodSpec.methodBuilder("logSafe")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(Object.class, "value")
            .addJavadoc("A client-supplied value as one bounded log line: control characters escaped, at most\n"
                + "$L characters.\n", LOG_SAFE_LIMIT)
            .addStatement("$T text = $T.valueOf(value)", String.class, String.class)
            .addStatement("$T out = new $T()", StringBuilder.class, StringBuilder.class)
            .beginControlFlow("for (int i = 0; i < text.length() && out.length() < $L; i++)", LOG_SAFE_LIMIT)
                .addStatement("char c = text.charAt(i)")
                .addStatement("if (c == '\\\\') out.append($S)", "\\\\")
                .addStatement("else if (c == '\\n') out.append($S)", "\\n")
                .addStatement("else if (c == '\\r') out.append($S)", "\\r")
                .addStatement("else if (c == '\\t') out.append($S)", "\\t")
                .addStatement("else if ($T.isISOControl(c)) out.append($T.format($S, (int) c))",
                    Character.class, String.class, "\\u%04x")
                .addStatement("else out.append(c)")
            .endControlFlow()
            .addStatement("return out.length() > $L ? out.substring(0, $L) + $S : out.toString()",
                LOG_SAFE_LIMIT, LOG_SAFE_LIMIT, "...")
            .build();

        // requireColumnAgreement coerces through DataType.convert(Object), which jOOQ deprecated
        // for removal in 3.20.0. The recommended replacement (Field.getConverter().from) does not
        // accept Object input, and the only public Object-to-T coercion path
        // (org.jooq.tools.Convert) is also marked for removal, so suppress here to keep consumer
        // builds clean until jOOQ ships a public successor.
        var suppressRemoval = AnnotationSpec.builder(SuppressWarnings.class)
            .addMember("value", "{$S, $S}", "deprecation", "removal")
            .build();
        var classBuilder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addAnnotation(suppressRemoval)
            .addJavadoc("Relay nodeId encode/decode helpers. Static, non-extendable\n"
                + "by design — see {@link NodeIdEncoderClassGenerator}.\n")
            .addField(loggerField)
            .addField(foldedColumnsField)
            .addField(legacyShapesField)
            .addField(convertedTypeIdsField)
            .addType(legacyShapeType)
            .addType(TypeSpec.classBuilder(NodeIdHelperNames.COLUMN_CONFLICT)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .superclass(RuntimeException.class)
                .addJavadoc("A node id's key value disagreeing with a value another writer already set on the\n"
                    + "same column of a record {@code decodeInto} is handed.\n")
                .addField(FieldSpec.builder(long.class, "serialVersionUID", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .addAnnotation(java.io.Serial.class).initializer("1L").build())
                .addMethod(MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addStatement("super(null, null, false, false)")
                    .build())
                .build())
            .addMethod(privateCtor)
            .addMethod(encode)
            .addMethod(peekTypeId)
            .addMethod(decodeValues)
            .addMethod(toCurrentId)
            .addMethod(decodeColumns)
            .addMethod(checkSlots)
            .addMethod(legacyShapes)
            .addMethod(reportFold)
            .addMethod(logSafe)
            .addMethod(buildRequireColumnAgreement())
            .addMethod(buildDecodeInto());

        for (NodeIdEncoderQuery.Node node : nodes) {
            classBuilder.addMethod(buildPerTypeEncode(node));
            classBuilder.addMethod(buildPerTypeDecode(node));
        }

        return List.of(classBuilder.build());
    }

    /**
     * Emits {@code requireColumnAgreement}, the shared value-agreement predicate: when more than
     * one writer ({@code @nodeId} decode or plain {@code @field}) lands on a single row column, it
     * throws {@code GraphqlErrorException} unless the present writers agree on the column's value.
     *
     * <p>Agreement is defined by the destination column's coercion: each value runs through the
     * column's jOOQ {@code DataType.convert} (the same coercion the real write applies) and the
     * results compare with {@code Objects.equals}, so format-variant wire values ({@code "01"},
     * {@code 1.0}, a {@code BigInteger}) collapse onto the same key while genuinely different
     * stored values still disagree. {@code conflictLabel} names the conflicting GraphQL input
     * fields, never the SQL column or the {@code @field(name:)} mapping. Call sites with more
     * than two writers emit pairwise calls against the first present writer ({@code equals} is
     * transitive).
     */
    private static MethodSpec buildRequireColumnAgreement() {
        TypeName dataTypeWildcard = ParameterizedTypeName.get(DATA_TYPE, WildcardTypeName.subtypeOf(ClassName.OBJECT));
        return MethodSpec.methodBuilder("requireColumnAgreement")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(String.class, "conflictLabel")
            .addParameter(dataTypeWildcard, "type")
            .addParameter(Object.class, "a")
            .addParameter(Object.class, "b")
            .beginControlFlow("if (!$T.equals(type.convert(a), type.convert(b)))", OBJECTS)
            .addStatement("throw $T.newErrorException().message($S + conflictLabel).build()",
                GRAPHQL_ERROR, "Conflicting values supplied for ")
            .endControlFlow()
            .build();
    }

    /**
     * Emits {@code decodeInto}: an id's key values decoded into {@code columns} of a record the caller
     * hands over, which may be the node type's own key columns or any columns of another table that
     * take them, a foreign key referencing the key among them, in key order. Answers the record, or
     * {@code null} where {@link #buildRequireColumnAgreement() decodeColumns} refuses the id.
     *
     * <p>A record several writers fill is passed to each, and its dirty flags, jOOQ's
     * {@code touched}, are the agreement check: a column no writer has set takes the value, and a
     * column one already has, its flag raised by a plain field or an earlier decode, keeps it where
     * the values agree and throws {@code ColumnConflict} where they do not, which the caller names
     * the input field in. So the caller writes plain fields first and decodes last. Each slot's text
     * is parsed as its column says, through {@code ColumnValues.exact}.
     */
    private static MethodSpec buildDecodeInto() {
        var r = com.palantir.javapoet.TypeVariableName.get("R", ClassName.get("org.jooq", "Record"));
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(ClassName.OBJECT));
        var fieldOfObject = ParameterizedTypeName.get(FIELD, ClassName.OBJECT);
        return MethodSpec.methodBuilder("decodeInto")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(r)
            .addAnnotation(com.palantir.javapoet.AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked").build())
            .returns(r)
            .addParameter(r, "into")
            .addParameter(String.class, "expectedTypeId")
            .addParameter(String.class, "base64Id")
            .addParameter(com.palantir.javapoet.ArrayTypeName.of(fieldWildcard), "columns")
            .varargs(true)
            .addStatement("$T values = decodeColumns(expectedTypeId, base64Id, columns)", Object[].class)
            .addStatement("if (values == null) return null")
            .beginControlFlow("for (int i = 0; i < columns.length; i++)")
                .addStatement("$T column = ($T) columns[i]", fieldOfObject, fieldOfObject)
                .beginControlFlow("if (into.touched(column))")
                    .beginControlFlow("if (!$T.equals(column.getDataType().convert(into.get(column)), column.getDataType().convert(values[i])))", OBJECTS)
                        .addStatement("throw new $L()", NodeIdHelperNames.COLUMN_CONFLICT)
                    .endControlFlow()
                .nextControlFlow("else")
                    .addStatement("into.set(column, values[i])")
                .endControlFlow()
            .endControlFlow()
            .addStatement("return into")
            .build();
    }

    /** The legacy columns as the table constants {@code decodeColumns} callers pass. */
    private static CodeBlock columnReferences(LegacyKeyShape shape) {
        ClassName tablesClass = CatalogRefs.constantsClass(shape.table());
        String tableField = shape.table().javaFieldName();
        return shape.columns().stream()
            .map(col -> CodeBlock.of("$T.$L.$L", tablesClass, tableField, col.javaName()))
            .collect(CodeBlock.joining(", "));
    }

    private static TypeName stringSetType() {
        return ParameterizedTypeName.get(ClassName.get(Set.class), ClassName.get(String.class));
    }

    /** Emits {@code static String encode<TypeName>(T1 v0, ..., TN vN-1)}. */
    private static MethodSpec buildPerTypeEncode(NodeIdEncoderQuery.Node node) {
        var b = MethodSpec.methodBuilder(NodeIdHelperNames.encode(node.typeName()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class);
        StringBuilder argList = new StringBuilder();
        for (int i = 0; i < node.keyColumns().size(); i++) {
            ColumnRef col = node.keyColumns().get(i);
            String paramName = "v" + i;
            b.addParameter(CatalogRefs.columnType(col), paramName);
            argList.append(", ").append(paramName);
        }
        b.addStatement("return encode($S" + argList + ")", node.typeId());
        return b.build();
    }

    /**
     * Emits {@code static RecordN<T1..TN> decode<TypeName>(String base64Id)}, returning
     * {@code null} on malformed input, typeId mismatch, wrong arity or a key value that is not a
     * value of its column ({@code decodeColumns}), and otherwise materialising a typed
     * {@link org.jooq.Record} populated from the table's {@link org.jooq.Field} references. Every
     * fact the body needs is on the node's row: the type name, the key columns, the wire type id and
     * the table.
     */
    private static MethodSpec buildPerTypeDecode(NodeIdEncoderQuery.Node node) {
        List<ColumnRef> key = node.keyColumns();
        int n = key.size();
        TypeName recordType = ParameterizedTypeName.get(ClassName.get("org.jooq", "Record" + n),
            key.stream().map(CatalogRefs::columnType).toArray(TypeName[]::new));
        String tableField = node.tableField();
        // Each decode method references its own table's Tables class, so multi-schema codegen
        // layouts get schema-segmented references.
        ClassName tablesClass = CatalogRefs.className(node.tablesClass());

        var b = MethodSpec.methodBuilder(NodeIdHelperNames.decode(node.typeName()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(recordType)
            .addParameter(String.class, "base64Id");

        StringBuilder fieldsList = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) fieldsList.append(", ");
            ColumnRef col = key.get(i);
            fieldsList.append("$T.").append(tableField).append(".").append(col.javaName());
        }
        Object[] tablesRefs = new Object[n];
        for (int i = 0; i < n; i++) tablesRefs[i] = tablesClass;

        Object[] decodeArgs = new Object[n + 1];
        decodeArgs[0] = node.typeId();
        System.arraycopy(tablesRefs, 0, decodeArgs, 1, n);
        b.addStatement("Object[] values = decodeColumns($S, base64Id, " + fieldsList + ")", decodeArgs);
        b.addStatement("if (values == null) return null");

        // Declared with the explicit record type rather than `var`; generated sources avoid `var`
        // (enforced by GeneratedSourcesLintTest in graphitron-sakila-example).
        b.addStatement("$T rec = $T.using($T.DEFAULT).newRecord(" + fieldsList + ")",
            prepend(tablesRefs, recordType, DSL, SQL_DIALECT));

        // Each value is already the column's canonical value, so the cast through the column's
        // own type is exact.
        for (int i = 0; i < n; i++) {
            ColumnRef col = key.get(i);
            b.addStatement("rec.set($T.$L.$L, $T.$L.$L.getType().cast(values[$L]))",
                tablesClass, tableField, col.javaName(),
                tablesClass, tableField, col.javaName(),
                i);
        }

        b.addStatement("return rec");
        return b.build();
    }

    /** Returns {@code [a, b, c, ...rest]} as a fresh {@code Object[]}. */
    private static Object[] prepend(Object[] rest, Object a, Object b, Object c) {
        Object[] out = new Object[rest.length + 3];
        out[0] = a;
        out[1] = b;
        out[2] = c;
        System.arraycopy(rest, 0, out, 3, rest.length);
        return out;
    }
}
