package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Generates the {@code NodeIdEncoder} utility class — encode + decode + WHERE-builder helpers
 * for Relay node IDs, emitted once per code-generation run alongside other rewrite output.
 *
 * <p>Wire format (matches the legacy {@code no.sikt.graphql.NodeIdStrategy} encoding so IDs
 * round-trip across the cut-over):
 * <pre>{@code
 * "typeId:v1,v2,..."  ->  base64-url (no padding, UTF-8)
 * }</pre>
 * Commas inside values are escaped as {@code %2C}. {@link #encode} returns {@code null} when
 * any value is {@code null} so the GraphQL field resolves to {@code null} rather than emitting
 * a malformed ID.
 *
 * <p>{@link #peekTypeId} and {@link #hasIds} swallow malformed input as {@code null} / empty
 * conditions: the Relay {@code Query.node(id:)} contract says "if no such object exists, the
 * field returns null", and decoding errors are not exposed to clients.
 *
 * <p>Generated as a source file rather than shipped as a library dependency. The class is
 * {@code final} with a private constructor and only static methods — consumers cannot extend
 * it to override the encoding. This is deliberate: a single canonical wire format across every
 * generated dispatcher is what makes nodeIds durable across schema evolution (see plan-nodeid-
 * directives.md "Durability and opacity").
 */
public class NodeIdEncoderClassGenerator {

    public static final String CLASS_NAME = "NodeIdEncoder";

    private static final ClassName BASE64    = ClassName.get(Base64.class);
    private static final ClassName CHARSETS  = ClassName.get(StandardCharsets.class);
    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName FIELD     = ClassName.get("org.jooq", "Field");
    private static final ClassName ROW_N     = ClassName.get("org.jooq", "RowN");
    private static final ClassName DSL       = ClassName.get("org.jooq.impl", "DSL");

    public static List<TypeSpec> generate() {
        var privateCtor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var fieldArray = no.sikt.graphitron.javapoet.ArrayTypeName.of(fieldWildcard);
        var collectionOfString = ParameterizedTypeName.get(ClassName.get(Collection.class), ClassName.get(String.class));

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

        // Returns the typeId prefix from a base64-encoded id, or null if the input is malformed.
        // Used by Query.node dispatch to route to the correct table without a full unpack.
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

        // Decodes a base64 id into its CSV value parts, or null when the input is malformed or
        // the embedded typeId does not match the expected one. Package-private and static —
        // callers within the generated package use this to build hasIds rows.
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

        // Coerces a CSV value to the jOOQ field's declared Java type. Mirrors the legacy
        // NodeIdStrategy.getFieldValue special cases for OffsetDateTime / LocalDate, falling
        // through to the field's data-type converter for everything else.
        var coerceValue = MethodSpec.methodBuilder("coerceValue")
            .addModifiers(Modifier.STATIC)
            .returns(Object.class)
            .addParameter(fieldWildcard, "field")
            .addParameter(String.class, "value")
            .addStatement("$T<?> type = field.getDataType().getType()", Class.class)
            .addStatement("if (type.isAssignableFrom($T.class)) return $T.parse(value)",
                ClassName.get(OffsetDateTime.class), ClassName.get(OffsetDateTime.class))
            .addStatement("if (type.isAssignableFrom($T.class)) return $T.parse(value)",
                ClassName.get(LocalDate.class), ClassName.get(LocalDate.class))
            .addStatement("return field.getDataType().convert(value)")
            .build();

        // Single-id WHERE condition. Delegates to hasIds. Garbage / typeId-mismatch input
        // collapses to noCondition() (the row never matches, the resolver returns null).
        var hasId = MethodSpec.methodBuilder("hasId")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(String.class, "typeId")
            .addParameter(String.class, "base64Id")
            .addParameter(fieldArray, "keyColumns")
            .varargs(true)
            .addStatement("return hasIds(typeId, $T.singletonList(base64Id), keyColumns)",
                ClassName.get("java.util", "Collections"))
            .build();

        // Multi-id WHERE condition. Decodes each id, coerces each CSV value to its column's
        // type, builds an IN clause over the resulting rows. Ids that fail to decode or whose
        // typeId does not match are silently skipped — callers should never see a query error
        // for a malformed user-supplied id.
        var arrayListOfRowN = ParameterizedTypeName.get(ClassName.get(ArrayList.class), ROW_N);
        var hasIds = MethodSpec.methodBuilder("hasIds")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(String.class, "typeId")
            .addParameter(collectionOfString, "base64Ids")
            .addParameter(fieldArray, "keyColumns")
            .varargs(true)
            .addStatement("$T rows = new $T()", arrayListOfRowN, arrayListOfRowN)
            .beginControlFlow("for (String id : base64Ids)")
                .addStatement("String[] values = decodeValues(typeId, id)")
                .addStatement("if (values == null || values.length != keyColumns.length) continue")
                .addStatement("Object[] coerced = new Object[values.length]")
                .beginControlFlow("for (int i = 0; i < values.length; i++)")
                    .addStatement("coerced[i] = coerceValue(keyColumns[i], values[i])")
                .endControlFlow()
                .addStatement("rows.add($T.row(coerced))", DSL)
            .endControlFlow()
            .addStatement("if (rows.isEmpty()) return $T.noCondition()", DSL)
            .addStatement("return $T.row(keyColumns).in(rows)", DSL)
            .build();

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Relay nodeId encode/decode/WHERE-builder helpers. Static, non-extendable\n"
                + "by design — see {@link NodeIdEncoderClassGenerator}.\n")
            .addMethod(privateCtor)
            .addMethod(encode)
            .addMethod(peekTypeId)
            .addMethod(hasId)
            .addMethod(hasIds)
            .addMethod(decodeValues)
            .addMethod(coerceValue)
            .build();

        return List.of(spec);
    }
}

