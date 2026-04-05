package no.sikt.graphitron.record.field;

/**
 * A reference to an external Java class and optional method, as specified by an
 * {@code ExternalCodeReference} input object in the GraphQL schema (e.g. the {@code service:}
 * argument of {@code @service} or the {@code tableMethodReference:} argument of
 * {@code @tableMethod}).
 *
 * <p>{@code className} is the value of the {@code className} field of the
 * {@code ExternalCodeReference} input object. May be a short name (if the class is listed in
 * the plugin's {@code externalReferences} configuration) or a fully qualified class name.
 *
 * <p>{@code methodName} is the value of the {@code method} field of the input object, or
 * {@code null} when not specified.
 */
public record ExternalRef(String className, String methodName) {}
