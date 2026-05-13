package no.sikt.graphitron.rewrite.model;

import java.lang.reflect.Method;
import java.util.List;

/**
 * The location of the row slot on a payload class for a DML mutation fetcher's success arm.
 * Paired with the {@link PayloadConstructionShape} the classifier resolved on the payload class.
 *
 * <ul>
 *   <li>{@link CtorParameterIndex} : the jOOQ row record binds to the constructor parameter at
 *       this index ({@code new Payload(..., row, ...)}). Pairs with
 *       {@link PayloadConstructionShape.AllFieldsCtor}.</li>
 *   <li>{@link SetterMethod} : the jOOQ row record is passed to {@code boundSetter} on a
 *       no-arg-constructed instance ({@code var p = new Payload(); ...; p.setRow(row); ...;
 *       return p;}). Pairs with {@link PayloadConstructionShape.MutableBean}.</li>
 * </ul>
 *
 * <p>Sibling of {@link ErrorsSlot} and {@link ResultSlot}. See {@link ErrorsSlot} for the
 * rationale on keeping these three role-specific seals separate rather than folding them
 * together.
 */
public sealed interface RowSlot
        permits RowSlot.CtorParameterIndex, RowSlot.SetterMethod {

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

    /**
     * Mutable-bean shape: invoke {@code boundSetter} on a no-arg-constructed payload with the
     * row record. {@code nonBoundSetters} captures every other SDL field's setter paired with
     * its language-default literal so the emit walks one structured list.
     */
    record SetterMethod(Method boundSetter, List<NonBoundSetter> nonBoundSetters)
            implements RowSlot {
        public SetterMethod {
            if (boundSetter == null) {
                throw new IllegalArgumentException(
                    "RowSlot.SetterMethod: boundSetter must be non-null");
            }
            nonBoundSetters = List.copyOf(nonBoundSetters);
        }
    }
}
