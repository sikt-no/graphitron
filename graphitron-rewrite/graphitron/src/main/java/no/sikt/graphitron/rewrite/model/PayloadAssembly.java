package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * Carrier-side recipe for emitting a typed payload-class constructor call on the success arm of
 * a DML mutation fetcher. Where {@link ErrorChannel} captures the catch-arm wiring (which
 * {@code @error} types route here, which constant on {@code ErrorMappings} holds the dispatch
 * table), this record captures the success-arm wiring: which payload class to instantiate, the
 * row slot the SQL row record binds to, and the defaulted slots for everything else.
 *
 * <p>Populated by the carrier classifier when a DML mutation field returns a {@code @record}
 * payload type whose resolved {@link PayloadConstructionShape} exposes one slot assignable from
 * the DML's table record. The emitter dispatches on {@code rowSlot}:
 * <ul>
 *   <li>{@link RowSlot.CtorParameterIndex} : walk constructor slots positionally, printing the
 *       row record local at {@code rowSlot.index()} and the slot's pre-resolved
 *       {@link DefaultedSlot#defaultLiteral()} otherwise (this covers any errors slot too; on
 *       the success arm the errors list is {@code null}).</li>
 *   <li>Phase-2 setter arm : no-arg-construct then invoke the row setter with the row record
 *       local; non-row setters are not invoked (the bean's default field values stand).</li>
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
    RowSlot rowSlot,
    TypeName rowSlotType,
    List<DefaultedSlot> defaultedSlots
) {
    public PayloadAssembly {
        defaultedSlots = List.copyOf(defaultedSlots);
        if (rowSlot == null) {
            throw new IllegalArgumentException("PayloadAssembly: rowSlot must be non-null");
        }
        if (rowSlot instanceof RowSlot.CtorParameterIndex cpi) {
            for (var slot : defaultedSlots) {
                if (slot.index() == cpi.index()) {
                    throw new IllegalArgumentException(
                        "PayloadAssembly: defaultedSlots must not include the row slot at index "
                            + cpi.index() + "; got slot for parameter '" + slot.name() + "'");
                }
            }
        }
    }
}
