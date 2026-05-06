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
 *
 * @param classpathRoots compile-output directories the LSP catalog scans for service /
 *                       condition / record class candidates. Populated from every reactor
 *                       project's {@code ${project.build.outputDirectory}} when the mojo runs
 *                       inside Maven; empty for unit-tier callers that don't ship classes.
 *                       External jars (from {@code ~/.m2}) are not scanned: services live in
 *                       reactor source, not third-party libraries.
 */
public record RewriteContext(
    List<SchemaInput> schemaInputs,
    Path basedir,
    Path outputDirectory,
    String outputPackage,
    String jooqPackage,
    Map<String, String> namedReferences,
    List<Path> classpathRoots
) {
    public RewriteContext {
        Objects.requireNonNull(schemaInputs, "schemaInputs");
        Objects.requireNonNull(basedir, "basedir");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(outputPackage, "outputPackage");
        Objects.requireNonNull(jooqPackage, "jooqPackage");
        Objects.requireNonNull(namedReferences, "namedReferences");
        Objects.requireNonNull(classpathRoots, "classpathRoots");
        schemaInputs = List.copyOf(schemaInputs);
        namedReferences = Map.copyOf(namedReferences);
        classpathRoots = List.copyOf(classpathRoots);
    }

    /**
     * Six-arg overload for unit-tier callers that don't care about classpath
     * scanning. Defaults {@code classpathRoots} to the empty list.
     */
    public RewriteContext(
        List<SchemaInput> schemaInputs,
        Path basedir,
        Path outputDirectory,
        String outputPackage,
        String jooqPackage,
        Map<String, String> namedReferences
    ) {
        this(schemaInputs, basedir, outputDirectory, outputPackage, jooqPackage,
            namedReferences, List.of());
    }
}

