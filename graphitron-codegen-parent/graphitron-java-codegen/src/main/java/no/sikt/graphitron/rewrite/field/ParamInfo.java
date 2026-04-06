package no.sikt.graphitron.rewrite.field;

/**
 * Reflection data for one parameter of a condition or service method.
 *
 * <p>{@code typeName} is the fully qualified type name (e.g. {@code "org.jooq.DSLContext"}).
 * Used to match parameters by type when binding arguments at code-generation time.
 *
 * <p>{@code paramName} is the parameter name from the compiled class (requires {@code -parameters}).
 * Used to match parameters by name when binding arguments at code-generation time.
 */
public record ParamInfo(String typeName, String paramName) {}
