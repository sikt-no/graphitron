package no.sikt.graphitron.rewrite.model;

/**
 * R246 — narrow interface declaring the two slots {@code UpdateRowsWalker} (and {@code FieldBuilder},
 * for the arg surface) populate on an {@code @mutation(typeName: UPDATE)} field that returns its
 * {@code @table} type directly: the slim {@link InputArgRef} and the {@link UpdateRows} carrier.
 *
 * <p>Sibling to the future {@code DeleteRowsField} / {@code InsertRowsField}: each DML walker-carrier
 * slice declares its own narrow interface surfacing the same {@link #inputArg()} accessor alongside
 * its verb-specific carrier, so emit-time helpers reading the arg surface work uniformly across
 * kinds. Both slots are non-Optional: every classified field carrying this interface has a
 * populated {@code inputArg} and {@code updateRows}. Fields that fail the FieldBuilder pre-checks or
 * the walker never reach construction; they surface as typed rejections with no carrier.
 */
public interface UpdateRowsField {

    InputArgRef inputArg();

    UpdateRows updateRows();
}
