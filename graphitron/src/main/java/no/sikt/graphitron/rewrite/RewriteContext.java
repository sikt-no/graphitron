package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * @param schemaFileExtensions file-name suffixes (with leading dot) that count as GraphQL
 *                       schema files. Drives the {@code <schemaInputs>} post-scan filter,
 *                       the {@code graphitron:dev} watcher's trigger filter, and the
 *                       {@code SchemaProblemDiagnostic} orphan scan. Always at least one
 *                       entry; the Mojo seam rejects empty configuration.
 * @param codegenLoader  classloader the reflection path uses to resolve consumer-declared
 *                       service / record / condition / jOOQ-catalog classes. The Mojo builds a
 *                       {@link java.net.URLClassLoader} over the project's compile classpath
 *                       and every reactor sibling's {@code target/classes}, parented on the
 *                       plugin loader. Unit-tier callers default to the current thread's
 *                       context classloader through the six-arg overload, which equals the
 *                       system classloader in a JUnit-launched JVM.
 * @param compileSourceRoots compile source-root directories (hand-written plus
 *                       generated-sources) the LSP catalog parses to recover Java
 *                       declaration positions and Javadoc for goto-definition and
 *                       hover. Populated from every reactor project's
 *                       {@link org.apache.maven.project.MavenProject#getCompileSourceRoots()}
 *                       when the mojo runs inside Maven; empty for unit-tier callers and
 *                       for any goal that builds a catalog without a real project, in
 *                       which case source positions stay
 *                       {@link no.sikt.graphitron.rewrite.catalog.CompletionData.SourceLocation#UNKNOWN}.
 */
public record RewriteContext(
    List<SchemaInput> schemaInputs,
    Set<String> schemaFileExtensions,
    Path basedir,
    Path outputDirectory,
    Path outputResourcesDirectory,
    String outputPackage,
    String jooqPackage,
    Map<String, String> namedReferences,
    List<Path> classpathRoots,
    ClassLoader codegenLoader,
    List<Path> compileSourceRoots,
    LintConfig lintConfig
) {
    /** Standard schema file extensions accepted out of the box. */
    public static final Set<String> DEFAULT_SCHEMA_FILE_EXTENSIONS = Set.of(".graphqls", ".graphql");

    public RewriteContext {
        Objects.requireNonNull(schemaInputs, "schemaInputs");
        Objects.requireNonNull(schemaFileExtensions, "schemaFileExtensions");
        Objects.requireNonNull(basedir, "basedir");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(outputResourcesDirectory, "outputResourcesDirectory");
        Objects.requireNonNull(outputPackage, "outputPackage");
        Objects.requireNonNull(jooqPackage, "jooqPackage");
        Objects.requireNonNull(namedReferences, "namedReferences");
        Objects.requireNonNull(classpathRoots, "classpathRoots");
        Objects.requireNonNull(codegenLoader, "codegenLoader");
        if (schemaFileExtensions.isEmpty()) {
            throw new IllegalArgumentException("schemaFileExtensions must contain at least one entry");
        }
        schemaInputs = List.copyOf(schemaInputs);
        schemaFileExtensions = Set.copyOf(schemaFileExtensions);
        namedReferences = Map.copyOf(namedReferences);
        classpathRoots = List.copyOf(classpathRoots);
        // Source roots are null-tolerant: callers that build a catalog without a Maven
        // project (unit tier, validate-only) omit them and goto-definition / hover fall
        // back to file-level / UNKNOWN locations.
        compileSourceRoots = compileSourceRoots == null ? List.of() : List.copyOf(compileSourceRoots);
        // Lint suppression is null-tolerant: only the build mojos populate it from the <lint> block;
        // every other caller (unit tier, LSP/MCP dev-loop) defaults to no suppression (R408).
        lintConfig = lintConfig == null ? LintConfig.empty() : lintConfig;
    }

    /**
     * Returns a copy of this context with {@code lintConfig} replaced. The other fields are shared by
     * reference (all immutable or defensively copied by the canonical constructor). Lets a caller that
     * built a context through a convenience constructor layer the {@code <lint>} suppression on
     * afterwards without re-threading every field.
     */
    public RewriteContext withLintConfig(LintConfig lintConfig) {
        return new RewriteContext(schemaInputs, schemaFileExtensions, basedir, outputDirectory,
            outputResourcesDirectory, outputPackage, jooqPackage, namedReferences, classpathRoots,
            codegenLoader, compileSourceRoots, lintConfig);
    }

    /**
     * Back-compatible eleven-arg constructor (pre-{@code lintConfig}). Defaults {@code lintConfig}
     * to {@link LintConfig#empty()} so callers that predate lint suppression, and every non-Mojo
     * caller, keep linting every author-owned type with every rule.
     */
    public RewriteContext(
        List<SchemaInput> schemaInputs,
        Set<String> schemaFileExtensions,
        Path basedir,
        Path outputDirectory,
        Path outputResourcesDirectory,
        String outputPackage,
        String jooqPackage,
        Map<String, String> namedReferences,
        List<Path> classpathRoots,
        ClassLoader codegenLoader,
        List<Path> compileSourceRoots
    ) {
        this(schemaInputs, schemaFileExtensions, basedir, outputDirectory, outputResourcesDirectory,
            outputPackage, jooqPackage, namedReferences, classpathRoots, codegenLoader,
            compileSourceRoots, LintConfig.empty());
    }

    /**
     * Back-compatible ten-arg constructor (pre-{@code compileSourceRoots}). Defaults
     * {@code compileSourceRoots} to empty so callers that do not surface Java source
     * roots keep working; their catalogs carry file-level / {@code UNKNOWN} positions
     * as before.
     */
    public RewriteContext(
        List<SchemaInput> schemaInputs,
        Set<String> schemaFileExtensions,
        Path basedir,
        Path outputDirectory,
        Path outputResourcesDirectory,
        String outputPackage,
        String jooqPackage,
        Map<String, String> namedReferences,
        List<Path> classpathRoots,
        ClassLoader codegenLoader
    ) {
        this(schemaInputs, schemaFileExtensions, basedir, outputDirectory, outputResourcesDirectory,
            outputPackage, jooqPackage, namedReferences, classpathRoots, codegenLoader, List.of(),
            LintConfig.empty());
    }

    /**
     * Seven-arg overload for callers that supply {@code classpathRoots} but no explicit
     * {@code codegenLoader}; the loader defaults to the current thread's context classloader,
     * which equals the system classloader in a JUnit-launched JVM. The resources
     * directory defaults to a {@code generated-resources} sibling of {@code outputDirectory}.
     */
    public RewriteContext(
        List<SchemaInput> schemaInputs,
        Path basedir,
        Path outputDirectory,
        String outputPackage,
        String jooqPackage,
        Map<String, String> namedReferences,
        List<Path> classpathRoots
    ) {
        this(schemaInputs, DEFAULT_SCHEMA_FILE_EXTENSIONS, basedir, outputDirectory,
            defaultResourcesDirectory(outputDirectory), outputPackage, jooqPackage,
            namedReferences, classpathRoots, Thread.currentThread().getContextClassLoader(), List.of(),
            LintConfig.empty());
    }

    /**
     * Six-arg overload for unit-tier callers that don't care about classpath
     * scanning. Defaults {@code classpathRoots} to the empty list,
     * {@code codegenLoader} to the current thread's context classloader, and
     * {@code schemaFileExtensions} to {@link #DEFAULT_SCHEMA_FILE_EXTENSIONS}.
     * The resources directory defaults to a sibling of {@code outputDirectory}.
     */
    public RewriteContext(
        List<SchemaInput> schemaInputs,
        Path basedir,
        Path outputDirectory,
        String outputPackage,
        String jooqPackage,
        Map<String, String> namedReferences
    ) {
        this(schemaInputs, DEFAULT_SCHEMA_FILE_EXTENSIONS, basedir, outputDirectory,
            defaultResourcesDirectory(outputDirectory), outputPackage, jooqPackage,
            namedReferences, List.of(), Thread.currentThread().getContextClassLoader(), List.of(),
            LintConfig.empty());
    }

    private static Path defaultResourcesDirectory(Path outputDirectory) {
        Path parent = outputDirectory.getParent();
        return (parent != null ? parent : outputDirectory).resolve("generated-resources-graphitron");
    }
}
