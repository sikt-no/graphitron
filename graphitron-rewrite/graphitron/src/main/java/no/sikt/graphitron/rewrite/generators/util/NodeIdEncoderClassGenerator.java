package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
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
 * Generates the {@code NodeIdEncoder} utility class — encode + decode helpers for Relay node IDs,
 * emitted once per code-generation run alongside other rewrite output.
 *
 * <p>Wire format (matches the legacy {@code no.sikt.graphql.NodeIdStrategy} encoding so IDs
 * round-trip across the cut-over):
 * <pre>{@code
 * "typeId:v1,v2,..."  ->  base64-url (no padding, UTF-8)
 * }</pre>
 * Commas inside values are escaped as {@code %2C}. The generic {@code encode} returns
 * {@code null} when any value is {@code null} so the GraphQL field resolves to {@code null}
 * rather than emitting a malformed ID.
 *
 * <p>Per-Node helpers. For each {@code @node} type the generator emits two static helpers
 * named after the GraphQL type:
 * <ul>
 *   <li>{@code String encode<TypeName>(T1 v1, ..., TN vN)} — bakes the typeId into the helper
 *       name so call sites pass typed key values directly instead of the wire string.</li>
 *   <li>{@code RecordN<T1, ..., TN> decode<TypeName>(String base64Id)} — returns {@code null}
 *       uniformly on malformed input or typeId mismatch; carrier consumers wrap that null
 *       through the {@code CallSiteExtraction.NodeIdDecodeKeys.*} arms.</li>
 * </ul>
 *
 * <p>Call sites resolve these helpers through structurally typed {@link HelperRef} references
 * pre-computed on {@link NodeType#encodeMethod()} / {@link NodeType#decodeMethod()}, so the
 * encoder generator and the call-site emitters cannot drift on naming.
 *
 * <p>Generated as a source file rather than shipped as a library dependency. The class is
 * {@code final} with a private constructor and only static methods — consumers cannot extend
 * it to override the encoding. This is deliberate: a single canonical wire format across every
 * generated dispatcher is what makes nodeIds durable across schema evolution.
 */
public class NodeIdEncoderClassGenerator {

    public static final String CLASS_NAME = "NodeIdEncoder";

    private static final ClassName BASE64      = ClassName.get(Base64.class);
    private static final ClassName CHARSETS    = ClassName.get(StandardCharsets.class);
    private static final ClassName DSL         = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName SQL_DIALECT = ClassName.get("org.jooq", "SQLDialect");

    /**
     * Backwards-compatible no-arg generator: emits the encoder class with no per-Node helpers.
     * Retained only for callers that have not yet been threaded with the schema; production
     * generation goes through {@link #generate(GraphitronSchema, String)}.
     */
    public static List<TypeSpec> generate() {
        return generate(List.of(), null);
    }

    /** Emits the encoder class with per-Node {@code encode<TypeName>} / {@code decode<TypeName>} helpers. */
    public static List<TypeSpec> generate(GraphitronSchema schema, String jooqPackage) {
        var nodeTypes = schema.types().values().stream()
            .filter(t -> t instanceof NodeType)
            .map(t -> (NodeType) t)
            .collect(Collectors.toUnmodifiableList());
        return generate(nodeTypes, jooqPackage);
    }

    private static List<TypeSpec> generate(List<NodeType> nodeTypes, String jooqPackage) {
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

        var decodeValues = MethodSpec.methodBuilder("decodeValues")
            .addModifiers(Modifier.STATIC)
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

        // The decode helpers route the wire-format String into the column's typed slot via
        // DataType.convert(Object), which jOOQ deprecated for removal in 3.20.0. The
        // recommended replacement (Field.getConverter().from) does not accept Object input,
        // and the only public Object→T coercion path (org.jooq.tools.Convert) is itself
        // marked for removal. Until jOOQ ships a public successor, suppress here so consumer
        // builds stay clean; revisit when DataType.convert(Object) is actually removed.
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
            .addMethod(decodeValues);

        ClassName tablesClass = jooqPackage == null
            ? null
            : ClassName.get(jooqPackage, "Tables");
        for (NodeType nt : nodeTypes) {
            classBuilder.addMethod(buildPerTypeEncode(nt));
            if (tablesClass != null) {
                classBuilder.addMethod(buildPerTypeDecode(nt, tablesClass));
            }
        }

        return List.of(classBuilder.build());
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
            b.addParameter(ClassName.bestGuess(col.columnClass()), paramName);
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

        // Build an unattached typed record over the keyColumns and populate each slot through
        // the column's data-type converter (matching the legacy coerceValue path's fall-through).
        // Construction shape:
        //   var rec = DSL.using(SQLDialect.DEFAULT).newRecord(Tables.<table>.<col1>, ...);
        //   rec.set(Tables.<table>.<col1>, Tables.<table>.<col1>.getDataType().convert(values[0]));
        StringBuilder fieldsList = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) fieldsList.append(", ");
            ColumnRef col = ref.outputColumnShape().get(i);
            fieldsList.append("$T.").append(tableField).append(".").append(col.javaName());
        }
        Object[] tablesRefs = new Object[n];
        for (int i = 0; i < n; i++) tablesRefs[i] = tablesClass;

        // Build the typed record target. We declare it as the wildcard return type and rely on
        // the explicit cast at the bottom rather than `var` (generated sources avoid `var` per
        // the GeneratedSourcesLintTest contract).
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
