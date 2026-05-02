package no.sikt.graphitron.rewrite.model;

/**
 * Pre-resolved return-shape dispatch for the four DML mutation variants. Replaces a per-emitter
 * {@code instanceof ScalarReturnType} / {@code wrapper().isList()} / {@code payloadAssembly()
 * .isPresent()} switch with a single sealed pattern-match: the classifier picks the arm once,
 * each {@link MutationField.DmlTableField} carries it, and INSERT / UPDATE / DELETE / UPSERT
 * emitters read it without defensive checks.
 *
 * <p>Total over the admitted DML return-type set defined by Invariant #14 in
 * {@code graphitron-rewrite/roadmap/mutations.md}:
 * <ul>
 *   <li>{@code ScalarReturnType("ID")}, single — {@link EncodedSingle}</li>
 *   <li>{@code ScalarReturnType("ID")}, list — {@link EncodedList}</li>
 *   <li>{@code TableBoundReturnType}, single — {@link ProjectedSingle}</li>
 *   <li>{@code TableBoundReturnType}, list — {@link ProjectedList}</li>
 *   <li>{@code ResultReturnType} (single, R12 {@code @record} payload) — {@link Payload}</li>
 * </ul>
 *
 * <p>Single-vs-list is encoded in the variant choice, not in a separate {@code isList} flag, so
 * the per-shape projection ({@code Encoded}, {@code Projected}) and the terminal cardinality
 * ({@code .fetchOne} / {@code .fetch}) read from one switch. The Payload arm is single-only;
 * list payloads are rejected at classify time per Invariant #14 (R12).
 *
 * <p>{@link Payload} carries the {@link PayloadAssembly} that captures the success-arm
 * payload-class constructor recipe; emitters walk its constructor slots positionally without
 * a separate {@code Optional<PayloadAssembly>} lookup.
 */
public sealed interface DmlReturnExpression {

    /** {@code ID} return on a single-cardinality DML. The encoder helper resolves the per-{@code @node}-type {@code encode<TypeName>}. */
    record EncodedSingle(HelperRef.Encode encode) implements DmlReturnExpression {}

    /** {@code [ID]} return on a list-cardinality DML. Same encoder helper as {@link EncodedSingle}. */
    record EncodedList(HelperRef.Encode encode) implements DmlReturnExpression {}

    /** {@code T} return where {@code T} is a {@code @table} type. The GraphQL return-type name resolves the {@code <TypeName>Type.$fields(...)} projection class. */
    record ProjectedSingle(String returnTypeName) implements DmlReturnExpression {}

    /** {@code [T]} return where {@code T} is a {@code @table} type. Same projection class as {@link ProjectedSingle}. */
    record ProjectedList(String returnTypeName) implements DmlReturnExpression {}

    /**
     * R12 {@code @record} payload return. Single-cardinality only; list payloads are rejected
     * at classify time. The {@link PayloadAssembly} captures the payload class, the row-slot
     * index that the SQL row record binds to, and the defaulted slots for everything else.
     */
    record Payload(PayloadAssembly assembly) implements DmlReturnExpression {}
}
