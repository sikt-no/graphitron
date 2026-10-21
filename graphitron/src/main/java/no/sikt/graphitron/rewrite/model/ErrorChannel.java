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
 * captures everything the emitter needs to construct that list: which {@code @error} types
 * are mapped, and the constant on the per-package {@code ErrorMappings} helper that holds the
 * dispatch table. A producer's catch hands what it claims back on the {@code Outcome} wrapper's
 * error arm; {@link #mappedErrorTypes()} and {@link #mappingsConstantName()} are what
 * {@code MappingsConstantNameDedup}, {@code ErrorMappingsClassGenerator} and
 * {@code CheckedExceptionMatcher} read.
 */
public sealed interface ErrorChannel permits ErrorChannel.Mapped {

    /**
     * The resolved {@code @error} types this channel routes to, in source order. A
     * single-element list for {@code [SomeError]} payload shapes; a multi-element list for
     * unions and interfaces of {@code @error} types.
     */
    List<GraphitronType.ErrorType> mappedErrorTypes();

    /**
     * The name of the {@code Mapping[]} constant on the per-package {@code ErrorMappings} helper
     * that holds this channel's dispatch table (e.g. {@code "FILM_PAYLOAD"}). Distinct channels
     * with identical mappings dedup to the same constant; identical-shape channels with
     * different mappings get a hash suffix.
     */
    String mappingsConstantName();

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
     * The classify-time descriptor of which {@code @error} types map and which
     * {@code ErrorMappings} constant drives them; it drives the {@code Outcome} wrapper emit (the
     * success path wraps the jOOQ record in {@code Success}, the error path returns
     * {@code ErrorList(matchedErrors)}). No developer payload class is constructed or reflected on.
     *
     * <p>Every producer of an outcome rides it, at a root or below one: an {@code @service} field,
     * a DML carrier ({@code @mutation} returning a payload) and a {@code @routine} write carrier. The {@code mappingsConstantName} for the structural carriers
     * is minted from the payload's SDL name, no developer payload class being consulted.
     */
    record Mapped(
        List<GraphitronType.ErrorType> mappedErrorTypes,
        String mappingsConstantName
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
