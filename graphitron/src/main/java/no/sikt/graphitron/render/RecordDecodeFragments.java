package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.model.jooq.TableRef;

import javax.lang.model.element.Modifier;
import no.sikt.graphitron.model.jooq.ColumnRef;

/**
 * The one body that turns a base64 node id into the target table's own {@code TableRecord}:
 * {@code private static <Record> decode<TypeName>Record(Object wire)}. Every host that decodes a node id into
 * a record emits this, and there are two families of them, which is why the derivation is here rather
 * than in either.
 *
 * <p>Two emitted forms come off the one body below: the client-error form, and the
 * null-returning form a polymorphic slot's per-candidate arm needs. Beside them sits the container
 * helper a polymorphic slot calls, which composes the null-returning form per candidate with the
 * multi-candidate failure {@link NodeIdDecodeFailure} spells.
 *
 * <p>A jOOQ-record-typed {@code @service} input-bean member decodes one on {@code <Type>Fetchers}; an
 * {@code argMapping} key-column projection decodes one wherever its consumer's glue lives, which for a
 * {@code @condition} is the {@code <Root>Conditions} class that {@link ConditionGlueRenderer} builds
 * and cannot reach the legacy emitter from. Copying the body into the second host would leave two
 * spellings of one emitted form, so it moved here and the unmigrated host delegates, the same
 * arrangement {@link FetcherResult} and {@link ErrorDispatchFragments} carry.
 *
 * <p>Naming is deliberately <em>not</em> here. One generated class's private-static method namespace is
 * that class's to allocate: {@code <Type>Fetchers} allocates its {@code decode*} names across the node
 * types and the polymorphic containers it hosts together, and a conditions class has its own namespace
 * with its own occupants. Both spell this family from the node type's own name, which is what a decode
 * body is a function of, so one table backing two node types yields two bodies under two names. The
 * host passes the name in.
 */
public final class RecordDecodeFragments {

    private RecordDecodeFragments() {}

    /**
     * The decode helper for one node type, under the name its host allocated, raising the
     * client error {@link NodeIdDecodeFailure} spells when the id does not decode.
     *
     * <p>The load is one {@code fromArray} call regardless of key arity, so a composite key just names
     * N fields, and each value is coerced through its column's {@code DataType} and registered
     * {@code Converter} rather than through the deprecated {@code convert(Object)}. Statement form
     * throughout (explicit types, named locals, no {@code var}) per the "generated code is read and
     * debugged" principle.
     *
     * <p>The two failure shapes are told apart on purpose. A wire value that is not a string at all is
     * an absent or wrong-shaped argument, so the helper returns {@code null} and lets its caller decide;
     * a string that does not decode is a malformed id or a well-formed id of another type, which is a
     * client error and throws. A caller that wants the first to be an error too raises it at the call
     * site, where it knows what the value was for.
     *
     * <p>Both hosts raise the same failure. A conditions class hosting an {@code argMapping}
     * key-column projection sits beside {@link CompositeDecodeHelperRegistry}'s key helpers reading
     * the same wire value at a different grain, and a {@code <Type>Fetchers} class hosting an
     * input-bean member decode reads it at a {@code @service} boundary; one bad id fails identically
     * at every grain, or a client learns that one spelling of an id validates and another does not.
     *
     * @param name          the method name the host allocated for this record type
     * @param encoderClass  the generated node-id encoder the decode goes through
     * @param typeId        the wire type id the encoded id carries
     * @param nodeTypeName  the node type as the failure message names it to the client
     * @param keyColumns    the node type's key columns in key order, the shape the load names
     * @param nodeTable     the node type's own table, whose record the load materialises
     * @param outputPackage the run's output package, which is how the generated client-error type is
     *                      reached
     */
    public static MethodSpec decodeHelper(String name, ClassName encoderClass, String typeId,
            String nodeTypeName, java.util.List<no.sikt.graphitron.model.jooq.ColumnRef> keyColumns,
            TableRef nodeTable, String outputPackage) {
        return decodeHelper(name, encoderClass, typeId, keyColumns, nodeTable,
            NodeIdDecodeFailure.throwStatement(outputPackage, encoderClass, typeId, nodeTypeName,
                "nodeId", "nodeId"));
    }

    /**
     * The same body whose arity mismatch returns {@code null} instead of failing, for a caller that
     * is about to try another candidate. The one form of this helper that does not raise: a
     * polymorphic slot's per-candidate arm has to be able to tell "this id is not mine" from "this id
     * is broken", and the form above answers both with a throw, so a right-prefix-wrong-arity
     * id would surface one candidate's own mismatch error instead of the dispatch's message naming
     * every candidate. {@code decodeColumns} already returns {@code null} for a foreign prefix, so one
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
            com.palantir.javapoet.TypeName slotType,
            ClassName encoderClass, java.util.List<String> candidateTypeIds,
            java.util.List<String> candidateHelpers, java.util.List<ClassName> candidateRecords,
            String candidates, String outputPackage) {
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
        return body.addStatement("throw new $T($L)", NodeIdDecodeFailure.clientException(outputPackage),
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
            com.palantir.javapoet.TypeName slotType, String scalarName) {
        var listType = com.palantir.javapoet.ParameterizedTypeName.get(
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
     * The shared body, with the mismatch failure supplied. The raising and the null-returning forms
     * above route through here, so the decode, the arity check and the positional load have one
     * spelling and only the mismatch branch differs.
     */
    private static MethodSpec decodeHelper(String name, ClassName encoderClass, String typeId,
            java.util.List<no.sikt.graphitron.model.jooq.ColumnRef> keyColumns,
            TableRef nodeTable, CodeBlock mismatchThrow) {
        ClassName recordType = CatalogRefs.recordClass(nodeTable);
        var fields = CodeBlock.builder();
        for (var column : keyColumns) {
            fields.add(", $T.$L.$L", CatalogRefs.constantsClass(nodeTable), nodeTable.javaFieldName(),
                column.javaName());
        }
        // decodeColumns refuses a malformed id, a wrong arity and a key value that is not a value
        // of its column, so every value fromArray loads is the column's own, never null.
        return MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(recordType)
            .addParameter(Object.class, "wire")
            .beginControlFlow("if (!(wire instanceof String nodeId))")
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T values = $T.decodeColumns($S, nodeId, $L$L)",
                Object[].class, encoderClass, typeId, SlotParse.parseClasses(keyColumns), fields.build())
            .beginControlFlow("if (values == null)")
            .addCode(mismatchThrow)
            .endControlFlow()
            .addStatement("$T decoded = new $T()", recordType, recordType)
            .addStatement("decoded.fromArray(values$L)", fields.build())
            .addStatement("return decoded")
            .build();
    }
}
