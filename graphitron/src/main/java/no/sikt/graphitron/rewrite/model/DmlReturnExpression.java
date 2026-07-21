package no.sikt.graphitron.rewrite.model;

import java.util.List;

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
 * <p>Class-backed-returning DML routes through the {@code Mutation*DmlRecordField} permits
 * ({@link MutationField.MutationDmlRecordField} / {@link MutationField.MutationBulkDmlRecordField})
 * rather than carrying a fifth arm here. The historical {@code Payload} arm was collapsed by
 * widening the structural-carrier admission to every {@code ResultType} arm: the
 * {@code Mutation*TableField} permits are now guaranteed never to carry a class-backed
 * return, and the type system enforces narrowness structurally rather than via classifier-
 * acceptance shape.
 *
 * <p>Single-vs-list is encoded in the variant choice, not in a separate {@code isList} flag, so
 * the per-shape projection ({@code Encoded}, {@code Projected}, {@code Discriminated}) and the
 * terminal cardinality ({@code .fetchOne} / {@code .fetch}) read from one switch.
 *
 * <p>The {@code Discriminated*} pair handles a return that is a single-table discriminated
 * interface ({@code @table @discriminate}, implementers pinned by {@code @discriminator(value:)},
 * all sharing one jOOQ table). The write half is identical to {@code Projected*} (a plain
 * single-{@code @table} write); only the follow-up re-projection differs: rather than the
 * concrete-type {@code <TypeName>Type.$fields(...)} SELECT, it projects the synthetic
 * {@code __discriminator__} alias plus the unified participant field set with discriminator-gated
 * cross-table {@code LEFT JOIN}s, so the interface's {@code TypeResolver} can route each row to its
 * implementer. Choosing a new return-shape arm (rather than a per-verb {@code MutationField} leaf)
 * keeps the fork off the write-verb axis: the write half is uniform across INSERT / UPDATE and the
 * model already carries this return-shape seam.
 */
public sealed interface DmlReturnExpression {

    /** {@code ID} return on a single-cardinality DML. The encoder helper resolves the per-{@code @node}-type {@code encode<TypeName>}. */
    record EncodedSingle(HelperRef.Encode encode) implements DmlReturnExpression {}

    /** {@code [ID]} return on a list-cardinality DML. Same encoder helper as {@link EncodedSingle}. */
    record EncodedList(HelperRef.Encode encode) implements DmlReturnExpression {}

    /**
     * {@code T} return where {@code T} is a {@code @table} type. The GraphQL return-type name
     * resolves the {@code <TypeName>Type.$fields(...)} projection class.
     *
     * <p>{@code reentryCorrelation} is the classified correlation the {@code rows<Name>} reentry
     * companion keys its follow-up SELECT on: the {@link ParentCorrelation.OnLiftedSlots}
     * PK-self-identity shape over the bound table's primary key (the same columns the write's
     * {@code RETURNING} captured). Attached at parse time so the reentry emitters read the carried
     * fact instead of re-deriving the key column set from {@code TableRef.primaryKeyColumns()} at
     * each emit site; the classifier rejects a table-bound DML return whose table has no primary
     * key before constructing this arm, so the correlation is never null.
     */
    record ProjectedSingle(String returnTypeName,
        ParentCorrelation.OnLiftedSlots reentryCorrelation) implements DmlReturnExpression {}

    /** {@code [T]} return where {@code T} is a {@code @table} type. Same projection class and carried correlation as {@link ProjectedSingle}. */
    record ProjectedList(String returnTypeName,
        ParentCorrelation.OnLiftedSlots reentryCorrelation) implements DmlReturnExpression {}

    /**
     * {@code T} return where {@code T} is a single-table discriminated interface. Carries the
     * read-side single-table discrimination data (sourced verbatim from the {@code TableInterfaceType}
     * verdict) so the emitter can re-project through the shared discriminated re-projection helper
     * keyed by the DML write's {@code RETURNING} primary key, plus the same carried
     * {@code reentryCorrelation} as {@link ProjectedSingle}. The DML sibling of the
     * {@code *ServiceTableInterfaceField} service-return variants.
     */
    record DiscriminatedSingle(String interfaceName, String discriminatorColumn,
        List<String> knownDiscriminatorValues, List<ParticipantRef> participants,
        ParentCorrelation.OnLiftedSlots reentryCorrelation) implements DmlReturnExpression {}

    /** {@code [T]} return where {@code T} is a single-table discriminated interface. List sibling of {@link DiscriminatedSingle}. */
    record DiscriminatedList(String interfaceName, String discriminatorColumn,
        List<String> knownDiscriminatorValues, List<ParticipantRef> participants,
        ParentCorrelation.OnLiftedSlots reentryCorrelation) implements DmlReturnExpression {}
}
