package no.sikt.graphitron.rewrite.model;

/**
 * Narrow interface declaring the two slots {@code DeleteRowsWalker} (and {@code FieldBuilder},
 * for the arg surface) populate on an {@code @mutation(typeName: DELETE)} field: the slim
 * {@link InputArgRef} and the {@link DeleteRows} carrier. Sibling to the UPDATE-verb {@link UpdateRowsField};
 * each DML walker-carrier slice declares its own narrow interface surfacing the same
 * {@link #inputArg()} accessor alongside its verb-specific carrier, so emit-time helpers reading the
 * arg surface work uniformly across kinds.
 *
 * <p>Unlike UPDATE, DELETE wears this interface on three leaves: the direct-{@code @table}/ID-return
 * {@code MutationDeleteTableField}, the payload single {@code MutationDeletePayloadField}, and the
 * payload bulk {@code MutationBulkDeletePayloadField} (direct and payload DELETE are the same
 * operation — match by key, delete, return the PKs — so one carrier serves all three). Both slots
 * are non-Optional: every classified field carrying this interface has a populated {@code inputArg}
 * and {@code deleteRows}. Fields that fail the FieldBuilder pre-checks or the walker never reach
 * construction; they surface as typed rejections with no carrier.
 */
public interface DeleteRowsField {

    InputArgRef inputArg();

    DeleteRows deleteRows();
}
