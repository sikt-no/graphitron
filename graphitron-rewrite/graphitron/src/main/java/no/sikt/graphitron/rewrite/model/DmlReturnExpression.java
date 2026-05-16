package no.sikt.graphitron.rewrite.model;

/**
 * Pre-resolved return-shape dispatch for the four DML mutation variants. Replaces a per-emitter
 * {@code instanceof ScalarReturnType} / {@code wrapper().isList()} switch with a single sealed
 * pattern-match: the classifier picks the arm once, each {@link MutationField.DmlTableField}
 * carries it, and INSERT / UPDATE / DELETE / UPSERT emitters read it without defensive checks.
 *
 * <p>Total over the admitted DML return-type set on the {@code Mutation*TableField} permits:
 * <ul>
 *   <li>{@code ScalarReturnType("ID")}, single — {@link EncodedSingle}</li>
 *   <li>{@code ScalarReturnType("ID")}, list — {@link EncodedList}</li>
 *   <li>{@code TableBoundReturnType}, single — {@link ProjectedSingle}</li>
 *   <li>{@code TableBoundReturnType}, list — {@link ProjectedList}</li>
 * </ul>
 *
 * <p>{@code @record}-returning DML routes through the carrier-walk permits
 * ({@link MutationField.MutationDmlRecordField} / {@link MutationField.MutationBulkDmlRecordField})
 * rather than carrying a fifth arm here. R161 collapsed the historical {@code Payload} arm by
 * widening {@code BuildContext.tryResolveSingleRecordCarrier} to admit every {@code ResultType}
 * arm: {@code Mutation*TableField} permits are now guaranteed never to carry a {@code @record}
 * return, and the type system enforces narrowness structurally rather than via classifier-
 * acceptance shape.
 *
 * <p>Single-vs-list is encoded in the variant choice, not in a separate {@code isList} flag, so
 * the per-shape projection ({@code Encoded}, {@code Projected}) and the terminal cardinality
 * ({@code .fetchOne} / {@code .fetch}) read from one switch.
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
}
