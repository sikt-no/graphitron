package no.sikt.graphitron.sakila.example.app;

import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphitron.generated.schema.FetcherErrorResponse;

/**
 * The one helper both redaction sites call when a request carries
 * {@link FaultInjectingGraphitronApplication#TRACE_HEADER}: the application's
 * {@code newReference} / {@code redactedMessage} overrides for a fault thrown while building the
 * input, and {@link #RESPONSE} for a fault thrown in a fetcher. A consumer shares a helper the same
 * way, because {@code graphitron-jakarta-rest} cannot see the generated {@code FetcherErrorResponse}.
 */
final class TracedErrorText {

    /** The request-context key the input-building seam puts the trace id under. */
    static final String TRACE_KEY = "conformance.trace-id";

    /** The fetcher side: the trace id as the reference, and the traced text as the message. */
    static final FetcherErrorResponse RESPONSE = new FetcherErrorResponse() {
        @Override
        public String reference(Throwable thrown, DataFetchingEnvironment env) {
            return env.getGraphQlContext().get(TRACE_KEY);
        }

        @Override
        public String unmatchedMessage(Throwable thrown, String reference, DataFetchingEnvironment env) {
            return message(reference);
        }
    };

    private TracedErrorText() {}

    static String message(String reference) {
        return "The request failed. Trace: " + reference + ".";
    }
}
