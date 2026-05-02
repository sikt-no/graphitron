package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;

/**
 * The carrier-side typed-error wiring for one fetcher. Resolved at classify time and attached
 * to fetcher-emitting field variants via {@link WithErrorChannel}; the emitter consumes it to
 * synthesize the per-fetcher try/catch wrapper that routes thrown exceptions into the
 * payload's {@code errors} field. See {@code error-handling-parity.md} (R12) for the full
 * contract.
 *
 * <p>Carrier-side counterpart of the payload-side
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ErrorsField}: where {@code ErrorsField}
 * names the payload field that holds the list of typed errors at request time, this record
 * captures everything the emitter needs to construct that list : which {@code @error} types
 * are mapped, which payload class to instantiate, and the constant on the per-package
 * {@code ErrorMappings} helper that holds the dispatch table.
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@code mappedErrorTypes} : the resolved {@code @error} types this channel routes to,
 *       in source order. A single-element list for {@code [SomeError]} payload shapes; a
 *       multi-element list for unions and interfaces of {@code @error} types.</li>
 *   <li>{@code payloadClass} : the developer-supplied payload class (e.g.
 *       {@code com.example.FilmPayload}). The emitter constructs {@code new FilmPayload(...)}
 *       at the catch site by walking the constructor's slots positionally: at
 *       {@code errorsSlotIndex} it prints the lambda parameter; at every other slot it looks
 *       up the slot's pre-resolved {@link DefaultedSlot#defaultLiteral()}.</li>
 *   <li>{@code errorsSlotIndex} : the index of the errors-list slot in the payload class's
 *       canonical constructor's parameter list. Identified positionally: the SDL field that
 *       classifies as {@link no.sikt.graphitron.rewrite.model.ChildField.ErrorsField} has an
 *       index in the payload type's field declaration order, and the canonical constructor's
 *       parameters follow that order. (Records preserve declaration order; hand-rolled POJOs
 *       are expected to expose a canonical constructor matching SDL order.)</li>
 *   <li>{@code defaultedSlots} : every constructor parameter except the errors slot, paired
 *       with its pre-resolved language default literal. Together with {@code errorsSlotIndex}
 *       they cover the constructor's full parameter list; the indices form a partition of
 *       {@code 0..ctor.parameterCount-1}.</li>
 *   <li>{@code mappingsConstantName} : the name of the {@code Mapping[]} constant on the
 *       per-package {@code ErrorMappings} helper that holds this channel's dispatch table
 *       (e.g. {@code "FILM_PAYLOAD"}). Distinct channels with identical mappings dedup to
 *       the same constant; identical-shape channels with different mappings get a hash
 *       suffix (see {@code error-handling-parity.md} §3).</li>
 * </ul>
 */
public record ErrorChannel(
    List<GraphitronType.ErrorType> mappedErrorTypes,
    ClassName payloadClass,
    int errorsSlotIndex,
    List<DefaultedSlot> defaultedSlots,
    String mappingsConstantName
) {

    public ErrorChannel {
        mappedErrorTypes = List.copyOf(mappedErrorTypes);
        defaultedSlots = List.copyOf(defaultedSlots);
        if (errorsSlotIndex < 0) {
            throw new IllegalArgumentException(
                "ErrorChannel: errorsSlotIndex must be non-negative; got " + errorsSlotIndex);
        }
        for (var slot : defaultedSlots) {
            if (slot.index() == errorsSlotIndex) {
                throw new IllegalArgumentException(
                    "ErrorChannel: defaultedSlots must not include the errors slot at index "
                        + errorsSlotIndex + "; got slot for parameter '" + slot.name() + "'");
            }
        }
    }
}
