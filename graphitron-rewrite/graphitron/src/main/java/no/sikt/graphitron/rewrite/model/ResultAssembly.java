package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * Carrier-side recipe for emitting a typed payload-class constructor call on the success arm of
 * a service-backed fetcher. The service method returns the domain object the payload's
 * <em>result slot</em> expects, and the wrapper assembles the payload around that return value.
 *
 * <p>Populated by the carrier classifier when a service-backed field's payload class has a
 * resolved {@link PayloadConstructionShape} exposing exactly one slot assignable from the
 * service method's declared return type. The emitter dispatches on {@code resultSlot}:
 * <ul>
 *   <li>{@link ResultSlot.CtorParameterIndex} : walk constructor slots positionally, printing
 *       the captured service-return local at {@code resultSlot.index()},
 *       {@code List.of()} at the channel's errors-ctor-index when an {@link ErrorChannel} is
 *       also present (success arm initialises the errors list to empty), and the slot's
 *       pre-resolved {@link DefaultedSlot#defaultLiteral()} otherwise.</li>
 *   <li>Phase-2 setter arm : no-arg-construct then invoke the result setter with the
 *       service-return local; the success-arm errors setter receives an empty list when an
 *       {@link ErrorChannel} is also present.</li>
 * </ul>
 *
 * <p>Independent of {@link ErrorChannel}: a service-backed field whose payload has no errors
 * slot carries a {@code ResultAssembly} (so the success arm constructs the payload around the
 * domain return) but no channel (so the catch arm falls back to {@code ErrorRouter.redact}).
 * When both are present they reference the same payload class.
 *
 * <p>Service-backed fields whose service method returns the SDL payload class directly do
 * not get a {@code ResultAssembly}; the emitter passes the service return through unchanged.
 */
public record ResultAssembly(
    ClassName payloadClass,
    ResultSlot resultSlot,
    TypeName resultSlotType,
    List<DefaultedSlot> defaultedSlots
) {
    public ResultAssembly {
        defaultedSlots = List.copyOf(defaultedSlots);
        if (resultSlot == null) {
            throw new IllegalArgumentException("ResultAssembly: resultSlot must be non-null");
        }
        if (resultSlot instanceof ResultSlot.CtorParameterIndex cpi) {
            for (var slot : defaultedSlots) {
                if (slot.index() == cpi.index()) {
                    throw new IllegalArgumentException(
                        "ResultAssembly: defaultedSlots must not include the result slot at index "
                            + cpi.index() + "; got slot for parameter '" + slot.name() + "'");
                }
            }
        }
    }
}
