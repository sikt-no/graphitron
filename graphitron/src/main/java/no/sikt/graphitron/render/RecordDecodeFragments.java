package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import javax.lang.model.element.Modifier;

/**
 * The one body that turns a base64 node id into the target table's own {@code TableRecord}:
 * {@code private static <Record> decode<Record>(Object wire)}. Every host that decodes a node id into
 * a record emits this, and there are two families of them, which is why the derivation is here rather
 * than in either.
 *
 * <p>A jOOQ-record-typed {@code @service} input-bean member decodes one on {@code <Type>Fetchers}; an
 * {@code argMapping} key-column projection decodes one wherever its consumer's glue lives, which for a
 * {@code @condition} is the {@code <Root>Conditions} class that {@link ConditionGlueRenderer} builds
 * and cannot reach the legacy emitter from. Copying the body into the second host would leave two
 * spellings of one emitted form, so it moved here and the unmigrated host delegates, the same
 * arrangement {@link FetcherResult} and {@link RecordSentinel} carry.
 *
 * <p>Naming is deliberately <em>not</em> here. One generated class's private-static method namespace is
 * that class's to allocate: {@code <Type>Fetchers} resolves {@code decode*} stems across the union of
 * every record class it hosts, so two schema packages holding same-simple-named records do not collide,
 * and a conditions class has its own namespace with its own occupants. The host passes the name in.
 */
public final class RecordDecodeFragments {

    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphqlErrorException");

    private RecordDecodeFragments() {}

    /**
     * The decode helper for one node type, under the name its host allocated.
     *
     * <p>The load is one {@code fromArray} call regardless of key arity, so a composite key just names
     * N fields, and each value is coerced through its column's {@code DataType} and registered
     * {@code Converter} rather than through the deprecated {@code convert(Object)}. Statement form
     * throughout (explicit types, named locals, no {@code var}) per the "generated code is read and
     * debugged" principle.
     *
     * <p>The two failure shapes are told apart on purpose. A wire value that is not a string at all is
     * an absent or wrong-shaped argument, so the helper returns {@code null} and lets its caller decide;
     * a string that decodes to the wrong arity is a well-formed id of another type, which is a client
     * error and throws. A caller that wants the first to be an error too raises it at the call site,
     * where it knows what the value was for.
     *
     * @param name        the method name the host allocated for this record type
     * @param decode      the per-type decoder: its encoder class, wire type id, and key column list
     * @param nodeTable   the node type's own table, whose record the load materialises
     */
    public static MethodSpec decodeHelper(String name, HelperRef.Decode decode, TableRef nodeTable) {
        ClassName recordType = nodeTable.recordClass();
        int arity = decode.outputColumnShape().size();
        var body = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(recordType)
            .addParameter(Object.class, "wire")
            .beginControlFlow("if (!(wire instanceof String nodeId))")
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T values = $T.decodeValues($S, nodeId)",
                String[].class, decode.encoderClass(), decode.typeId())
            .beginControlFlow("if (values == null || values.length != $L)", arity)
            .addStatement("throw $T.newErrorException().message($S).build()", GRAPHQL_ERROR,
                "Decoded NodeId did not match the expected type for this argument")
            .endControlFlow()
            .addStatement("$T decoded = new $T()", recordType, recordType);
        var fields = CodeBlock.builder();
        for (var column : decode.outputColumnShape()) {
            fields.add(", $T.$L.$L", nodeTable.constantsClass(), nodeTable.javaFieldName(),
                column.javaName());
        }
        body.addStatement("decoded.fromArray(values$L)", fields.build());
        return body.addStatement("return decoded").build();
    }
}
