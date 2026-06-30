package no.sikt.graphitron.jakarta.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The GraphQL-over-HTTP request body shape: {@code query}, {@code operationName}, {@code variables},
 * {@code extensions}. Parsed from the raw request body by {@link GraphqlResource}'s {@code ObjectMapper};
 * wire-format decoding stays at the HTTP boundary, never in the model.
 *
 * <p>Unknown top-level members are ignored rather than rejected, per the spec: "The server SHOULD
 * ignore [reserved/unknown] request parameters it does not understand."
 *
 * @param query         the GraphQL document; required for a well-formed request
 * @param operationName the operation to run when the document defines several; may be {@code null}
 * @param variables     variable values for the operation; may be {@code null}
 * @param extensions    implementation-specific request metadata; may be {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphqlRequest(
    String query,
    String operationName,
    Map<String, Object> variables,
    Map<String, Object> extensions) {
}
