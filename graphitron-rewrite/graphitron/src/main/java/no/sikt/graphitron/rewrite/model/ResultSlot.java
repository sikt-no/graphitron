package no.sikt.graphitron.rewrite.model;

/**
 * The location of the result slot on a payload class for a service-backed fetcher's success arm.
 * Paired with the {@link PayloadConstructionShape} the classifier resolved on the payload class.
 *
 * <ul>
 *   <li>{@link CtorParameterIndex} : the service-return local binds to the constructor
 *       parameter at this index ({@code new Payload(..., __row, ...)}). Pairs with
 *       {@link PayloadConstructionShape.AllFieldsCtor}.</li>
 *   <li>{@code SetterMethod} (phase 2) : the service-return local is passed to this setter on
 *       a no-arg-constructed instance ({@code var p = new Payload(); ...; p.setX(__row); ...;
 *       return p;}). Pairs with the phase-2 mutable-bean permit.</li>
 * </ul>
 *
 * <p>Sibling of {@link ErrorsSlot} and {@link RowSlot}. See {@link ErrorsSlot} for the rationale
 * on keeping these three role-specific seals separate rather than folding them together.
 */
public sealed interface ResultSlot permits ResultSlot.CtorParameterIndex {

    /**
     * All-fields-ctor shape: the service-return parameter sits at {@code index} in the canonical
     * constructor's parameter list. The emitter prints {@code __row} at this slot and the
     * pre-resolved {@link DefaultedSlot#defaultLiteral()} at every other.
     */
    record CtorParameterIndex(int index) implements ResultSlot {
        public CtorParameterIndex {
            if (index < 0) {
                throw new IllegalArgumentException(
                    "ResultSlot.CtorParameterIndex: index must be non-negative; got " + index);
            }
        }
    }
}
