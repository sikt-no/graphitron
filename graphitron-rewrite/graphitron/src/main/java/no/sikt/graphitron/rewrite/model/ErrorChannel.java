package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The carrier-side typed-error wiring for one fetcher. Resolved at classify time and attached
 * to fetcher-emitting field variants as {@code Optional<ErrorChannel>}; the emitter consumes
 * it to synthesize the per-fetcher try/catch wrapper that routes thrown exceptions into the
 * payload's {@code errors} field. See {@code error-handling-parity.md} (R12) for the full
 * contract.
 *
 * <p>Carrier-side counterpart of the payload-side
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ErrorsField}: where {@code ErrorsField}
 * names the payload field that holds the list of typed errors at request time, this record
 * captures everything the emitter needs to construct that list — which {@code @error} types
 * are mapped, which payload class to instantiate, and the constant on the per-package
 * {@code ErrorMappings} helper that holds the dispatch table.
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@code errorsFieldName} — the name of the {@code errors}-shaped field on the payload
 *       (matched structurally; the legacy convention is {@code errors:} but the rewrite keys
 *       off the structural relationship to {@code @error} types, not a hardcoded name).</li>
 *   <li>{@code mappedErrorTypes} — the resolved {@code @error} types this channel routes to,
 *       in source order. A single-element list for {@code [SomeError]} payload shapes; a
 *       multi-element list for unions and interfaces of {@code @error} types.</li>
 *   <li>{@code payloadClassName} — the fully qualified Java class name of the developer-supplied
 *       payload class (e.g. {@code "com.example.FilmPayload"}). The emitter constructs
 *       {@code new FilmPayload(...)} at the catch site.</li>
 *   <li>{@code payloadCtorParams} — the ordered all-fields constructor signature for
 *       {@code payloadClassName}, with the errors slot identified. The emitter walks this
 *       list to print the synthesized payload-factory lambda; non-error parameters print
 *       their language default, the errors slot prints the lambda parameter.</li>
 *   <li>{@code mappingsConstantName} — the name of the {@code Mapping[]} constant on the
 *       per-package {@code ErrorMappings} helper that holds this channel's dispatch table
 *       (e.g. {@code "FILM_PAYLOAD"}). Distinct channels with identical mappings dedup to
 *       the same constant; identical-shape channels with different mappings get a hash
 *       suffix (see {@code error-handling-parity.md} §3).</li>
 * </ul>
 */
public record ErrorChannel(
    String errorsFieldName,
    List<ErrorTypeRef> mappedErrorTypes,
    String payloadClassName,
    List<PayloadConstructorParam> payloadCtorParams,
    String mappingsConstantName
) {

    public ErrorChannel {
        mappedErrorTypes = List.copyOf(mappedErrorTypes);
        payloadCtorParams = List.copyOf(payloadCtorParams);
    }

    /**
     * One parameter on the developer-supplied payload class's all-fields constructor.
     * The classifier records every parameter so the emitter can print a full constructor
     * call at the catch site, defaulting non-error parameters and binding the errors list
     * to the lambda parameter.
     *
     * <ul>
     *   <li>{@code name} — declared parameter name (recorded for diagnostics; matching is
     *       by type assignability, not by name).</li>
     *   <li>{@code typeDescriptor} — type descriptor in source form, e.g.
     *       {@code "com.example.Film"} for a reference type, {@code "long"} for a primitive,
     *       {@code "java.util.List<? extends GraphitronError>"} for the errors slot. The
     *       C3 emitter lifts this to a JavaPoet {@code TypeName} when printing.</li>
     *   <li>{@code isErrorsSlot} — exactly one parameter has this set; it is the slot the
     *       lambda parameter binds to. Identified at classify time as the parameter whose
     *       type is assignable from {@code List<? extends GraphitronError>}.</li>
     * </ul>
     */
    public record PayloadConstructorParam(String name, String typeDescriptor, boolean isErrorsSlot) {}
}
