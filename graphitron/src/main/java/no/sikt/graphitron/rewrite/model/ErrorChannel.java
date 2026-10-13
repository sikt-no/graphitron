package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

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
 * are mapped, which payload class to instantiate, and the constant on the per-package
 * {@code ErrorMappings} helper that holds the dispatch table.
 *
 * <p>The arms differ in how the catch-side ferry hands errors back to graphql-java; the
 * shared {@link #mappedErrorTypes()} and {@link #mappingsConstantName()} accessors are what
 * the channel-agnostic consumers ({@code MappingsConstantNameDedup},
 * {@code ErrorMappingsClassGenerator}, {@code CheckedExceptionMatcher}) need.
 */
public sealed interface ErrorChannel permits ErrorChannel.Mapped, ErrorChannel.RouterDispatched {

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
     * <p>Every root producer of a payload the classifier recognises structurally rides this arm:
     * a root {@code @service} field, a DML carrier ({@code @mutation} returning a payload) and a
     * {@code @routine} write carrier. The {@code mappingsConstantName} for the structural carriers
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

    /**
     * The {@link ErrorChannel} partition whose catch-side ferry dispatches through the generated
     * {@code ErrorRouter}'s {@code dispatch}, emitted by the sync {@code catchArm} and async
     * {@code asyncRouterCall} seams in
     * {@link no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator}. {@link Mapped} sits
     * outside this partition: it rides the typed {@code Outcome} wrapper and is emitted solely by
     * {@link no.sikt.graphitron.rewrite.generators.ChannelCatchArmEmitter}. The permits clause is
     * the compiler-checked membership; a field variant whose channel slot declares this type
     * cannot hand a {@code Mapped} channel to the router seams.
     */
    sealed interface RouterDispatched extends ErrorChannel permits PayloadClass {}

    /**
     * The catch arm constructs a developer payload class and slots the errors list into it.
     * Active for every fetcher whose payload return is a class-backed shape; it is the only
     * arm for service-backed paths
     * ({@code MutationServiceTableField}, {@code MutationServiceRecordField},
     * {@code QueryServiceTableField}, {@code QueryServiceRecordField}) and child
     * child {@code @service} variants.
     *
     * <ul>
     *   <li>{@code payloadClass}: the developer-supplied payload class (e.g.
     *       {@code com.example.FilmPayload}). The emitter constructs the payload instance at
     *       the catch site by dispatching on {@code errorsSlot}: the
     *       {@link ErrorsSlot.CtorParameterIndex} arm prints {@code new FilmPayload(...)} with
     *       the lambda parameter at the ctor index; the {@link ErrorsSlot.SetterMethod} arm
     *       prints {@code var p = new FilmPayload(); p.setErrors(errors); ...; return p;}.</li>
     *   <li>{@code errorsSlot}: where the errors list is bound on the payload, sealed over the
     *       all-fields-ctor parameter index and the bean-setter method. Resolved by
     *       {@code FieldBuilder.resolvePayloadConstructionShape} once at classify
     *       time so each emitter dispatches on the arm without re-deriving.</li>
     *   <li>{@code defaultedSlots}: every constructor parameter except the errors slot, paired
     *       with its pre-resolved language default literal. The ctor-index arm fills non-errors
     *       slots positionally from it; under the setter arm the list captures the
     *       per-non-errors-SDL-field defaults keyed by {@link DefaultedSlot#index()}, so the
     *       emitter walks identical structured information either way.</li>
     * </ul>
     */
    record PayloadClass(
        List<GraphitronType.ErrorType> mappedErrorTypes,
        ClassName payloadClass,
        ErrorsSlot errorsSlot,
        List<DefaultedSlot> defaultedSlots,
        String mappingsConstantName
    ) implements RouterDispatched {

        public PayloadClass {
            mappedErrorTypes = List.copyOf(mappedErrorTypes);
            defaultedSlots = List.copyOf(defaultedSlots);
            if (errorsSlot == null) {
                throw new IllegalArgumentException("ErrorChannel.PayloadClass: errorsSlot must be non-null");
            }
            if (errorsSlot instanceof ErrorsSlot.CtorParameterIndex cpi) {
                for (var slot : defaultedSlots) {
                    if (slot.index() == cpi.index()) {
                        throw new IllegalArgumentException(
                            "ErrorChannel.PayloadClass: defaultedSlots must not include the errors slot at index "
                                + cpi.index() + "; got slot for parameter '" + slot.name() + "'");
                    }
                }
            }
        }
    }
}
