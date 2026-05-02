package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * Carrier-side recipe for emitting a typed payload-class constructor call on the success arm of
 * a service-backed fetcher (R12 §2c, §5). Where {@link PayloadAssembly} captures the analogous
 * recipe for DML fetchers (binding the row record to the payload's row slot), this record
 * captures the success-arm wiring for service fetchers: the service method returns the
 * domain object the payload's <em>result slot</em> expects, and the wrapper assembles the
 * payload around that return value.
 *
 * <p>Populated by the carrier classifier when a service-backed field's payload class has a
 * canonical constructor exposing exactly one parameter assignable from the service method's
 * declared return type. The emitter walks {@code 0..N-1} (where
 * {@code N == 1 + defaultedSlots.size()}) and prints, per slot:
 * <ul>
 *   <li>the captured service-return local at {@code resultSlotIndex}</li>
 *   <li>{@code List.of()} at the channel's errors slot when an {@link ErrorChannel} is also
 *       present (success arm initialises the errors list to empty)</li>
 *   <li>the slot's pre-resolved {@link DefaultedSlot#defaultLiteral()} otherwise</li>
 * </ul>
 *
 * <p>Independent of {@link ErrorChannel}: a service-backed field whose payload has no errors
 * slot still carries a {@code ResultAssembly} (so the success arm constructs the payload
 * around the domain return), but no channel (so the catch arm falls back to
 * {@code ErrorRouter.redact}). When both are present they reference the same payload class;
 * the classifier verifies their slot indices are distinct (errors slot ≠ result slot).
 *
 * <p>Service-backed fields whose service method returns the SDL payload class directly do
 * not get a {@code ResultAssembly}; the emitter passes the service return through unchanged
 * (the legacy shape, before the §2c convergence).
 */
public record ResultAssembly(
    ClassName payloadClass,
    int resultSlotIndex,
    TypeName resultSlotType,
    List<DefaultedSlot> defaultedSlots
) {
    public ResultAssembly {
        defaultedSlots = List.copyOf(defaultedSlots);
        if (resultSlotIndex < 0) {
            throw new IllegalArgumentException(
                "ResultAssembly: resultSlotIndex must be non-negative; got " + resultSlotIndex);
        }
        for (var slot : defaultedSlots) {
            if (slot.index() == resultSlotIndex) {
                throw new IllegalArgumentException(
                    "ResultAssembly: defaultedSlots must not include the result slot at index "
                        + resultSlotIndex + "; got slot for parameter '" + slot.name() + "'");
            }
        }
    }
}
