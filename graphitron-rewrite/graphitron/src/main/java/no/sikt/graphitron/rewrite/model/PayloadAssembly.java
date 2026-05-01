package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.ErrorChannel.PayloadConstructorParam;

import java.util.List;

/**
 * Carrier-side recipe for emitting a typed payload-class constructor call on the success arm of
 * a DML mutation fetcher. Where {@link ErrorChannel} captures the catch-arm wiring (which
 * {@code @error} types route here, which constant on {@code ErrorMappings} holds the dispatch
 * table), this record captures the success-arm wiring: which payload class to instantiate, the
 * ordered constructor signature, and the index of the slot the SQL row record binds to.
 *
 * <p>Populated by the carrier classifier when a DML mutation field returns a {@code @record}
 * payload type whose all-fields constructor exposes one parameter assignable from the DML's
 * table record. The emitter walks {@code params} and prints, per slot:
 * <ul>
 *   <li>the row record local variable when the slot index equals {@code rowSlotIndex},</li>
 *   <li>{@code null} when the slot is the errors slot ({@code isErrorsSlot}; success-arm has
 *       no errors to attach),</li>
 *   <li>the slot's pre-resolved {@code defaultLiteral} otherwise.</li>
 * </ul>
 *
 * <p>Independent of {@link ErrorChannel}: a DML payload return without an errors-shaped field
 * still carries a {@code PayloadAssembly} (so the success arm constructs the payload), but no
 * channel (so the catch arm falls back to {@code ErrorRouter.redact}). A DML payload return
 * with an errors-shaped field carries both: the same constructor signature is captured on each
 * record because the carrier classifier resolves them from one reflection pass.
 *
 * <p>Service-mutation and query-service fetchers do not carry a {@code PayloadAssembly}; the
 * developer-supplied service method returns the payload directly, so the emitter has no
 * constructor call to synthesize.
 */
public record PayloadAssembly(
    ClassName payloadClass,
    List<PayloadConstructorParam> params,
    int rowSlotIndex
) {
    public PayloadAssembly {
        params = List.copyOf(params);
        if (rowSlotIndex < 0 || rowSlotIndex >= params.size()) {
            throw new IllegalArgumentException(
                "PayloadAssembly: rowSlotIndex " + rowSlotIndex
                    + " is out of bounds for params list of size " + params.size());
        }
        var rowSlot = params.get(rowSlotIndex);
        if (rowSlot.isErrorsSlot()) {
            throw new IllegalArgumentException(
                "PayloadAssembly: row slot at index " + rowSlotIndex
                    + " (parameter '" + rowSlot.name() + "') is also marked as the errors slot; "
                    + "a single parameter cannot serve both roles");
        }
    }
}
