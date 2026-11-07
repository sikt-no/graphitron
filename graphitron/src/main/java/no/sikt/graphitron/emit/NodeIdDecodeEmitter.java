package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import no.sikt.graphitron.render.NodeIdDecodeFailure;

import javax.lang.model.element.Modifier;

/**
 * Emits a node id's decode from one of {@link NodeIdDecodeQuery}'s decodes: the id loaded into a jOOQ
 * record's columns through the generated encoder's {@code decodeInto}, and an id it refuses refused
 * for the input it came from.
 *
 * <pre>{@code
 * if (NodeIdEncoder.decodeInto(out, "Film", id1, "filmId", null, Tables.INVENTORY.FILM_ID) == null) {
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

    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName SET = ClassName.get("java.util", "Set");
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
     * the caller has made, refusing an id {@code decodeInto} answers null for.
     */
    public static void into(MethodSpec.Builder method, CodeBlock target, NodeIdDecodeQuery.Decode d, String id,
                            String label, Runtime runtime) {
        method.beginControlFlow("if ($T.decodeInto($L, $S, $L, $S, null, $L) == null)",
                runtime.nodeIdEncoder(), target, d.typeId(), id, label, columns(d))
            .addCode(refuse(d, id, runtime))
            .endControlFlow();
    }

    /**
     * Decodes the {@code String} local {@code id} into a new record of the decode's table, declared
     * as {@code decoded}, refusing an id {@code decodeInto} answers null for.
     */
    private static void fresh(MethodSpec.Builder method, NodeIdDecodeQuery.Decode d, String id, String label,
                              Runtime runtime) {
        var record = SessionIdentityRows.classOf(d.recordClass());
        method.addStatement("$T decoded = $T.decodeInto(new $T(), $S, $L, $S, null, $L)",
                record, runtime.nodeIdEncoder(), record, d.typeId(), id, label, columns(d))
            .beginControlFlow("if (decoded == null)")
            .addCode(refuse(d, id, runtime))
            .endControlFlow();
    }

    /**
     * The helper decoding a node id argument, or a list or set of them, into a parameter of
     * {@code type}: each id into a new record of the decode's table, its key taken off the record as a
     * {@code RecordN} ({@code RECORD_N}) or {@code RowN} ({@code ROW_N}) where the parameter wants
     * that and the record itself otherwise, an absent one null.
     */
    public static MethodSpec parameterHelper(String name, TypeName type, NodeIdDecodeQuery.Decode d, String shape,
                                             String label, Runtime runtime) {
        var columns = columns(d);
        var taken = switch (shape) {
            case "RECORD_N" -> CodeBlock.of("decoded.into($L)", columns);
            case "ROW_N" -> CodeBlock.of("decoded.into($L).valuesRow()", columns);
            default -> CodeBlock.of("decoded");
        };
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(type)
            .addParameter(Object.class, "wire");
        var collection = type instanceof ParameterizedTypeName p
            && (LIST.equals(p.rawType()) || SET.equals(p.rawType())) ? (ParameterizedTypeName) type : null;
        if (collection == null) {
            method.beginControlFlow("if (!(wire instanceof $T id))", STRING)
                .addStatement("return null")
                .endControlFlow();
            fresh(method, d, "id", label, runtime);
            return method.addStatement("return $L", taken).build();
        }
        method.beginControlFlow("if (!(wire instanceof $T<?> ids))", ClassName.get("java.util", "Collection"))
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T out = new $T<>()", type,
                LIST.equals(collection.rawType()) ? ClassName.get("java.util", "ArrayList")
                    : ClassName.get("java.util", "LinkedHashSet"))
            .beginControlFlow("for (Object element : ids)")
            .beginControlFlow("if (!(element instanceof $T id))", STRING)
            .addStatement("out.add(null)")
            .addStatement("continue")
            .endControlFlow();
        fresh(method, d, "id", label, runtime);
        return method.addStatement("out.add($L)", taken)
            .endControlFlow()
            .addStatement("return out")
            .build();
    }
}
