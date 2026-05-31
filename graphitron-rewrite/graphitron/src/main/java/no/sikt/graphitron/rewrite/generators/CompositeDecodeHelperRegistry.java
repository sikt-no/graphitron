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
 * Per-class collector for NodeId decode helpers. One instance is owned by
 * {@link QueryConditionsGenerator} for each emitted {@code <Root>Conditions} class. Call sites
 * that decode a NodeId argument register a helper through this collector instead of inlining the
 * decode-and-project chain; the registry deduplicates by {@link Key} so two condition methods
 * consuming the same NodeId type share one helper.
 *
 * <p>Helpers are private static methods on the host {@code <Root>Conditions} class. They take an
 * {@code Object wire} and return the decoded key in the shape the call site binds against:
 * <ul>
 *   <li>arity-1 scalar → {@code <KeyType>} (e.g. {@code Integer}); list → {@code List<KeyType>}</li>
 *   <li>arity-N scalar → {@code Row<N><T1, ..., TN>}; list → {@code List<Row<N><T1, ..., TN>>}</li>
 * </ul>
 * The wire-shape guard, decode call, and projection ({@code key.value1()} for arity-1,
 * {@code key.valuesRow()} for arity-N) are baked into a readable statement-form body, so the
 * call site collapses to {@code <name>(wireExpr)}.
 *
 * <p>Skip and throw modes get separate helpers (different bodies) — the throw arm raises a
 * {@code GraphqlErrorException} on a {@code null} decode rather than filtering it out. The
 * statement-form throw replaces the expression-only {@code Supplier}-lambda-throw trick the inline
 * emission used to need to stay an expression (R260); a developer can now breakpoint the decode
 * and read a meaningful stack frame.
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
     * method name. Handles every arity: arity-1 decoders project the single key column
     * ({@code key.value1()}); arity-N decoders project the composite tuple ({@code key.valuesRow()}).
     */
    String register(HelperRef.Decode decode, Mode mode, boolean list) {
        Key key = new Key(decode.encoderClass(), decode.methodName(), mode, list);
        String existing = helperNames.get(key);
        if (existing != null) return existing;
        String name = helperName(decode.methodName(), mode, list, decode.outputColumnShape().size());
        helperNames.put(key, name);
        helpers.put(key, buildHelper(decode, mode, list, name));
        return name;
    }

    /** All collected helper specs in registration order. Empty when nothing was registered. */
    Collection<MethodSpec> emit() {
        return helpers.values();
    }

    private static String helperName(String decodeMethod, Mode mode, boolean list, int arity) {
        // Decoder methods are uniformly named `decode<TypeName>`; strip the prefix once and apply
        // the (arity, list) suffix matrix to derive the helper name. Arity-1 projects a single key
        // (`Key`/`Keys`); arity-N projects a composite tuple row (`Row`/`Rows`).
        String typeName = decodeMethod.startsWith("decode")
            ? decodeMethod.substring("decode".length())
            : decodeMethod;
        String kind = arity == 1
            ? (list ? "Keys" : "Key")
            : (list ? "Rows" : "Row");
        String base = "decode" + typeName + kind;
        return mode == Mode.THROW ? base + "OrThrow" : base;
    }

    private static MethodSpec buildHelper(HelperRef.Decode decode, Mode mode, boolean list, String name) {
        int arity = decode.outputColumnShape().size();
        // arity-1 binds the single key column directly; arity-N binds the typed Row<N> tuple.
        TypeName elementType = arity == 1
            ? ClassName.bestGuess(decode.outputColumnShape().getFirst().columnClass())
            : typedRow(decode.outputColumnShape());
        TypeName returnType = list
            ? ParameterizedTypeName.get(ClassName.get(List.class), elementType)
            : elementType;

        var builder = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(returnType)
            .addParameter(Object.class, "wire");

        ClassName encoder = decode.encoderClass();
        String decodeMethod = decode.methodName();
        ClassName recordN = ClassName.get("org.jooq", "Record" + arity);
        ClassName objects = ClassName.get(java.util.Objects.class);
        ClassName collectors = ClassName.get(java.util.stream.Collectors.class);
        ClassName graphqlErr = ClassName.get("graphql", "GraphqlErrorException");

        if (list) {
            builder.addStatement("if (!(wire instanceof $T<?> nodeIds)) return null", List.class);
            CodeBlock.Builder chain = CodeBlock.builder()
                .add("return nodeIds.stream().map(nodeId -> $T.$L((String) nodeId))", encoder, decodeMethod);
            if (mode == Mode.THROW) {
                chain.add(".map(key -> { if (key == null) throw new $T($S); return key; })",
                    graphqlErr, MISMATCH_MESSAGE);
            } else {
                chain.add(".filter($T::nonNull)", objects);
            }
            // arity-1 projects the single key column; arity-N projects the composite tuple row.
            chain.add(arity == 1 ? ".map($T::value1)" : ".map($T::valuesRow)", recordN);
            chain.add(".collect($T.toList())", collectors);
            builder.addStatement(chain.build());
        } else {
            builder.addStatement("if (!(wire instanceof String nodeId)) return null");
            // Typed local rather than `var`: the lint guard forbids `var` in emitted sources so
            // inferred types stay searchable. Record<N> with the column tuple's java types is the
            // exact return shape of NodeIdEncoder.decode<TypeName>(String).
            TypeName recordType = typedRecord(decode.outputColumnShape());
            builder.addStatement("$T key = $T.$L(nodeId)", recordType, encoder, decodeMethod);
            String projection = arity == 1 ? "key.value1()" : "key.valuesRow()";
            if (mode == Mode.THROW) {
                builder.addStatement("if (key == null) throw new $T($S)", graphqlErr, MISMATCH_MESSAGE);
                builder.addStatement("return $L", projection);
            } else {
                builder.addStatement("return key == null ? null : $L", projection);
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

    private static TypeName typedRecord(List<ColumnRef> columns) {
        int n = columns.size();
        ClassName recordN = ClassName.get("org.jooq", "Record" + n);
        TypeName[] typeArgs = new TypeName[n];
        for (int i = 0; i < n; i++) {
            typeArgs[i] = ClassName.bestGuess(columns.get(i).columnClass());
        }
        return ParameterizedTypeName.get(recordN, typeArgs);
    }
}
