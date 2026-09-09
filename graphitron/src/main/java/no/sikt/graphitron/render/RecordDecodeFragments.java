package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.model.jooq.TableRef;

import javax.lang.model.element.Modifier;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * The one body that turns a base64 node id into the target table's own {@code TableRecord}:
 * {@code private static <Record> decode<Record>(Object wire)}. Every host that decodes a node id into
 * a record emits this, and there are two families of them, which is why the derivation is here rather
 * than in either.
 *
 * <p>Three emitted forms come off the one body below: the plain form, the client-error form, and the
 * null-returning form a polymorphic slot's per-candidate arm needs. Beside them sits the container
 * helper a polymorphic slot calls, which composes the null-returning form per candidate with the
 * multi-candidate failure {@link NodeIdDecodeFailure} spells.
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
        return decodeHelper(name, decode.encoderClass(), decode.typeId(),
            decode.outputColumnShape(), nodeTable);
    }

    /**
     * The same body with the client-error failure the key helpers raise, for a host that can reach
     * the generated error type and knows what to call the node type.
     *
     * <p>The two failure forms are a parameter rather than a fork inside the body because they
     * differ in what the host can reach, not in what the decode means. A conditions class hosting an
     * {@code argMapping} key-column projection sits beside {@link CompositeDecodeHelperRegistry}'s
     * key helpers reading the same wire value at a different grain, so one bad id has to fail
     * identically at both or a client learns that one spelling of a filter validates its ids and
     * another does not. A {@code <Type>Fetchers} class hosting an input-bean member decode is a
     * different consumer at a different boundary and keeps the plain form above.
     *
     * @param outputPackage the run's output package, which is how the generated client-error type is
     *                      reached
     * @param nodeTypeName  the node type as the failure message names it to the client
     */
    public static MethodSpec decodeHelper(String name, ClassName encoderClass, String typeId,
            String nodeTypeName, java.util.List<no.sikt.graphitron.model.jooq.ColumnRef> keyColumns,
            TableRef nodeTable, String outputPackage) {
        return decodeHelper(name, encoderClass, typeId, keyColumns, nodeTable,
            NodeIdDecodeFailure.throwStatement(outputPackage, encoderClass, typeId, nodeTypeName,
                "nodeId", "nodeId"));
    }

    /**
     * The same body from the facts themselves rather than from a model reference, which is what a
     * store-sourced caller has: the encoder class it minted from its own configuration, the wire type
     * id and key column list off a command row, and the node table beside them. The overload above
     * unpacks a {@link HelperRef.Decode} into exactly this call, so the two cannot emit different
     * bodies; it stays for the input-bean family, whose refs the walk still mints.
     *
     * @param name         the method name the host allocated for this record type
     * @param encoderClass the generated node-id encoder the decode goes through
     * @param typeId       the wire type id the encoded id carries
     * @param keyColumns   the node type's key columns in key order, the shape the load names
     * @param nodeTable    the node type's own table, whose record the load materialises
     */
    public static MethodSpec decodeHelper(String name, ClassName encoderClass, String typeId,
            java.util.List<no.sikt.graphitron.model.jooq.ColumnRef> keyColumns,
            TableRef nodeTable) {
        return decodeHelper(name, encoderClass, typeId, keyColumns, nodeTable,
            CodeBlock.builder()
                .addStatement("throw $T.newErrorException().message($S).build()", GRAPHQL_ERROR,
                    "Decoded NodeId did not match the expected type for this argument")
                .build());
    }

    /**
     * The same body whose arity mismatch returns {@code null} instead of failing, for a caller that
     * is about to try another candidate. The one form of this helper that does not raise: a
     * polymorphic slot's per-candidate arm has to be able to tell "this id is not mine" from "this id
     * is broken", and the three forms above answer both with a throw, so a right-prefix-wrong-arity
     * id would surface one candidate's own mismatch error instead of the dispatch's message naming
     * every candidate. {@code decodeValues} already returns {@code null} for a foreign prefix, so one
     * arm answers both misses.
     *
     * <p>Never emitted as a slot's own decode. Its caller is the container helper
     * {@link #polymorphicDecodeHelper} builds, which raises the failure itself once every candidate
     * has stood aside.
     *
     * @param name         the method name the host allocated for this candidate
     * @param encoderClass the generated node-id encoder the decode goes through
     * @param typeId       the wire type id the encoded id carries
     * @param keyColumns   the node type's key columns in key order, the shape the load names
     * @param nodeTable    the node type's own table, whose record the load materialises
     */
    public static MethodSpec decodeHelperOrNull(String name, ClassName encoderClass, String typeId,
            java.util.List<no.sikt.graphitron.model.jooq.ColumnRef> keyColumns,
            TableRef nodeTable) {
        return decodeHelper(name, encoderClass, typeId, keyColumns, nodeTable,
            CodeBlock.builder().addStatement("return null").build());
    }

    /**
     * The container helper a polymorphic {@code @nodeId} slot calls:
     * {@code private static <SlotType> decode<Container>Record(Object wire)}, which reads the wire
     * id's own type prefix, hands it to whichever candidate claims that prefix, and raises the
     * multi-candidate client error when none does.
     *
     * <pre>
     *   private static UpdatableRecord&lt;?&gt; decodeAddressOccupantRecord(Object wire) {
     *       if (!(wire instanceof String nodeId)) {
     *           return null;
     *       }
     *       String peeked = NodeIdEncoder.peekTypeId(nodeId);
     *       if ("Customer".equals(peeked)) {
     *           CustomerRecord decodedRecord = decodeAddressOccupantRecordCustomer(wire);
     *           if (decodedRecord != null) {
     *               return decodedRecord;
     *           }
     *       }
     *       ...
     *       throw new GraphitronClientException(...);
     *   }
     * </pre>
     *
     * <p>An {@code if} chain and not a {@code switch} on the peeked string: the emitted source
     * compiles at Java 17 in {@code graphitron-sakila-example}, and a {@code switch} on a
     * {@code null} {@code String} throws, so the chain is both the simpler and the correct shape.
     *
     * <p>A non-{@code String} wire value returns {@code null}, which is the contract the single-type
     * helper keeps: graphql-java enforces {@code ID!} at the boundary, so a non-null slot always has a
     * string, and a nullable slot's omitted or explicitly-null value yields an unset member.
     *
     * @param name          the method name the host allocated for this container
     * @param slotType      the slot's declared type, which every candidate's record is
     * @param encoderClass  the generated node-id encoder the peek goes through
     * @param candidateTypeIds  each candidate's wire type id, in emission order
     * @param candidateHelpers  each candidate's null-returning helper name, in the same order
     * @param candidateRecords  each candidate's record class, in the same order
     * @param candidates    the candidate node types as the client error names them
     * @param outputPackage the run's output package, which is how the generated client-error type is
     *                      reached
     */
    public static MethodSpec polymorphicDecodeHelper(String name,
            no.sikt.graphitron.javapoet.TypeName slotType,
            ClassName encoderClass, java.util.List<String> candidateTypeIds,
            java.util.List<String> candidateHelpers, java.util.List<ClassName> candidateRecords,
            String candidates, String outputPackage) {
        ClassName clientException = ClassName.get(outputPackage + ".schema", "GraphitronClientException");
        var body = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(slotType)
            .addParameter(Object.class, "wire")
            .beginControlFlow("if (!(wire instanceof String nodeId))")
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T peeked = $T.peekTypeId(nodeId)", String.class, encoderClass);
        for (int i = 0; i < candidateTypeIds.size(); i++) {
            body.beginControlFlow("if ($S.equals(peeked))", candidateTypeIds.get(i))
                .addStatement("$T decodedRecord = $L(wire)", candidateRecords.get(i),
                    candidateHelpers.get(i))
                .beginControlFlow("if (decodedRecord != null)")
                .addStatement("return decodedRecord")
                .endControlFlow()
                .endControlFlow();
        }
        return body.addStatement("throw new $T($L)", clientException,
                NodeIdDecodeFailure.multiCandidateMessage(candidateTypeIds, candidates, "wire",
                    "peeked"))
            .build();
    }

    /**
     * The list form of {@link #polymorphicDecodeHelper}: one decoded record per element, through the
     * scalar helper. A present-but-foreign element raises there, because a slot's value is
     * materialized input and not a query predicate; there is no silent-drop path here, as there is
     * none in the single-type list helper.
     *
     * @param name       the method name the host allocated for the list form
     * @param slotType   the slot's declared <em>element</em> type; the helper returns a list of it
     * @param scalarName the scalar container helper's name, which each element goes through
     */
    public static MethodSpec polymorphicDecodeHelperList(String name,
            no.sikt.graphitron.javapoet.TypeName slotType, String scalarName) {
        var listType = no.sikt.graphitron.javapoet.ParameterizedTypeName.get(
            ClassName.get(java.util.List.class), slotType);
        return MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listType)
            .addParameter(Object.class, "wire")
            .beginControlFlow("if (!(wire instanceof $T<?> nodeIds))",
                ClassName.get(java.util.List.class))
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T records = new $T<>(nodeIds.size())", listType,
                ClassName.get(java.util.ArrayList.class))
            .beginControlFlow("for (Object element : nodeIds)")
            .addStatement("records.add($L(element))", scalarName)
            .endControlFlow()
            .addStatement("return records")
            .build();
    }

    /**
     * The shared body, with the mismatch failure supplied. Both public forms above route through
     * here, so the decode, the arity check and the positional load have one spelling and only the
     * throw differs.
     */
    private static MethodSpec decodeHelper(String name, ClassName encoderClass, String typeId,
            java.util.List<no.sikt.graphitron.model.jooq.ColumnRef> keyColumns,
            TableRef nodeTable, CodeBlock mismatchThrow) {
        ClassName recordType = CatalogRefs.recordClass(nodeTable);
        int arity = keyColumns.size();
        var body = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(recordType)
            .addParameter(Object.class, "wire")
            .beginControlFlow("if (!(wire instanceof String nodeId))")
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T values = $T.decodeValues($S, nodeId)",
                String[].class, encoderClass, typeId)
            .beginControlFlow("if (values == null || values.length != $L)", arity)
            .addCode(mismatchThrow)
            .endControlFlow()
            .addStatement("$T decoded = new $T()", recordType, recordType);
        var fields = CodeBlock.builder();
        for (var column : keyColumns) {
            fields.add(", $T.$L.$L", CatalogRefs.constantsClass(nodeTable), nodeTable.javaFieldName(),
                column.javaName());
        }
        body.addStatement("decoded.fromArray(values$L)", fields.build());
        return body.addStatement("return decoded").build();
    }
}
