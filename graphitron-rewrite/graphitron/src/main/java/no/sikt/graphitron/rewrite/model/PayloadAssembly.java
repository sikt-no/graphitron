package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * Carrier-side recipe for emitting a typed payload-class constructor call on the success arm of
 * a DML mutation fetcher. Where {@link ErrorChannel} captures the catch-arm wiring (which
 * {@code @error} types route here, which constant on {@code ErrorMappings} holds the dispatch
 * table), this record captures the success-arm wiring: which payload class to instantiate, the
 * row-slot index the SQL row record binds to, and the defaulted slots for everything else.
 *
 * <p>Populated by the carrier classifier when a DML mutation field returns a {@code @record}
 * payload type whose all-fields constructor exposes one parameter assignable from the DML's
 * table record. The emitter walks the constructor's parameter indices {@code 0..N-1} (where
 * {@code N == 1 + defaultedSlots.size()}) and prints, per slot:
 * <ul>
 *   <li>the row record local variable when the slot index equals {@code rowSlotIndex}</li>
 *   <li>the slot's pre-resolved {@link DefaultedSlot#defaultLiteral()} otherwise (this covers
 *       any errors slot too; on the success arm the errors list is {@code null})</li>
 * </ul>
 *
 * <p>Independent of {@link ErrorChannel}: a DML payload return without an errors-shaped field
 * carries a {@code PayloadAssembly} (so the success arm constructs the payload) but no channel
 * (so the catch arm falls back to {@code ErrorRouter.redact}). A DML payload return with an
 * errors-shaped field carries both, both resolved by one carrier-classifier reflection pass.
 *
 * <p>Service-mutation and query-service fetchers do not carry a {@code PayloadAssembly}; the
 * developer-supplied service method returns the payload directly, so the emitter has no
 * constructor call to synthesize. The analogous wiring for those fetchers lives in
 * {@link ResultAssembly}.
 */
public record PayloadAssembly(
    ClassName payloadClass,
    int rowSlotIndex,
    TypeName rowSlotType,
    List<DefaultedSlot> defaultedSlots
) {
    public PayloadAssembly {
        defaultedSlots = List.copyOf(defaultedSlots);
        if (rowSlotIndex < 0) {
            throw new IllegalArgumentException(
                "PayloadAssembly: rowSlotIndex must be non-negative; got " + rowSlotIndex);
        }
        for (var slot : defaultedSlots) {
            if (slot.index() == rowSlotIndex) {
                throw new IllegalArgumentException(
                    "PayloadAssembly: defaultedSlots must not include the row slot at index "
                        + rowSlotIndex + "; got slot for parameter '" + slot.name() + "'");
            }
        }
    }
}
