package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The carrier-side typed-error wiring for one fetcher. Resolved at classify time and attached
 * to fetcher-emitting field variants via {@link WithErrorChannel}; the emitter consumes it to
 * synthesize the per-fetcher try/catch wrapper that routes thrown exceptions into the
 * payload's {@code errors} field.
 *
 * <p>Carrier-side counterpart of the payload-side
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ErrorsField}: where {@code ErrorsField}
 * names the payload field that holds the list of typed errors at request time, this carrier
 * records which {@code @error} types are mapped. A producer's catch hands what it claims back on
 * the {@code Outcome} wrapper's error arm, through the {@code ErrorMappings} constant of its outcome
 * type, which the generator names from the store's {@code graphitron_producer_outcome}.
 */
public sealed interface ErrorChannel permits ErrorChannel.Mapped {

    /**
     * The resolved {@code @error} types this channel routes to, in source order. A
     * single-element list for {@code [SomeError]} payload shapes; a multi-element list for
     * unions and interfaces of {@code @error} types.
     */
    List<GraphitronType.ErrorType> mappedErrorTypes();

    /**
     * Whether any handler on any mapped {@code @error} type is a {@code VALIDATION} handler: the
     * channel can receive a pre-execution validation error, which is what turns the pre-execution
     * checks on.
     */
    default boolean hasValidationHandler() {
        return mappedErrorTypes().stream()
            .flatMap(et -> et.handlers().stream())
            .anyMatch(h -> h instanceof GraphitronType.ErrorType.ValidationHandler);
    }

    /**
     * The classify-time descriptor of which {@code @error} types map; it drives the
     * {@code Outcome} wrapper emit (the
     * success path wraps the jOOQ record in {@code Success}, the error path returns
     * {@code ErrorList(matchedErrors)}). No developer payload class is constructed or reflected on.
     *
     * <p>Every producer of an outcome rides it, at a root or below one: an {@code @service} field,
     * a DML carrier ({@code @mutation} returning a payload) and a {@code @routine} write carrier.
     */
    record Mapped(
        List<GraphitronType.ErrorType> mappedErrorTypes
    ) implements ErrorChannel {
        public Mapped {
            mappedErrorTypes = List.copyOf(mappedErrorTypes);
            if (mappedErrorTypes.isEmpty()) {
                throw new IllegalArgumentException(
                    "ErrorChannel.Mapped: mappedErrorTypes must be non-empty");
            }
        }
    }
}
