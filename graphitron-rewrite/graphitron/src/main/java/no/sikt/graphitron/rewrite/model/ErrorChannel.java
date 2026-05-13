package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;

/**
 * The carrier-side typed-error wiring for one fetcher. Resolved at classify time and attached
 * to fetcher-emitting field variants via {@link WithErrorChannel}; the emitter consumes it to
 * synthesize the per-fetcher try/catch wrapper that routes thrown exceptions into the
 * payload's {@code errors} field. See {@code error-handling-parity.md} for the full contract.
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
 *       {@code com.example.FilmPayload}). The emitter constructs the payload instance at the
 *       catch site by dispatching on {@code errorsSlot}: the {@link ErrorsSlot.CtorParameterIndex}
 *       arm prints {@code new FilmPayload(...)} with the lambda parameter at the ctor index;
 *       the phase-2 setter arm prints {@code var p = new FilmPayload(); p.setErrors(errors);
 *       ...; return p;}.</li>
 *   <li>{@code errorsSlot} : where the errors list is bound on the payload. Sealed over the
 *       all-fields-ctor parameter index (phase 1) and the bean-setter method (phase 2);
 *       resolved by {@code FieldBuilder.resolvePayloadConstructionShape} once at classify time
 *       so each emitter dispatches on the arm without re-deriving.</li>
 *   <li>{@code defaultedSlots} : every constructor parameter except the errors slot, paired
 *       with its pre-resolved language default literal. Used by the all-fields-ctor arm of
 *       {@code errorsSlot} to fill non-errors slots positionally; carried unchanged from
 *       legacy. Under the phase-2 setter arm the list captures the per-non-errors-SDL-field
 *       defaults keyed by {@link DefaultedSlot#index()} so the emitter walks identical
 *       structured information either way.</li>
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
    ErrorsSlot errorsSlot,
    List<DefaultedSlot> defaultedSlots,
    String mappingsConstantName
) {

    public ErrorChannel {
        mappedErrorTypes = List.copyOf(mappedErrorTypes);
        defaultedSlots = List.copyOf(defaultedSlots);
        if (errorsSlot == null) {
            throw new IllegalArgumentException("ErrorChannel: errorsSlot must be non-null");
        }
        if (errorsSlot instanceof ErrorsSlot.CtorParameterIndex cpi) {
            for (var slot : defaultedSlots) {
                if (slot.index() == cpi.index()) {
                    throw new IllegalArgumentException(
                        "ErrorChannel: defaultedSlots must not include the errors slot at index "
                            + cpi.index() + "; got slot for parameter '" + slot.name() + "'");
                }
            }
        }
    }
}
