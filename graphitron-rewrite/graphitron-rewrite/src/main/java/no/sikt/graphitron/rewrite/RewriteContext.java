package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.schema.input.SchemaInput;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-invocation configuration the rewrite generator runs against.
 *
 * <p>Constructed once by the Mojo and flowed through the entire pipeline. Never held in a static
 * or {@link ThreadLocal}; it travels through
 * {@link GraphQLRewriteGenerator#GraphQLRewriteGenerator(RewriteContext)} and is accessible to
 * every pipeline stage through the generator instance.
 */
public record RewriteContext(
    List<SchemaInput> schemaInputs,
    Path basedir,
    Path outputDirectory,
    String outputPackage,
    String jooqPackage,
    Map<String, String> namedReferences
) {
    public RewriteContext {
        Objects.requireNonNull(schemaInputs, "schemaInputs");
        Objects.requireNonNull(basedir, "basedir");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(outputPackage, "outputPackage");
        Objects.requireNonNull(jooqPackage, "jooqPackage");
        Objects.requireNonNull(namedReferences, "namedReferences");
        schemaInputs = List.copyOf(schemaInputs);
        namedReferences = Map.copyOf(namedReferences);
    }
}
