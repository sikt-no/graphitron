package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;

import javax.lang.model.element.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-class collector for composite-key NodeId decode helpers. One instance is owned by
 * {@link QueryConditionsGenerator} for each emitted {@code <Root>Conditions} class. Call sites
 * that decode an arity > 1 NodeId into a typed {@code Row<N><...>} register a helper through
 * this collector instead of inlining the decode-and-project chain; the registry deduplicates
 * by {@link Key} so two condition methods consuming the same NodeId type share one helper.
 *
 * <p>Helpers are private static methods on the host {@code <Root>Conditions} class. They take
 * an {@code Object wire} and return either {@code Row<N><T1, ..., TN>} (scalar) or
 * {@code List<Row<N><T1, ..., TN>>} (list); the wire-shape guard, decode call, and projection
 * to {@code Row<N>} are baked into the body, so the call site collapses to
 * {@code <name>(wireExpr)}.
 *
 * <p>Skip and throw modes get separate helpers (different bodies) — the throw arm wraps a
 * {@code null} decode in a {@code GraphqlErrorException} rather than filtering it out.
 */
final class CompositeDecodeHelperRegistry {

    enum Mode { SKIP, THROW }

    record Key(ClassName encoderClass, String methodName, Mode mode, boolean list) {}

    private static final String MISMATCH_MESSAGE =
        "Decoded NodeId did not match the expected type for this argument";

    private final Map<Key, String> helperNames = new LinkedHashMap<>();
    private final Map<Key, MethodSpec> helpers = new LinkedHashMap<>();

    /**
     * Registers a helper for {@code (decode, mode, list)} if not already present and returns its
     * method name. The decoder must have {@code outputColumnShape().size() > 1} (composite-key);
     * arity-1 decoders are handled inline at the call site.
     */
    String register(HelperRef.Decode decode, Mode mode, boolean list) {
        Key key = new Key(decode.encoderClass(), decode.methodName(), mode, list);
        String existing = helperNames.get(key);
        if (existing != null) return existing;
        String name = helperName(decode.methodName(), mode, list);
        helperNames.put(key, name);
        helpers.put(key, buildHelper(decode, mode, list, name));
        return name;
    }

    /** All collected helper specs in registration order. Empty when nothing was registered. */
    Collection<MethodSpec> emit() {
        return helpers.values();
    }

    private static String helperName(String decodeMethod, Mode mode, boolean list) {
        // Decoder methods are uniformly named `decode<TypeName>`; strip the prefix once and apply
        // the (mode, list) suffix matrix to derive the helper name.
        String typeName = decodeMethod.startsWith("decode")
            ? decodeMethod.substring("decode".length())
            : decodeMethod;
        String base = "decode" + typeName + (list ? "Rows" : "Row");
        return mode == Mode.THROW ? base + "OrThrow" : base;
    }

    private static MethodSpec buildHelper(HelperRef.Decode decode, Mode mode, boolean list, String name) {
        TypeName rowType = typedRow(decode.outputColumnShape());
        TypeName returnType = list
            ? ParameterizedTypeName.get(ClassName.get(List.class), rowType)
            : rowType;

        var builder = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(returnType)
            .addParameter(Object.class, "wire");

        ClassName encoder = decode.encoderClass();
        String decodeMethod = decode.methodName();
        ClassName recordN = ClassName.get("org.jooq", "Record" + decode.outputColumnShape().size());
        ClassName objects = ClassName.get(java.util.Objects.class);
        ClassName collectors = ClassName.get(java.util.stream.Collectors.class);
        ClassName graphqlErr = ClassName.get("graphql", "GraphqlErrorException");

        if (list) {
            builder.addStatement("if (!(wire instanceof $T<?> nl)) return null", List.class);
            CodeBlock.Builder chain = CodeBlock.builder()
                .add("return nl.stream().map(s -> $T.$L((String) s))", encoder, decodeMethod);
            if (mode == Mode.THROW) {
                chain.add(".map(r -> { if (r == null) throw new $T($S); return r; })",
                    graphqlErr, MISMATCH_MESSAGE);
            } else {
                chain.add(".filter($T::nonNull)", objects);
            }
            chain.add(".map($T::valuesRow).collect($T.toList())", recordN, collectors);
            builder.addStatement(chain.build());
        } else {
            builder.addStatement("if (!(wire instanceof String s)) return null");
            builder.addStatement("var r = $T.$L(s)", encoder, decodeMethod);
            if (mode == Mode.THROW) {
                builder.addStatement("if (r == null) throw new $T($S)", graphqlErr, MISMATCH_MESSAGE);
                builder.addStatement("return r.valuesRow()");
            } else {
                builder.addStatement("return r == null ? null : r.valuesRow()");
            }
        }
        return builder.build();
    }

    private static TypeName typedRow(List<ColumnRef> columns) {
        int n = columns.size();
        ClassName rowN = ClassName.get("org.jooq", "Row" + n);
        TypeName[] typeArgs = new TypeName[n];
        for (int i = 0; i < n; i++) {
            typeArgs[i] = ClassName.bestGuess(columns.get(i).columnClass());
        }
        return ParameterizedTypeName.get(rowN, typeArgs);
    }
}
