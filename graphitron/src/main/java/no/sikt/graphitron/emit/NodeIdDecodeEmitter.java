package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import no.sikt.graphitron.render.NodeIdDecodeFailure;

import javax.lang.model.element.Modifier;
import java.util.Optional;

/**
 * Emits a node id's decode from one of {@link NodeIdDecodeQuery}'s decodes: the id loaded into a jOOQ
 * record's columns through the generated encoder's {@code decodeInto}, and an id it refuses refused
 * for the input it came from.
 *
 * <pre>{@code
 * if (NodeIdEncoder.decodeInto(out, "Film", id1, Tables.INVENTORY.FILM_ID) == null) {
 *     String peeked = NodeIdEncoder.peekTypeId(id1);
 *     throw new GraphitronClientException(...);
 * }
 * }</pre>
 *
 * <p>It decodes into a record its caller hands it, a fresh one, a local several fields share, or the
 * record under construction, the caller having written the record's plain columns first so the
 * decode's agreement check sees them; and it makes the helper that decodes an argument, or a list or
 * set of them, straight into a parameter.
 */
public final class NodeIdDecodeEmitter {

    private NodeIdDecodeEmitter() {}

    private static final ClassName STRING = ClassName.get("java.lang", "String");

    /**
     * The generated runtime a node id is decoded through: the encoder whose {@code decodeInto} loads
     * a record, and the run's output package, which reaches the client error an id it answers null
     * for is refused with.
     */
    public record Runtime(ClassName nodeIdEncoder, String outputPackage) {}

    /**
     * The refusal of the {@code String} local {@code id}, the one {@link NodeIdDecodeFailure} words
     * for every decode, so a client reads the same message whichever host decoded the id.
     */
    private static CodeBlock refuse(NodeIdDecodeQuery.Decode d, String id, Runtime runtime) {
        return NodeIdDecodeFailure.throwStatement(runtime.outputPackage(), runtime.nodeIdEncoder(),
            d.typeId(), d.nodeTypeName(), id, id);
    }

    /** The record's columns the key loads into, as the generated tables class names them. */
    private static CodeBlock columns(NodeIdDecodeQuery.Decode d) {
        return CodeBlock.join(d.columnJavaNames().stream()
            .map(c -> CodeBlock.of("$T.$L.$L", SessionIdentityRows.classOf(d.tablesClass()), d.tableField(), c))
            .toList(), ", ");
    }

    /**
     * Decodes the {@code String} local {@code id} into {@code target}, a record of the decode's table
     * the caller has made, refusing an id {@code decodeInto} answers null for. A column another
     * writer already set to another value fails the request naming the input field {@code label}.
     */
    public static void into(MethodSpec.Builder method, CodeBlock target, NodeIdDecodeQuery.Decode d, String id,
                            String label, Runtime runtime) {
        method.beginControlFlow("try")
            .beginControlFlow("if ($T.decodeInto($L, $S, $L, $L) == null)",
                runtime.nodeIdEncoder(), target, d.typeId(), id, columns(d))
            .addCode(refuse(d, id, runtime))
            .endControlFlow()
            .nextControlFlow("catch ($T e)", runtime.nodeIdEncoder().nestedClass(NodeIdHelperNames.COLUMN_CONFLICT))
            .addStatement("throw $T.newErrorException().message($S).build()",
                ClassName.get("graphql", "GraphqlErrorException"), "Conflicting values supplied for " + label)
            .endControlFlow();
    }

    /**
     * Decodes the {@code String} local {@code id} into a new record of the decode's table, declared
     * as {@code decoded}, refusing an id {@code decodeInto} answers null for.
     */
    private static void fresh(MethodSpec.Builder method, NodeIdDecodeQuery.Decode d, String id, Runtime runtime) {
        var record = SessionIdentityRows.classOf(d.recordClass());
        method.addStatement("$T decoded = $T.decodeInto(new $T(), $S, $L, $L)",
                record, runtime.nodeIdEncoder(), record, d.typeId(), id, columns(d))
            .beginControlFlow("if (decoded == null)")
            .addCode(refuse(d, id, runtime))
            .endControlFlow();
    }

    /**
     * Decodes the {@code String} local {@code id} into a new record of whichever member's node type it
     * names, declared as {@code decoded} of {@code type}, a supertype of every member's record; an id
     * of no member's type, or one its type's decode answers null for, is refused naming every member.
     * {@code wire} is the {@code Object} local the id was read from, which the refusal peeks.
     */
    private static void dispatched(MethodSpec.Builder method, TypeName type, java.util.List<NodeIdDecodeQuery.Decode> ds,
                                   String id, String wire, Runtime runtime) {
        method.addStatement("$T peeked = $T.peekTypeId($L)", STRING, runtime.nodeIdEncoder(), id)
            .addStatement("$T decoded = null", type);
        for (int i = 0; i < ds.size(); i++) {
            var d = ds.get(i);
            var record = SessionIdentityRows.classOf(d.recordClass());
            method.beginControlFlow(i == 0 ? "if ($S.equals(peeked))" : "else if ($S.equals(peeked))", d.typeId())
                .addStatement("decoded = $T.decodeInto(new $T(), $S, $L, $L)",
                    runtime.nodeIdEncoder(), record, d.typeId(), id, columns(d))
                .endControlFlow();
        }
        method.beginControlFlow("if (decoded == null)")
            .addCode(NodeIdDecodeFailure.multiCandidateThrowStatement(runtime.outputPackage(), runtime.nodeIdEncoder(),
                ds.stream().map(NodeIdDecodeQuery.Decode::typeId).toList(),
                String.join(", ", ds.stream().map(NodeIdDecodeQuery.Decode::nodeTypeName).toList()), wire))
            .endControlFlow();
    }

    /**
     * The helper decoding a node id argument or member, or a list or set of them, into a parameter of
     * {@code type} as {@code decoded} states: each id into a new record of the decode's table, its key
     * taken off the record as a {@code RecordN} ({@code RECORD_N}), {@code RowN} ({@code ROW_N}) or the
     * value of the projected key column ({@code KEY_VALUE}) where the parameter wants that, and the
     * record itself otherwise; an id of several node types into whichever member's record it names
     * ({@code SUPERTYPE}). An absent id is null.
     */
    public static MethodSpec parameterHelper(String name, TypeName type, BuildQuery.Result.Decoded decoded,
                                             Runtime runtime) {
        var d = decoded.decodes().getFirst();
        var columns = columns(d);
        var taken = switch (decoded.shape()) {
            case "RECORD_N" -> CodeBlock.of("decoded.into($L)", columns);
            case "ROW_N" -> CodeBlock.of("decoded.into($L).valuesRow()", columns);
            case "KEY_VALUE" -> CodeBlock.of("decoded.get($T.$L.$L)", SessionIdentityRows.classOf(d.tablesClass()),
                d.tableField(), decoded.column().orElseThrow());
            default -> CodeBlock.of("decoded");
        };
        boolean supertype = decoded.shape().equals("SUPERTYPE");
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(type)
            .addParameter(Object.class, "wire");
        var container = decoded.erasedClass();
        boolean collection = "java.util.List".equals(container) || "java.util.Set".equals(container);
        if (!collection) {
            method.beginControlFlow("if (!(wire instanceof $T id))", STRING)
                .addStatement("return null")
                .endControlFlow();
            if (supertype) {
                dispatched(method, type, decoded.decodes(), "id", "wire", runtime);
            } else {
                fresh(method, d, "id", runtime);
            }
            return method.addStatement("return $L", taken).build();
        }
        var element = ((ParameterizedTypeName) type).typeArguments().getFirst();
        method.beginControlFlow("if (!(wire instanceof $T<?> ids))", ClassName.get("java.util", "Collection"))
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T out = new $T<>()", type,
                "java.util.List".equals(container) ? ClassName.get("java.util", "ArrayList")
                    : ClassName.get("java.util", "LinkedHashSet"))
            .beginControlFlow("for (Object element : ids)")
            .beginControlFlow("if (!(element instanceof $T id))", STRING)
            .addStatement("out.add(null)")
            .addStatement("continue")
            .endControlFlow();
        if (supertype) {
            dispatched(method, element, decoded.decodes(), "id", "element", runtime);
        } else {
            fresh(method, d, "id", runtime);
        }
        return method.addStatement("out.add($L)", taken)
            .endControlFlow()
            .addStatement("return out")
            .build();
    }
}
