package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.render.SlotParse;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.model.derive.StoreLegacyKeyShapes.LegacyKeyShape;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.HelperRef;

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
 * through its user type (see {@link SlotParse}). The generic {@code encode} returns
 * {@code null} when any value is {@code null}, so the GraphQL field resolves to {@code null}
 * rather than emitting a malformed ID.
 *
 * <p>For each node type the generator emits static {@code encode<TypeName>} /
 * {@code decode<TypeName>} helpers, and a {@code decode<TypeName>} alone for each {@code @table}
 * type a {@code @nodeId} leaf decodes from catalog node metadata
 * ({@link GraphitronSchema#catalogNodeDecodes()}): nothing encodes such a type's id, because its
 * output side has no node id. The typeId is baked into the helper name, so call sites pass
 * typed key values, not the wire string. {@code decode<TypeName>} returns {@code null} uniformly
 * on malformed input, typeId mismatch or a key value that is not exactly one the encoder writes
 * for a value of its column (the {@code ColumnValues} exact rule, see
 * {@link ColumnValuesClassGenerator}); carrier consumers wrap that null through the
 * {@code CallSiteExtraction.NodeIdDecodeKeys} arms. Call sites resolve the helpers through
 * {@link HelperRef} references pre-computed on {@link NodeType#encodeMethod()} /
 * {@link NodeType#decodeMethod()} or minted with the catalog decode, and each decode is emitted
 * from the very reference its call sites hold, so the encoder generator and the call-site emitters
 * cannot drift on naming.
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
     * Emits the encoder class with per-Node {@code encode<TypeName>} / {@code decode<TypeName>}
     * helpers, then a {@code decode<TypeName>} for each catalog decode. {@code outputPackage}
     * locates the sibling {@code ColumnValues} every key slot is checked through, and
     * {@code legacyKeyShapes} are the type ids whose old ids {@code decodeColumns} converts.
     */
    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage,
                                          List<LegacyKeyShape> legacyKeyShapes) {
        var nodeTypes = schema.types().values().stream()
            .filter(t -> t instanceof NodeType)
            .map(t -> (NodeType) t)
            .collect(Collectors.toUnmodifiableList());
        var columnValues = ClassName.get(outputPackage + ".util", ColumnValuesClassGenerator.CLASS_NAME);
        return generate(nodeTypes, schema.catalogNodeDecodes(), columnValues, legacyKeyShapes);
    }

    private static List<TypeSpec> generate(List<NodeType> nodeTypes,
                                           List<HelperRef.Decode> catalogNodeDecodes,
                                           ClassName columnValues,
                                           List<LegacyKeyShape> legacyKeyShapes) {
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

        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(TypeName.OBJECT));
        var classWildcard = ParameterizedTypeName.get(ClassName.get(Class.class),
            WildcardTypeName.subtypeOf(TypeName.OBJECT));
        var decodeColumns = MethodSpec.methodBuilder("decodeColumns")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object[].class)
            .addParameter(String.class, "expectedTypeId")
            .addParameter(String.class, "base64Id")
            .addParameter(no.sikt.graphitron.javapoet.ArrayTypeName.of(classWildcard), "parseClasses")
            .addParameter(no.sikt.graphitron.javapoet.ArrayTypeName.of(fieldWildcard), "columns")
            .varargs(true)
            .addJavadoc("The id's key values as values of {@code columns}, in key order, or {@code null} when\n"
                + "the id is malformed, names another type, has the wrong arity, or carries a value that\n"
                + "is not exactly what {@link #encode} writes for a value of its column.\n"
                + "{@code parseClasses[i]}, when the array and the element are non-null, is the user type\n"
                + "of a converter on {@code columns[i]}, which that slot's text is parsed as.\n")
            .addCode("// An id of a type id with a legacy key shape is read in the current shape.\n")
            .beginControlFlow("if (LEGACY_KEY_SHAPES.containsKey(expectedTypeId) && expectedTypeId.equals(peekTypeId(base64Id)))")
                .addStatement("base64Id = toCurrentId(base64Id)")
            .endControlFlow()
            .addStatement("$T values = decodeValues(expectedTypeId, base64Id)", String[].class)
            .addStatement("if (values == null || values.length != columns.length) return null")
            .addStatement("return checkSlots(values, parseClasses, columns)")
            .build();

        // The per-slot check every decoded value passes, the current shape's and a legacy one's alike.
        var checkSlots = MethodSpec.methodBuilder("checkSlots")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Object[].class)
            .addParameter(String[].class, "values")
            .addParameter(ArrayTypeName.of(classWildcard), "parseClasses")
            .addParameter(ArrayTypeName.of(fieldWildcard), "columns")
            .addStatement("Object[] decoded = new Object[values.length]")
            .beginControlFlow("for (int i = 0; i < values.length; i++)")
                .addStatement("$T parseClass = parseClasses == null ? null : parseClasses[i]", classWildcard)
                .addStatement("decoded[i] = $T.exact(columns[i].getDataType(), parseClass, values[i])", columnValues)
                .beginControlFlow("if (decoded[i] == null)")
                    .addStatement("if (parseClass != null) reportFold(columns[i], parseClass, values[i])")
                    .addStatement("return null")
                .endControlFlow()
            .endControlFlow()
            .addStatement("return decoded")
            .build();

        var legacyShapeClass = ClassName.get(columnValues.packageName(), CLASS_NAME, LEGACY_SHAPE_CLASS);
        var legacyShapeType = TypeSpec.classBuilder(LEGACY_SHAPE_CLASS)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("A type id's legacy key shape: its columns in the order old ids carry their values,\n"
                + "their parse classes as {@code decodeColumns} takes them, and for each current key\n"
                + "position the legacy position its value is read from.\n")
            .addField(ArrayTypeName.of(fieldWildcard), "columns", Modifier.FINAL)
            .addField(ArrayTypeName.of(classWildcard), "parseClasses", Modifier.FINAL)
            .addField(int[].class, "keptAt", Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addParameter(ArrayTypeName.of(fieldWildcard), "columns")
                .addParameter(ArrayTypeName.of(classWildcard), "parseClasses")
                .addParameter(int[].class, "keptAt")
                .addStatement("this.columns = columns")
                .addStatement("this.parseClasses = parseClasses")
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
            legacyShapesBuilder.addStatement("shapes.put($S, new $T(new $T { $L }, $L, new int[] { $L }))",
                shape.typeId(), legacyShapeClass, ArrayTypeName.of(fieldWildcard),
                columnReferences(shape),
                SlotParse.parseClasses(shape.columns()),
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
            .addStatement("if (checkSlots(values, shape.parseClasses, shape.columns) == null) return null")
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
            .addParameter(classWildcard, "parseClass")
            .addParameter(String.class, "text")
            .addStatement("Object folded = $T.folded(column.getDataType(), parseClass, text)", columnValues)
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
            .addMethod(buildRequireColumnAgreement());

        for (NodeType nt : nodeTypes) {
            classBuilder.addMethod(buildPerTypeEncode(nt));
            classBuilder.addMethod(buildPerTypeDecode(nt.decodeMethod()));
        }
        for (HelperRef.Decode decode : catalogNodeDecodes) {
            classBuilder.addMethod(buildPerTypeDecode(decode));
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
        TypeName dataTypeWildcard = ParameterizedTypeName.get(DATA_TYPE, WildcardTypeName.subtypeOf(TypeName.OBJECT));
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
    private static MethodSpec buildPerTypeEncode(NodeType nt) {
        HelperRef.Encode ref = nt.encodeMethod();
        var b = MethodSpec.methodBuilder(ref.methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class);
        StringBuilder argList = new StringBuilder();
        for (int i = 0; i < ref.paramSignature().size(); i++) {
            ColumnRef col = ref.paramSignature().get(i);
            String paramName = "v" + i;
            b.addParameter(CatalogRefs.columnType(col), paramName);
            argList.append(", ").append(paramName);
        }
        b.addStatement("return encode($S" + argList + ")", nt.typeId());
        return b.build();
    }

    /**
     * Emits {@code static RecordN<T1..TN> decode<TypeName>(String base64Id)}, returning
     * {@code null} on malformed input, typeId mismatch, wrong arity or a key value that is not a
     * value of its column ({@code decodeColumns}), and otherwise materialising a typed
     * {@link org.jooq.Record} populated from the table's {@link org.jooq.Field} references. Every
     * fact the body needs is on the reference: the method name, the output shape, the wire type id
     * and the table.
     */
    private static MethodSpec buildPerTypeDecode(HelperRef.Decode ref) {
        TypeName recordType = ref.returnType();
        int n = ref.outputColumnShape().size();
        String tableField = ref.table().javaFieldName();
        // Each decode method references its own table's Tables class, so multi-schema codegen
        // layouts get schema-segmented references.
        ClassName tablesClass = CatalogRefs.constantsClass(ref.table());

        var b = MethodSpec.methodBuilder(ref.methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(recordType)
            .addParameter(String.class, "base64Id");

        StringBuilder fieldsList = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) fieldsList.append(", ");
            ColumnRef col = ref.outputColumnShape().get(i);
            fieldsList.append("$T.").append(tableField).append(".").append(col.javaName());
        }
        Object[] tablesRefs = new Object[n];
        for (int i = 0; i < n; i++) tablesRefs[i] = tablesClass;

        Object[] decodeArgs = new Object[n + 2];
        decodeArgs[0] = ref.typeId();
        decodeArgs[1] = SlotParse.parseClasses(ref.outputColumnShape());
        System.arraycopy(tablesRefs, 0, decodeArgs, 2, n);
        b.addStatement("Object[] values = decodeColumns($S, base64Id, $L, " + fieldsList + ")", decodeArgs);
        b.addStatement("if (values == null) return null");

        // Declared with the explicit record type rather than `var`; generated sources avoid `var`
        // (enforced by GeneratedSourcesLintTest in graphitron-sakila-example).
        b.addStatement("$T rec = $T.using($T.DEFAULT).newRecord(" + fieldsList + ")",
            prepend(tablesRefs, recordType, DSL, SQL_DIALECT));

        // Each value is already the column's canonical value, so the cast through the column's
        // own type is exact.
        for (int i = 0; i < n; i++) {
            ColumnRef col = ref.outputColumnShape().get(i);
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
