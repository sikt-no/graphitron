package no.sikt.graphitron.rewrite.model;

/**
 * The location of the errors slot on a payload class, paired with the
 * {@link PayloadConstructionShape} the classifier resolved. Two structurally distinct ways to
 * bind the errors list at the catch arm's payload-factory:
 *
 * <ul>
 *   <li>{@link CtorParameterIndex} : pass the errors list as the constructor parameter at this
 *       index ({@code new Payload(arg0, arg1, errors, arg3)}). Pairs with
 *       {@link PayloadConstructionShape.AllFieldsCtor}.</li>
 *   <li>{@code SetterMethod} (phase 2) : invoke this setter on a no-arg-constructed instance
 *       ({@code var p = new Payload(); ...; p.setErrors(errors); ...; return p;}). Pairs with
 *       the phase-2 mutable-bean permit.</li>
 * </ul>
 *
 * <p>Sibling of {@link ResultSlot} and {@link RowSlot} in form but lives in its own sealed
 * hierarchy : folding the three onto one broad {@code Slot} interface would force every
 * consumer to widen and reach back through {@code instanceof} for the role-specific data
 * ({@code ErrorChannel}, {@code ResultAssembly}, {@code PayloadAssembly} each carry their own
 * slot type).
 */
public sealed interface ErrorsSlot permits ErrorsSlot.CtorParameterIndex {

    /**
     * All-fields-ctor shape: the errors parameter sits at {@code index} in the canonical
     * constructor's parameter list. The catch-arm payload-factory emits
     * {@code new Payload(..., errors, ...)} where {@code errors} is the lambda parameter and
     * every other slot prints the corresponding {@link DefaultedSlot#defaultLiteral()}.
     */
    record CtorParameterIndex(int index) implements ErrorsSlot {
        public CtorParameterIndex {
            if (index < 0) {
                throw new IllegalArgumentException(
                    "ErrorsSlot.CtorParameterIndex: index must be non-negative; got " + index);
            }
        }
    }
}
