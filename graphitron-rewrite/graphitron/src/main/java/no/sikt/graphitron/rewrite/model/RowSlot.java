package no.sikt.graphitron.rewrite.model;

/**
 * The location of the row slot on a payload class for a DML mutation fetcher's success arm.
 * Paired with the {@link PayloadConstructionShape} the classifier resolved on the payload class.
 *
 * <ul>
 *   <li>{@link CtorParameterIndex} : the jOOQ row record binds to the constructor parameter at
 *       this index ({@code new Payload(..., row, ...)}). Pairs with
 *       {@link PayloadConstructionShape.AllFieldsCtor}.</li>
 *   <li>{@code SetterMethod} (phase 2) : the jOOQ row record is passed to this setter on a
 *       no-arg-constructed instance ({@code var p = new Payload(); ...; p.setRow(row); ...;
 *       return p;}). Pairs with the phase-2 mutable-bean permit.</li>
 * </ul>
 *
 * <p>Sibling of {@link ErrorsSlot} and {@link ResultSlot}. See {@link ErrorsSlot} for the
 * rationale on keeping these three role-specific seals separate rather than folding them
 * together.
 */
public sealed interface RowSlot permits RowSlot.CtorParameterIndex {

    /**
     * All-fields-ctor shape: the jOOQ row record parameter sits at {@code index} in the
     * canonical constructor's parameter list. The emitter prints {@code row} at this slot and
     * the pre-resolved {@link DefaultedSlot#defaultLiteral()} at every other.
     */
    record CtorParameterIndex(int index) implements RowSlot {
        public CtorParameterIndex {
            if (index < 0) {
                throw new IllegalArgumentException(
                    "RowSlot.CtorParameterIndex: index must be non-negative; got " + index);
            }
        }
    }
}
