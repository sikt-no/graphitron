package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.HelperRef;

import javax.lang.model.element.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
 * Commas inside values are escaped as {@code %2C}. The generic {@code encode} returns
 * {@code null} when any value is {@code null}, so the GraphQL field resolves to {@code null}
 * rather than emitting a malformed ID.
 *
 * <p>For each {@code @node} type the generator emits static {@code encode<TypeName>} /
 * {@code decode<TypeName>} helpers; the typeId is baked into the helper name, so call sites pass
 * typed key values, not the wire string. {@code decode<TypeName>} returns {@code null} uniformly
 * on malformed input or typeId mismatch; carrier consumers wrap that null through the
 * {@code CallSiteExtraction.NodeIdDecodeKeys} arms. Call sites resolve the helpers through
 * {@link HelperRef} references pre-computed on {@link NodeType#encodeMethod()} /
 * {@link NodeType#decodeMethod()}, so the encoder generator and the call-site emitters cannot
 * drift on naming.
 *
 * <p>The generated class is {@code final} with a private constructor and only static methods, so
 * consumers cannot override the encoding: a single canonical wire format across every generated
 * dispatcher is what keeps nodeIds durable across schema evolution.
 */
public class NodeIdEncoderClassGenerator {

    public static final String CLASS_NAME = "NodeIdEncoder";

    private static final ClassName BASE64      = ClassName.get(Base64.class);
    private static final ClassName CHARSETS    = ClassName.get(StandardCharsets.class);
    private static final ClassName DSL         = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName SQL_DIALECT = ClassName.get("org.jooq", "SQLDialect");
    private static final ClassName DATA_TYPE   = ClassName.get("org.jooq", "DataType");
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphqlErrorException");
    private static final ClassName OBJECTS     = ClassName.get("java.util", "Objects");

    /**
     * No-arg variant: emits the encoder class with no per-Node helpers. Production generation
     * goes through {@link #generate(GraphitronSchema)}.
     */
    public static List<TypeSpec> generate() {
        return generate(List.of());
    }

    /** Emits the encoder class with per-Node {@code encode<TypeName>} / {@code decode<TypeName>} helpers. */
    public static List<TypeSpec> generate(GraphitronSchema schema) {
        var nodeTypes = schema.types().values().stream()
            .filter(t -> t instanceof NodeType)
            .map(t -> (NodeType) t)
            .collect(Collectors.toUnmodifiableList());
        return generate(nodeTypes);
    }

    private static List<TypeSpec> generate(List<NodeType> nodeTypes) {
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

        // Public rather than package-private: the generated input-bean record-decode helpers land
        // in the `…fetchers` package and call decodeValues directly, so the raw unpack must cross
        // the package boundary.
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

        // The decode helpers coerce the wire-format String through DataType.convert(Object),
        // which jOOQ deprecated for removal in 3.20.0. The recommended replacement
        // (Field.getConverter().from) does not accept Object input, and the only public
        // Object-to-T coercion path (org.jooq.tools.Convert) is also marked for removal, so
        // suppress here to keep consumer builds clean until jOOQ ships a public successor.
        var suppressRemoval = AnnotationSpec.builder(SuppressWarnings.class)
            .addMember("value", "{$S, $S}", "deprecation", "removal")
            .build();
        var classBuilder = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addAnnotation(suppressRemoval)
            .addJavadoc("Relay nodeId encode/decode helpers. Static, non-extendable\n"
                + "by design — see {@link NodeIdEncoderClassGenerator}.\n")
            .addMethod(privateCtor)
            .addMethod(encode)
            .addMethod(peekTypeId)
            .addMethod(decodeValues)
            .addMethod(buildRequireColumnAgreement());

        for (NodeType nt : nodeTypes) {
            classBuilder.addMethod(buildPerTypeEncode(nt));
            // Each decode method references its own table's Tables class, so multi-schema codegen
            // layouts get schema-segmented references.
            classBuilder.addMethod(buildPerTypeDecode(nt, nt.table().constantsClass()));
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
            b.addParameter(col.columnType(), paramName);
            argList.append(", ").append(paramName);
        }
        b.addStatement("return encode($S" + argList + ")", nt.typeId());
        return b.build();
    }

    /**
     * Emits {@code static RecordN<T1..TN> decode<TypeName>(String base64Id)}, returning
     * {@code null} on malformed input or typeId mismatch and otherwise materialising a typed
     * {@link org.jooq.Record} populated from the table's {@link org.jooq.Field} references.
     */
    private static MethodSpec buildPerTypeDecode(NodeType nt, ClassName tablesClass) {
        HelperRef.Decode ref = nt.decodeMethod();
        TypeName recordType = ref.returnType();
        int n = ref.outputColumnShape().size();
        String tableField = nt.table().javaFieldName();

        var b = MethodSpec.methodBuilder(ref.methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(recordType)
            .addParameter(String.class, "base64Id")
            .addStatement("$T values = decodeValues($S, base64Id)", String[].class, nt.typeId())
            .addStatement("if (values == null || values.length != $L) return null", n);

        StringBuilder fieldsList = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) fieldsList.append(", ");
            ColumnRef col = ref.outputColumnShape().get(i);
            fieldsList.append("$T.").append(tableField).append(".").append(col.javaName());
        }
        Object[] tablesRefs = new Object[n];
        for (int i = 0; i < n; i++) tablesRefs[i] = tablesClass;

        // Declared with the explicit record type rather than `var`; generated sources avoid `var`
        // (enforced by GeneratedSourcesLintTest in graphitron-sakila-example).
        b.addStatement("$T rec = $T.using($T.DEFAULT).newRecord(" + fieldsList + ")",
            prepend(tablesRefs, recordType, DSL, SQL_DIALECT));

        for (int i = 0; i < n; i++) {
            ColumnRef col = ref.outputColumnShape().get(i);
            b.addStatement("rec.set($T.$L.$L, $T.$L.$L.getDataType().convert(values[$L]))",
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
