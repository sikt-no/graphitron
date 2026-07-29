package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;

import javax.lang.model.element.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-class collector for NodeId decode helpers, one instance per host class (see
 * {@link #collectInto}). Call sites that decode a NodeId argument register a helper through this
 * collector instead of inlining the decode-and-project chain; the registry deduplicates by
 * {@link Key} so two methods consuming the same NodeId type share one helper.
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
 * <p>Skip and throw modes get separate helpers (different bodies). The throw arm raises the
 * generated {@code GraphitronClientException} on a {@code null} decode rather than filtering it
 * out, carrying a two-branch message that distinguishes structurally-malformed input from a
 * well-formed wrong-type id (it peeks the wire prefix via {@code NodeIdEncoder.peekTypeId}).
 * The throw is statement-form, so a developer can breakpoint the decode and read a meaningful
 * stack frame.
 */
public final class CompositeDecodeHelperRegistry {

    public enum Mode { SKIP, THROW }

    record Key(ClassName encoderClass, String methodName, Mode mode, boolean list) {}

    private final Map<Key, String> helperNames = new LinkedHashMap<>();
    private final Map<Key, MethodSpec> helpers = new LinkedHashMap<>();

    /**
     * Output package of the generated code, used to reach the generated client-error type
     * {@code <outputPackage>.schema.GraphitronClientException} that a {@link Mode#THROW} helper
     * raises on a decode failure. Empty package is tolerated for the SKIP-only paths that
     * never reach the client-error reference.
     */
    private final String outputPackage;

    public CompositeDecodeHelperRegistry(String outputPackage) {
        this.outputPackage = outputPackage;
    }

    /**
     * Brackets the construct-thread-drain lifecycle so a registry can never be registered into
     * without a matching drain: constructs a fresh registry, hands it to {@code body}, then drains
     * every collected helper onto {@code classBuilder}. A dropped drain would otherwise surface
     * only as a dangling {@code decode<Type>(...)} reference and a downstream consumer compile
     * error, not a generator failure.
     *
     * <p>Used by {@link ConditionGlueRenderer} and the legacy fetcher-side hosts
     * (the projection renderer, {@code TypeFetcherGenerator}); each owns the
     * {@link TypeSpec.Builder} the helpers land on.
     */
    public static void collectInto(TypeSpec.Builder classBuilder, String outputPackage,
            java.util.function.Consumer<CompositeDecodeHelperRegistry> body) {
        var registry = new CompositeDecodeHelperRegistry(outputPackage);
        body.accept(registry);
        registry.emit().forEach(classBuilder::addMethod);
    }

    /**
     * Registers a helper for {@code (decode, mode, list)} if not already present and returns its
     * method name.
     */
    public String register(HelperRef.Decode decode, Mode mode, boolean list) {
        Key key = new Key(decode.encoderClass(), decode.methodName(), mode, list);
        String existing = helperNames.get(key);
        if (existing != null) return existing;
        String name = helperName(decode.methodName(), mode, list, decode.outputColumnShape().size());
        helperNames.put(key, name);
        helpers.put(key, buildHelper(decode, mode, list, name));
        return name;
    }

    /** All collected helper specs in registration order. Empty when nothing was registered. */
    public Collection<MethodSpec> emit() {
        return helpers.values();
    }

    /**
     * The Java type a decode helper returns for {@code (decode, list)}: the single key column's
     * type at arity 1, the typed {@code Row<N><T1, ..., TN>} above, wrapped in {@code List} on
     * the list axis. Exposed so a caller declaring a typed local for the helper's result derives
     * the type from the same shape the helper body is built from.
     */
    public static TypeName decodedType(HelperRef.Decode decode, boolean list) {
        int arity = decode.outputColumnShape().size();
        TypeName elementType = arity == 1
            ? decode.outputColumnShape().getFirst().columnType()
            : typedRow(decode.outputColumnShape());
        return list ? ParameterizedTypeName.get(ClassName.get(List.class), elementType) : elementType;
    }

    private static String helperName(String decodeMethod, Mode mode, boolean list, int arity) {
        // Decoder methods are uniformly named `decode<TypeName>`; strip the prefix and derive
        // the helper name from the (arity, list) suffix matrix below.
        String typeName = strippedTypeName(decodeMethod);
        String kind = arity == 1
            ? (list ? "Keys" : "Key")
            : (list ? "Rows" : "Row");
        String base = "decode" + typeName + kind;
        return mode == Mode.THROW ? base + "OrThrow" : base;
    }

    private MethodSpec buildHelper(HelperRef.Decode decode, Mode mode, boolean list, String name) {
        int arity = decode.outputColumnShape().size();
        TypeName elementType = arity == 1
            ? decode.outputColumnShape().getFirst().columnType()
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
        TypeName recordType = typedRecord(decode.outputColumnShape());

        if (list) {
            builder.addStatement("if (!(wire instanceof $T<?> nodeIds)) return null", List.class);
            if (mode == Mode.THROW) {
                // Decode and the null-check share one statement lambda so the offending wire value
                // (`nodeId`) is in scope at the throw; the bad element names itself in the message.
                CodeBlock.Builder b = CodeBlock.builder()
                    .add("return nodeIds.stream().map(nodeId -> {\n").indent()
                    .addStatement("$T key = $T.$L((String) nodeId)", recordType, encoder, decodeMethod)
                    .beginControlFlow("if (key == null)")
                    .add(decodeFailureThrow(decode, "(String) nodeId", "nodeId"))
                    .endControlFlow()
                    .addStatement("return key")
                    .unindent()
                    .add("})\n")
                    .add(arity == 1 ? ".map($T::value1)\n" : ".map($T::valuesRow)\n", recordN)
                    .addStatement(".collect($T.toList())", collectors);
                builder.addCode(b.build());
            } else {
                CodeBlock.Builder chain = CodeBlock.builder()
                    .add("return nodeIds.stream().map(nodeId -> $T.$L((String) nodeId))", encoder, decodeMethod)
                    .add(".filter($T::nonNull)", objects)
                    .add(arity == 1 ? ".map($T::value1)" : ".map($T::valuesRow)", recordN)
                    .add(".collect($T.toList())", collectors);
                builder.addStatement(chain.build());
            }
        } else {
            builder.addStatement("if (!(wire instanceof String nodeId)) return null");
            // Typed local rather than `var`: GeneratedSourcesLintTest forbids `var` in emitted
            // sources so inferred types stay searchable. Record<N> with the column tuple's java
            // types is the exact return shape of NodeIdEncoder.decode<TypeName>(String).
            builder.addStatement("$T key = $T.$L(nodeId)", recordType, encoder, decodeMethod);
            String projection = arity == 1 ? "key.value1()" : "key.valuesRow()";
            if (mode == Mode.THROW) {
                builder.beginControlFlow("if (key == null)");
                builder.addCode(decodeFailureThrow(decode, "nodeId", "nodeId"));
                builder.endControlFlow();
                builder.addStatement("return $L", projection);
            } else {
                builder.addStatement("return key == null ? null : $L", projection);
            }
        }
        return builder.build();
    }

    /**
     * Emits the {@link Mode#THROW} failure path for a {@code null} decode return: peek the
     * wire value's type prefix, then throw the generated {@code GraphitronClientException} carrying
     * a two-branch message that distinguishes structurally-malformed input from a well-formed
     * wrong-type id. {@code peekArg} is the wire expression fed to {@code peekTypeId} (cast to
     * {@code String} on the list path, already-{@code String} on the scalar path); {@code msgVar}
     * is the local concatenated into the message text.
     *
     * <p>The second base64 walk {@code peekTypeId} performs (re-decoding what {@code decode<Type>}
     * already discarded) is deliberate: it runs only on the error path, which is about to throw and
     * abort the field, so the redundant decode costs nothing on the success path.
     */
    private CodeBlock decodeFailureThrow(HelperRef.Decode decode, String peekArg, String msgVar) {
        ClassName clientException = ClassName.get(outputPackage + ".schema", "GraphitronClientException");
        String typeName = strippedTypeName(decode.methodName());
        return CodeBlock.builder()
            .addStatement("$T peeked = $T.peekTypeId($L)", String.class, decode.encoderClass(), peekArg)
            .addStatement("throw new $T($L)", clientException,
                failureMessageExpr(decode.typeId(), typeName, msgVar))
            .build();
    }

    /**
     * Builds the ternary message expression. {@code peeked == null} (bad base64 / no colon) and
     * {@code peeked.equals(expectedTypeId)} (right type prefix, wrong key arity) both read as
     * "malformed"; any other non-null prefix is a well-formed wrong-type id and names the type it
     * decoded to.
     */
    private static CodeBlock failureMessageExpr(String expectedTypeId, String typeName, String msgVar) {
        return CodeBlock.of(
            "peeked == null || $S.equals(peeked)\n"
          + "    ? $S + $L + $S\n"
          + "    : $S + $L + $S + peeked + $S",
            expectedTypeId,
            "Invalid node id \"", msgVar, "\" for this argument: not a valid " + typeName + " id",
            "Invalid node id \"", msgVar, "\" for this argument: decodes to type \"",
            "\", expected a " + typeName + " id");
    }

    private static String strippedTypeName(String decodeMethod) {
        return decodeMethod.startsWith("decode")
            ? decodeMethod.substring("decode".length())
            : decodeMethod;
    }

    private static TypeName typedRow(List<ColumnRef> columns) {
        int n = columns.size();
        ClassName rowN = ClassName.get("org.jooq", "Row" + n);
        TypeName[] typeArgs = new TypeName[n];
        for (int i = 0; i < n; i++) {
            typeArgs[i] = columns.get(i).columnType();
        }
        return ParameterizedTypeName.get(rowN, typeArgs);
    }

    private static TypeName typedRecord(List<ColumnRef> columns) {
        int n = columns.size();
        ClassName recordN = ClassName.get("org.jooq", "Record" + n);
        TypeName[] typeArgs = new TypeName[n];
        for (int i = 0; i < n; i++) {
            typeArgs[i] = columns.get(i).columnType();
        }
        return ParameterizedTypeName.get(recordN, typeArgs);
    }
}
