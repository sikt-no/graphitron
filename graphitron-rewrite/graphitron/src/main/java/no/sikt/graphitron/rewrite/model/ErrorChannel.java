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
 * names the payload field that holds the list of typed errors at request time, this carrier
 * captures everything the emitter needs to construct that list : which {@code @error} types
 * are mapped, which payload class to instantiate, and the constant on the per-package
 * {@code ErrorMappings} helper that holds the dispatch table.
 *
 * <h2>Sealed split</h2>
 *
 * Two arms cover the two ways the catch-side ferry can hand errors back to graphql-java:
 * {@link PayloadClass} construction (the catch arm builds a developer payload class with the
 * errors list slotted in) and {@link LocalContext} (the catch arm emits
 * {@code data(null).localContext(errorsList).build()} and the errors-field DataFetcher reads
 * from {@code env.getLocalContext()}). Both arms share the {@link #mappedErrorTypes()} and
 * {@link #mappingsConstantName()} accessors, which is what the channel-agnostic consumers
 * ({@code MappingsConstantNameDedup}, {@code ErrorMappingsClassGenerator},
 * {@code CheckedExceptionMatcher}) need.
 */
public sealed interface ErrorChannel permits ErrorChannel.Mapped, ErrorChannel.PayloadClass, ErrorChannel.LocalContext {

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
     * different mappings get a hash suffix (see {@code error-handling-parity.md} §3).
     */
    String mappingsConstantName();

    /**
     * NEW (R244) ; replaces the {@link PayloadClass} arm for {@code @service} / {@code @tableMethod}
     * outcome fields. The classify-time descriptor of which {@code @error} types map and which
     * {@code ErrorMappings} constant drives them; it drives the {@code Outcome} wrapper emit (the
     * success path wraps the jOOQ record in {@code Success}, the error path returns
     * {@code ErrorList(matchedErrors)}). No developer payload class is constructed or reflected on.
     *
     * <p>Carries the same two fields as {@link LocalContext} by coincidence; they are distinct
     * types because they drive different emit ({@code Mapped} → {@code Outcome},
     * {@code LocalContext} → sentinel + {@code dispatchToLocalContext}).
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
     * The catch arm constructs a developer payload class and slots the errors list into it.
     * Active for every fetcher whose payload return is a class-backed shape today;
     * after R161 this arm is the only one for service-backed paths
     * ({@code MutationServiceTableField}, {@code MutationServiceRecordField},
     * {@code QueryServiceTableField}, {@code QueryServiceRecordField}) and child
     * {@code @tableMethod} / child {@code @service} variants.
     *
     * <ul>
     *   <li>{@code payloadClass} : the developer-supplied payload class (e.g.
     *       {@code com.example.FilmPayload}). The emitter constructs the payload instance at
     *       the catch site by dispatching on {@code errorsSlot}: the
     *       {@link ErrorsSlot.CtorParameterIndex} arm prints {@code new FilmPayload(...)} with
     *       the lambda parameter at the ctor index; the phase-2 setter arm prints
     *       {@code var p = new FilmPayload(); p.setErrors(errors); ...; return p;}.</li>
     *   <li>{@code errorsSlot} : where the errors list is bound on the payload. Sealed over the
     *       all-fields-ctor parameter index (phase 1) and the bean-setter method (phase 2);
     *       resolved by {@code FieldBuilder.resolvePayloadConstructionShape} once at classify
     *       time so each emitter dispatches on the arm without re-deriving.</li>
     *   <li>{@code defaultedSlots} : every constructor parameter except the errors slot, paired
     *       with its pre-resolved language default literal. Used by the all-fields-ctor arm of
     *       {@code errorsSlot} to fill non-errors slots positionally; under the phase-2 setter
     *       arm the list captures the per-non-errors-SDL-field defaults keyed by
     *       {@link DefaultedSlot#index()} so the emitter walks identical structured information
     *       either way.</li>
     * </ul>
     */
    record PayloadClass(
        List<GraphitronType.ErrorType> mappedErrorTypes,
        ClassName payloadClass,
        ErrorsSlot errorsSlot,
        List<DefaultedSlot> defaultedSlots,
        String mappingsConstantName
    ) implements ErrorChannel {

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

    /**
     * The catch arm hands errors back through graphql-java's {@code DataFetcherResult.localContext}.
     * Produced by {@code FieldBuilder.detectStructuralDmlErrorChannel} on a wrapper shaped
     * {@code { data: X, errors: [SomeError!]! }}, where the errors-field side of the carrier
     * binds directly to this arm. This is the unified DML error-channel transport used by
     * {@code MutationDmlRecordField} / {@code MutationBulkDmlRecordField}.
     *
     * <p>The catch arm emits a pattern that consults the channel's mapping table via
     * {@code ErrorRouter.dispatchToLocalContext(throwable, mappings, env, sentinel)} and returns
     * {@code DataFetcherResult.<R>newResult().data(sentinel).localContext(List.of(t)).build()}
     * on the first match, falling through to {@code ErrorRouter.redact} on no match.
     *
     * <p>The {@code sentinel} is a non-null structurally-valid source whose every column is
     * null (a jOOQ {@code Record1}/{@code Record2}/{@code Result} constructed via
     * {@code DSL.using(SQLDialect.DEFAULT).newRecord(...)} or {@code newResult(...)}). The
     * non-null source is required because graphql-java's {@code completeValueForObject}
     * short-circuits children on a null parent value: {@code data: null} would prevent the
     * carrier's children from being fetched at all, so the {@code errors} field would never
     * resolve. With the sentinel in place the data field's null-PK SELECT naturally renders
     * {@code null} (jOOQ's {@code WHERE pk = null} resolves to no row) and the errors-field
     * DataFetcher reads the throwable list via {@code env.getLocalContext()}.
     *
     * <p>The bare {@code mappingsConstantName} comes from
     * {@code SCREAMING_SNAKE(wrapperSdlTypeName)} (e.g. {@code FilmPayload} →
     * {@code FILM_PAYLOAD}) rather than from a payload-class simple name; no developer payload
     * class is consulted on the catch path.
     */
    record LocalContext(
        List<GraphitronType.ErrorType> mappedErrorTypes,
        String mappingsConstantName
    ) implements ErrorChannel {

        public LocalContext {
            mappedErrorTypes = List.copyOf(mappedErrorTypes);
        }
    }
}
