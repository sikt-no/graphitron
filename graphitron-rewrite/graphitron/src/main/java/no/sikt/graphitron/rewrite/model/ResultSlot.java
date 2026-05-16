package no.sikt.graphitron.rewrite.model;

import java.lang.reflect.Method;
import java.util.List;

/**
 * The location of the result slot on a payload class for a service-backed fetcher's success arm.
 * Paired with the {@link PayloadConstructionShape} the classifier resolved on the payload class.
 *
 * <ul>
 *   <li>{@link CtorParameterIndex} : the service-return local binds to the constructor
 *       parameter at this index ({@code new Payload(..., __row, ...)}). Pairs with
 *       {@link PayloadConstructionShape.AllFieldsCtor}.</li>
 *   <li>{@link SetterMethod} : the service-return local is passed to {@code boundSetter} on a
 *       no-arg-constructed instance ({@code var p = new Payload(); ...; p.setX(__row); ...;
 *       return p;}). Pairs with {@link PayloadConstructionShape.MutableBean}.</li>
 * </ul>
 *
 * <p>Sibling of {@link ErrorsSlot}. See {@link ErrorsSlot} for the rationale on keeping these
 * two role-specific seals separate rather than folding them together.
 */
public sealed interface ResultSlot
        permits ResultSlot.CtorParameterIndex, ResultSlot.SetterMethod {

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

    /**
     * Mutable-bean shape: invoke {@code boundSetter} on a no-arg-constructed payload with the
     * service-return local. {@code nonBoundSetters} captures every other SDL field's setter
     * paired with its language-default literal so the emit walks one structured list. The
     * errors-slot setter, if present (the surrounding fetcher's {@link ErrorChannel} carries the
     * {@link ErrorsSlot}), is suppressed by the consumer at emit time and replaced with the
     * success-arm {@code List.of()}.
     */
    record SetterMethod(Method boundSetter, List<NonBoundSetter> nonBoundSetters)
            implements ResultSlot {
        public SetterMethod {
            if (boundSetter == null) {
                throw new IllegalArgumentException(
                    "ResultSlot.SetterMethod: boundSetter must be non-null");
            }
            nonBoundSetters = List.copyOf(nonBoundSetters);
        }
    }
}
