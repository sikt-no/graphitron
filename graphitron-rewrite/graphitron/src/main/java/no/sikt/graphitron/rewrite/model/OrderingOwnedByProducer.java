package no.sikt.graphitron.rewrite.model;

/**
 * Capability marker for {@link SqlGeneratingField} variants whose visible result ordering is
 * owned by an upstream producer rather than by the field's own {@code orderBy()} component.
 *
 * <p>Two permits opt in:
 *
 * <ul>
 *   <li>{@link ChildField.SingleRecordTableField} — the R141 / R158 carrier data field. The
 *       {@code FetcherEmitter} walk fetches the response SELECT into a PK-keyed map and then
 *       iterates the upstream {@code source} list to assemble the returned rows in source order.
 *       The response SELECT's ORDER BY contributes nothing to visible ordering.</li>
 *   <li>{@link ChildField.ServiceTableField} — the {@code @service}-backed child field. The
 *       developer's service method returns the list verbatim; Graphitron emits no follow-up
 *       SELECT for this field. Ordering is whatever the service method produces.</li>
 * </ul>
 *
 * <p>Why a marker rather than an {@code orderBy()} value: both permits structurally carry
 * {@link OrderBySpec.None} (literally, in {@link ChildField.SingleRecordTableField}'s accessor
 * override; effectively, in {@link ChildField.ServiceTableField}'s construction sites in
 * {@code FieldBuilder} where the resolver is never invoked). The cross-cutting validator
 * {@code GraphitronSchemaValidator.validateListRequiresOrdering} treats "list-shaped +
 * {@code OrderBySpec.None}" as a non-determinism rejection; that rule is *wrong* for these
 * permits because the upstream producer owns ordering. Encoding the exemption at the type
 * level keeps the validator's predicate honest ("list-shaped and unorderable AND no producer
 * owns ordering") instead of forcing the model to fabricate a misleading {@code orderBy} value
 * to side-step the check.
 *
 * <p>Sealed so a future field permit with the same "upstream owns ordering" semantics must be
 * added to the permits list deliberately; find-usages from any permit surfaces the validator
 * coupling.
 */
public sealed interface OrderingOwnedByProducer permits
    ChildField.SingleRecordTableField,
    ChildField.ServiceTableField {
}
