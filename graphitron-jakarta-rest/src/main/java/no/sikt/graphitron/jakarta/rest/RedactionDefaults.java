package no.sikt.graphitron.jakarta.rest;

import java.util.UUID;

/**
 * The library's one spelling of the reference and text a redacted fault gets by default: the
 * defaults of {@link GraphitronApplication#newReference(Throwable)} and
 * {@link GraphitronApplication#redactedMessage(Throwable, String)}, and what
 * {@link GraphqlHttpHandler} falls back to when an override of either fails. They match the
 * generated {@code FetcherErrorResponse} defaults, which this module compiles before and cannot
 * name; the conformance suite's redaction case keeps the two in step.
 */
final class RedactionDefaults {

    private RedactionDefaults() {}

    static String reference() {
        return UUID.randomUUID().toString();
    }

    static String message(String reference) {
        return "An error occurred. Reference: " + reference + ".";
    }
}
