package no.sikt.graphitron.emit;

import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.render.NodeIdDecodeFailure;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Emits the node lookup's judgement of an id, from {@link NodeQuery}'s nodes: the typename of the node
 * served by id the id names, {@code null} for an id of any other type, and the client error for an id
 * that is not well formed.
 *
 * <pre>{@code
 * private static String typenameOf(String id) {
 *     String typeId = NodeIdEncoder.peekTypeId(id);
 *     if (typeId == null || typeId.isEmpty()) {
 *         throw new GraphitronClientException("Invalid node id \"" + id + "\" for this argument: not a valid node id");
 *     }
 *     if ("Customer".equals(typeId)) {
 *         if (NodeIdEncoder.decodeCustomer(id) == null) {
 *             throw new GraphitronClientException("Invalid node id \"" + id + "\" for this argument: not a valid Customer id");
 *         }
 *         return "Customer";
 *     }
 *     return null;
 * }
 * }</pre>
 *
 * <p>Well formed has two tiers: the type prefix decodes at all, and, where it is a served node's, the key
 * decodes against that node's key columns through the encoder's own {@code decode<TypeName>} helper,
 * the decode every other reader of that node's ids makes. A type id naming no served node is not a
 * judgement on the id: another subgraph may own it, so it answers {@code null}. The messages are
 * {@link NodeIdDecodeFailure}'s, so a client reads the text a {@code @nodeId} argument gives.
 */
public final class NodeIdJudgement {

    /** The emitted method's name. */
    public static final String METHOD = "typenameOf";

    private NodeIdJudgement() {}

    /** The judging method, a private static member of the class it is added to. */
    public static MethodSpec typenameOf(List<NodeQuery.Node> nodes, NodeIdDecodeEmitter.Runtime runtime) {
        var method = MethodSpec.methodBuilder(METHOD)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(String.class, "id")
            .addJavadoc("The typename of the node served by id that {@code id} names, or {@code null} for a\n"
                + "well-formed id of a type this subgraph does not serve by id. Throws the client error for an\n"
                + "id whose type prefix does not decode, and for an id of a served node whose key does not\n"
                + "decode against that node's key columns. {@code id} is not null.\n")
            .addStatement("$T typeId = $T.peekTypeId(id)", String.class, runtime.nodeIdEncoder())
            .beginControlFlow("if (typeId == null || typeId.isEmpty())")
            .addCode(NodeIdDecodeFailure.undecodableThrowStatement(runtime.outputPackage(), "id"))
            .endControlFlow();
        for (var node : nodes) {
            method.beginControlFlow("if ($S.equals(typeId))", node.typeId())
                .beginControlFlow("if ($T.$L(id) == null)", runtime.nodeIdEncoder(),
                    NodeIdHelperNames.decode(node.typeName()))
                .addCode(NodeIdDecodeFailure.malformedThrowStatement(runtime.outputPackage(), node.typeName(), "id"))
                .endControlFlow()
                .addStatement("return $S", node.typeName())
                .endControlFlow();
        }
        return method.addStatement("return null").build();
    }
}
