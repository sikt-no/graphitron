package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;

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
 *                       {@code SchemaProblemDiagnostic} orphan scan. Never empty.
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
 *                       {@code MavenProject.getCompileSourceRoots()}
 *                       when the mojo runs inside Maven; empty for unit-tier callers and
 *                       for any goal that builds a catalog without a real project, in
 *                       which case source positions stay
 *                       {@link no.sikt.graphitron.rewrite.catalog.CompletionData.SourceLocation#UNKNOWN}.
 * @param tenantColumn   name of the database column that carries the tenant id in a
 *                       database-per-tenant deployment, matched against catalog columns the way
 *                       column lookups already match (Java name first, then SQL name, both
 *                       case-insensitive). Configured through the Mojo's {@code <tenantColumn>}
 *                       element. {@code null} (the default) means single-tenant: no table
 *                       carries a tenant scope and no per-field tenant binding is computed.
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
    LintConfig lintConfig,
    SessionStateConfig sessionStateConfig,
    String tenantColumn
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
        // The last four components are null-tolerant: only the build mojos populate them
        // (from <sessionState>, <lint>, <tenantColumn>, and the Maven project's source roots);
        // every other caller passes null and gets the single-tenant, no-suppression,
        // no-hook, UNKNOWN-positions defaults.
        compileSourceRoots = compileSourceRoots == null ? List.of() : List.copyOf(compileSourceRoots);
        lintConfig = lintConfig == null ? LintConfig.empty() : lintConfig;
        sessionStateConfig = sessionStateConfig == null ? SessionStateConfig.none() : sessionStateConfig;
        tenantColumn = tenantColumn == null || tenantColumn.isBlank() ? null : tenantColumn.trim();
    }

    /**
     * Returns a copy with {@code lintConfig} replaced, so a convenience-constructor caller can
     * layer the {@code <lint>} suppression on afterwards.
     */
    public RewriteContext withLintConfig(LintConfig lintConfig) {
        return new RewriteContext(schemaInputs, schemaFileExtensions, basedir, outputDirectory,
            outputResourcesDirectory, outputPackage, jooqPackage, namedReferences, classpathRoots,
            codegenLoader, compileSourceRoots, lintConfig, sessionStateConfig, tenantColumn);
    }

    /**
     * Returns a copy with {@code sessionStateConfig} replaced, so a convenience-constructor caller
     * can layer the {@code <sessionState>} configuration on afterwards.
     */
    public RewriteContext withSessionStateConfig(SessionStateConfig sessionStateConfig) {
        return new RewriteContext(schemaInputs, schemaFileExtensions, basedir, outputDirectory,
            outputResourcesDirectory, outputPackage, jooqPackage, namedReferences, classpathRoots,
            codegenLoader, compileSourceRoots, lintConfig, sessionStateConfig, tenantColumn);
    }

    /**
     * Returns a copy with {@code tenantColumn} replaced, so a convenience-constructor caller can
     * layer the {@code <tenantColumn>} declaration on afterwards.
     */
    public RewriteContext withTenantColumn(String tenantColumn) {
        return new RewriteContext(schemaInputs, schemaFileExtensions, basedir, outputDirectory,
            outputResourcesDirectory, outputPackage, jooqPackage, namedReferences, classpathRoots,
            codegenLoader, compileSourceRoots, lintConfig, sessionStateConfig, tenantColumn);
    }

    /** Thirteen-arg overload: defaults {@code tenantColumn} to {@code null} (single-tenant). */
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
        List<Path> compileSourceRoots,
        LintConfig lintConfig,
        SessionStateConfig sessionStateConfig
    ) {
        this(schemaInputs, schemaFileExtensions, basedir, outputDirectory, outputResourcesDirectory,
            outputPackage, jooqPackage, namedReferences, classpathRoots, codegenLoader,
            compileSourceRoots, lintConfig, sessionStateConfig, null);
    }

    /**
     * Eleven-arg overload: defaults {@code lintConfig} to {@link LintConfig#empty()} (no
     * suppression; every author-owned type is linted with every rule).
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
            compileSourceRoots, LintConfig.empty(), SessionStateConfig.none());
    }

    /**
     * Ten-arg overload: defaults {@code compileSourceRoots} to empty, so the catalog carries
     * file-level / {@code UNKNOWN} positions.
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
            LintConfig.empty(), SessionStateConfig.none());
    }

    /**
     * Seven-arg overload for callers that supply {@code classpathRoots} but no explicit
     * {@code codegenLoader}; the loader defaults to the current thread's context classloader,
     * which equals the system classloader in a JUnit-launched JVM.
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
            LintConfig.empty(), SessionStateConfig.none());
    }

    /** Six-arg overload for unit-tier callers that don't care about classpath scanning. */
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
            LintConfig.empty(), SessionStateConfig.none());
    }

    private static Path defaultResourcesDirectory(Path outputDirectory) {
        Path parent = outputDirectory.getParent();
        return (parent != null ? parent : outputDirectory).resolve("generated-resources-graphitron");
    }
}
