package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

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
 *       at the catch site by walking {@code payloadCtorParams}.</li>
 *   <li>{@code payloadCtorParams} : the ordered all-fields constructor signature for
 *       {@code payloadClass}, with the errors slot identified and the literal expression for
 *       every other slot pre-resolved. The emitter walks this list to print the synthesized
 *       payload-factory lambda.</li>
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
    List<PayloadConstructorParam> payloadCtorParams,
    String mappingsConstantName
) {

    public ErrorChannel {
        mappedErrorTypes = List.copyOf(mappedErrorTypes);
        payloadCtorParams = List.copyOf(payloadCtorParams);
    }

    /**
     * One parameter on the developer-supplied payload class's all-fields constructor. The
     * classifier records every parameter so the emitter can print a full constructor call at
     * the catch site, defaulting non-error parameters and binding the errors list to the
     * lambda parameter.
     *
     * <ul>
     *   <li>{@code name} : declared parameter name (recorded for diagnostics; matching is
     *       by type assignability, not by name).</li>
     *   <li>{@code type} : the parameter's resolved {@link TypeName}, e.g. a {@code ClassName}
     *       for {@code com.example.Film}, a primitive for {@code long}, or a parameterised
     *       list type for the errors slot.</li>
     *   <li>{@code isErrorsSlot} : exactly one parameter has this set; it is the slot the
     *       lambda parameter binds to. Identified at classify time as the parameter whose
     *       type is assignable from {@code List<? extends GraphitronError>}.</li>
     *   <li>{@code defaultLiteral} : the literal expression to print at this slot when the
     *       lambda is constructed, for non-errors slots only ({@code "null"} for reference
     *       types, {@code "0"} / {@code "0L"} / {@code "0.0"} / {@code "false"} /
     *       {@code "'\\0'"} for primitives). {@code null} when {@code isErrorsSlot} is
     *       {@code true} (the lambda parameter binds there). The classifier resolves the
     *       literal once; the emitter prints it directly without re-deriving from
     *       {@code type}.</li>
     * </ul>
     */
    public record PayloadConstructorParam(
        String name,
        TypeName type,
        boolean isErrorsSlot,
        String defaultLiteral
    ) {
        public PayloadConstructorParam {
            if (isErrorsSlot && defaultLiteral != null) {
                throw new IllegalArgumentException(
                    "PayloadConstructorParam: errors slot must not carry a defaultLiteral "
                        + "(the lambda parameter binds there); got '" + defaultLiteral
                        + "' for parameter '" + name + "'");
            }
            if (!isErrorsSlot && defaultLiteral == null) {
                throw new IllegalArgumentException(
                    "PayloadConstructorParam: non-errors slot '" + name
                        + "' must carry a defaultLiteral");
            }
        }
    }
}
