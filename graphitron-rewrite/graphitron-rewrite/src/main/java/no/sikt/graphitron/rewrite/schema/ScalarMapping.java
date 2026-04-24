package no.sikt.graphitron.rewrite.schema;

/**
 * Maps a GraphQL scalar name to its Java implementation class.
 *
 * <p>Supplied via {@code <scalars>} in the plugin configuration and carried on
 * {@link no.sikt.graphitron.rewrite.RewriteContext#scalars()}.
 */
public record ScalarMapping(String scalarName, String className) {}
