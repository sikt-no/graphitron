package no.sikt.graphitron.rewrite.type;

import no.sikt.graphitron.configuration.ErrorHandlerType;

/**
 * Captures the data of a single handler entry in the {@code @error} directive.
 *
 * @param handlerType the kind of handler (DATABASE or GENERIC)
 * @param className   fully-qualified exception class name; may be {@code null} for DATABASE handlers
 * @param code        database error code; may be {@code null}
 * @param sqlState    SQL state code; may be {@code null}
 * @param matches     substring the exception message must contain; may be {@code null}
 * @param description user-facing error description; may be {@code null}
 */
public record ErrorHandlerSpec(
    ErrorHandlerType handlerType,
    String className,
    String code,
    String sqlState,
    String matches,
    String description
) {}
