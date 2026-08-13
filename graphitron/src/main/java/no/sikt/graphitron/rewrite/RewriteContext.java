package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.dependency.DependencyVersions;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaRecipe;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;

import java.nio.file.Path;
import java.util.List;
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
 * @param classpathRoots the compile classpath the class census is taken over: every reactor
 *                       project's {@code ${project.build.outputDirectory}} plus the resolved
 *                       compile dependencies, jars included, when the mojo runs inside Maven;
 *                       empty for unit-tier callers that don't ship classes. This is the same
 *                       list {@code codegenLoader} is built over, by construction rather than by
 *                       coincidence, which is the point: a class the loader resolves is a class
 *                       the census holds. It previously carried reactor output directories only,
 *                       on the premise that consumer vocabulary lives in reactor source rather
 *                       than in third-party libraries, and
 *                       {@code @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")}
 *                       falsifies that: it generates fine and reads as an unknown class in the
 *                       editor, because the two paths were reading different classpaths.
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
 * @param dependencyVersions the resolved graphql-java / jOOQ versions on both sides of the
 *                       currency nudge: the consumer's, decoded from the Maven project's
 *                       dependency graph, and graphitron's own, decoded from the plugin realm.
 *                       Plain strings by the time they arrive here, because the Maven artifact
 *                       types they are decoded from must not cross this boundary. {@code null}
 *                       (the default for every non-Maven caller) means
 *                       {@link DependencyVersions#empty()} and the advisory stays silent.
 * @param graphName      the name this run's graph carries in the fact store: the partition every
 *                       SDL fact row's key leads with, so one shared store holds many graphs
 *                       without fusing them. Maven callers get the {@code <graphName>} parameter's
 *                       default, the module's artifactId; programmatic callers state a name once
 *                       each, because a fallback name would be an unowned name and every caller
 *                       has a natural identity to give. Required and non-blank.
 * @param storeDirectory the fact store's home, so a run starts from the previous run's rows
 *                       instead of re-walking everything that has not changed. The store appends
 *                       its own compatibility segment under whatever home it is handed. Populated
 *                       by the build mojos from the resolved per-user cache location (or the
 *                       consumer's {@code <storeDirectory>} override); {@code null} for every
 *                       caller with no home to give, which gets the in-memory store that dies
 *                       with the run. Warm or cold changes what a load costs, never what it
 *                       records.
 * @param schemaRecipe   how this run's schema files were found: the resolved recipe entries, the
 *                       effective extension filter, and the build file they were resolved from.
 *                       Capture persists it beside the graph so a currency check can re-expand it
 *                       without building the module. Never {@code null}: a build mojo decodes it
 *                       from {@code <schemaInputs>}, and every convenience overload mints a literal
 *                       recipe from the input list it was handed, so every run has a recipe to
 *                       transcribe. It is also the one home of the effective extension filter, read
 *                       through {@link #schemaFileExtensions()}, which is why that stopped being a
 *                       second component with nothing binding the copies
 * @param supergraph     which supergraph this graph declared itself a subgraph of, from the
 *                       {@code <supergraph>} parameter. Grouping rather than federation: declaring
 *                       it does not make a graph federated and is not policed against the SDL's
 *                       opt-in. {@code null} is standalone, which is the default rather than a state
 *                       an author spells
 */
public record RewriteContext(
    List<SchemaInput> schemaInputs,
    Path basedir,
    String graphName,
    Path outputDirectory,
    Path outputResourcesDirectory,
    String outputPackage,
    String jooqPackage,
    List<Path> classpathRoots,
    ClassLoader codegenLoader,
    List<Path> compileSourceRoots,
    LintConfig lintConfig,
    SessionStateConfig sessionStateConfig,
    String tenantColumn,
    DependencyVersions dependencyVersions,
    Path storeDirectory,
    SchemaRecipe schemaRecipe,
    String supergraph
) {
    /** Standard schema file extensions accepted out of the box. */
    public static final Set<String> DEFAULT_SCHEMA_FILE_EXTENSIONS = Set.of(".graphqls", ".graphql");

    /**
     * The inert package a validate-only run carries in {@link #outputPackage} and
     * {@link #jooqPackage}: those components are non-null by contract and a validate run has no
     * output coordinates, so the build mojos substitute this. Named here rather than kept private to
     * the mojo because {@link #declaredOutputCoordinates()} reads it, and reading the admission is
     * how absence stays derived from the one copy instead of carried a second time.
     */
    public static final String NO_OUTPUT_PACKAGE = "validation.unused";

    public RewriteContext {
        Objects.requireNonNull(schemaInputs, "schemaInputs");
        Objects.requireNonNull(basedir, "basedir");
        Objects.requireNonNull(graphName, "graphName");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(outputResourcesDirectory, "outputResourcesDirectory");
        Objects.requireNonNull(outputPackage, "outputPackage");
        Objects.requireNonNull(jooqPackage, "jooqPackage");
        Objects.requireNonNull(classpathRoots, "classpathRoots");
        Objects.requireNonNull(codegenLoader, "codegenLoader");
        Objects.requireNonNull(schemaRecipe, "schemaRecipe");
        if (schemaRecipe.extensions().isEmpty()) {
            throw new IllegalArgumentException("schemaFileExtensions must contain at least one entry");
        }
        if (graphName.isBlank()) {
            throw new IllegalArgumentException("graphName must be non-blank");
        }
        schemaInputs = List.copyOf(schemaInputs);
        classpathRoots = List.copyOf(classpathRoots);
        // The six null-tolerant components: only the build mojos populate them (from
        // <sessionState>, <lint>, <tenantColumn>, the Maven project's source roots, the resolved
        // dependency graphs, the resolved store home, and <supergraph>); every other caller passes
        // null and gets the single-tenant, no-suppression, no-hook, UNKNOWN-positions,
        // no-version-facts, store-dies-with-the-run, standalone defaults.
        compileSourceRoots = compileSourceRoots == null ? List.of() : List.copyOf(compileSourceRoots);
        lintConfig = lintConfig == null ? LintConfig.empty() : lintConfig;
        sessionStateConfig = sessionStateConfig == null ? SessionStateConfig.none() : sessionStateConfig;
        tenantColumn = tenantColumn == null || tenantColumn.isBlank() ? null : tenantColumn.trim();
        dependencyVersions = dependencyVersions == null ? DependencyVersions.empty() : dependencyVersions;
        supergraph = supergraph == null || supergraph.isBlank() ? null : supergraph.trim();
    }

    /**
     * File-name suffixes (with leading dot) that count as GraphQL schema files. Drives the
     * {@code <schemaInputs>} post-scan filter, the {@code graphitron:dev} watcher's trigger filter,
     * and the {@code SchemaProblemDiagnostic} orphan scan. Never empty.
     *
     * <p>An accessor over {@link #schemaRecipe()} rather than a component of its own. It was both
     * once, and the two copies had nothing binding them: the disagreement-at-the-producer this
     * context's own recipe exists to abolish, one level up. The fact is asserted once and there is
     * nothing left to hold in agreement.
     */
    public Set<String> schemaFileExtensions() {
        return Set.copyOf(schemaRecipe.extensions());
    }

    /**
     * Where this run writes, or empty when it has no output coordinates at all. A derivation over the
     * components already here rather than a second copy of them, so the two cannot disagree: a run
     * carrying {@link #NO_OUTPUT_PACKAGE} is a validate-only run saying so, and what a fact store
     * transcribes from it is the absence rather than the sentinel.
     */
    public java.util.Optional<FactCapture.OutputCoordinates> declaredOutputCoordinates() {
        if (NO_OUTPUT_PACKAGE.equals(outputPackage) || NO_OUTPUT_PACKAGE.equals(jooqPackage)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
            new FactCapture.OutputCoordinates(outputPackage, jooqPackage, outputDirectory));
    }

    /**
     * A literal recipe over {@code inputs}: one entry per input, in order, carrying its attribution,
     * with {@code extensions} as the effective filter. What the convenience overloads mint so their
     * callers keep their signatures and their graph still transcribes a recipe; a recipe derived
     * from the list cannot disagree with the list it was derived from.
     */
    private static SchemaRecipe literalRecipe(List<SchemaInput> inputs, Set<String> extensions) {
        Objects.requireNonNull(inputs, "schemaInputs");
        Objects.requireNonNull(extensions, "schemaFileExtensions");
        return SchemaRecipe.literalOver(inputs, extensions);
    }

    /**
     * Returns a copy with {@code lintConfig} replaced, so a convenience-constructor caller can
     * layer the {@code <lint>} suppression on afterwards.
     */
    public RewriteContext withLintConfig(LintConfig lintConfig) {
        return new RewriteContext(schemaInputs, basedir, graphName, outputDirectory,
            outputResourcesDirectory, outputPackage, jooqPackage, classpathRoots,
            codegenLoader, compileSourceRoots, lintConfig, sessionStateConfig, tenantColumn,
            dependencyVersions, storeDirectory, schemaRecipe, supergraph);
    }

    /**
     * Returns a copy with {@code sessionStateConfig} replaced, so a convenience-constructor caller
     * can layer the {@code <sessionState>} configuration on afterwards.
     */
    public RewriteContext withSessionStateConfig(SessionStateConfig sessionStateConfig) {
        return new RewriteContext(schemaInputs, basedir, graphName, outputDirectory,
            outputResourcesDirectory, outputPackage, jooqPackage, classpathRoots,
            codegenLoader, compileSourceRoots, lintConfig, sessionStateConfig, tenantColumn,
            dependencyVersions, storeDirectory, schemaRecipe, supergraph);
    }

    /**
     * Returns a copy with {@code tenantColumn} replaced, so a convenience-constructor caller can
     * layer the {@code <tenantColumn>} declaration on afterwards.
     */
    public RewriteContext withTenantColumn(String tenantColumn) {
        return new RewriteContext(schemaInputs, basedir, graphName, outputDirectory,
            outputResourcesDirectory, outputPackage, jooqPackage, classpathRoots,
            codegenLoader, compileSourceRoots, lintConfig, sessionStateConfig, tenantColumn,
            dependencyVersions, storeDirectory, schemaRecipe, supergraph);
    }

    /**
     * Returns a copy with {@code dependencyVersions} replaced, so a convenience-constructor caller
     * can layer the resolved graphql-java / jOOQ version facts on afterwards.
     */
    public RewriteContext withDependencyVersions(DependencyVersions dependencyVersions) {
        return new RewriteContext(schemaInputs, basedir, graphName, outputDirectory,
            outputResourcesDirectory, outputPackage, jooqPackage, classpathRoots,
            codegenLoader, compileSourceRoots, lintConfig, sessionStateConfig, tenantColumn,
            dependencyVersions, storeDirectory, schemaRecipe, supergraph);
    }

    /**
     * Returns a copy with {@code supergraph} replaced, so a caller can layer the
     * {@code <supergraph>} declaration on afterwards.
     */
    public RewriteContext withSupergraph(String supergraph) {
        return new RewriteContext(schemaInputs, basedir, graphName, outputDirectory,
            outputResourcesDirectory, outputPackage, jooqPackage, classpathRoots,
            codegenLoader, compileSourceRoots, lintConfig, sessionStateConfig, tenantColumn,
            dependencyVersions, storeDirectory, schemaRecipe, supergraph);
    }

    /**
     * Fifteen-arg overload: defaults {@code storeDirectory} to {@code null}, so the fact store is
     * in-memory and dies with the run, and mints a literal recipe over {@code schemaInputs} with
     * {@code schemaFileExtensions} as its filter.
     */
    public RewriteContext(
        List<SchemaInput> schemaInputs,
        Set<String> schemaFileExtensions,
        Path basedir,
        String graphName,
        Path outputDirectory,
        Path outputResourcesDirectory,
        String outputPackage,
        String jooqPackage,
        List<Path> classpathRoots,
        ClassLoader codegenLoader,
        List<Path> compileSourceRoots,
        LintConfig lintConfig,
        SessionStateConfig sessionStateConfig,
        String tenantColumn,
        DependencyVersions dependencyVersions
    ) {
        this(schemaInputs, basedir, graphName, outputDirectory, outputResourcesDirectory,
            outputPackage, jooqPackage, classpathRoots, codegenLoader,
            compileSourceRoots, lintConfig, sessionStateConfig, tenantColumn, dependencyVersions,
            null, literalRecipe(schemaInputs, schemaFileExtensions), null);
    }

    /**
     * Thirteen-arg overload: defaults {@code tenantColumn} to {@code null} (single-tenant) and
     * {@code dependencyVersions} to {@link DependencyVersions#empty()} (no version facts).
     */
    public RewriteContext(
        List<SchemaInput> schemaInputs,
        Set<String> schemaFileExtensions,
        Path basedir,
        String graphName,
        Path outputDirectory,
        Path outputResourcesDirectory,
        String outputPackage,
        String jooqPackage,
        List<Path> classpathRoots,
        ClassLoader codegenLoader,
        List<Path> compileSourceRoots,
        LintConfig lintConfig,
        SessionStateConfig sessionStateConfig
    ) {
        this(schemaInputs, schemaFileExtensions, basedir, graphName, outputDirectory, outputResourcesDirectory,
            outputPackage, jooqPackage, classpathRoots, codegenLoader,
            compileSourceRoots, lintConfig, sessionStateConfig, null, null);
    }

    /**
     * Eleven-arg overload: defaults {@code lintConfig} to {@link LintConfig#empty()} (no
     * suppression; every author-owned type is linted with every rule).
     */
    public RewriteContext(
        List<SchemaInput> schemaInputs,
        Set<String> schemaFileExtensions,
        Path basedir,
        String graphName,
        Path outputDirectory,
        Path outputResourcesDirectory,
        String outputPackage,
        String jooqPackage,
        List<Path> classpathRoots,
        ClassLoader codegenLoader,
        List<Path> compileSourceRoots
    ) {
        this(schemaInputs, schemaFileExtensions, basedir, graphName, outputDirectory, outputResourcesDirectory,
            outputPackage, jooqPackage, classpathRoots, codegenLoader,
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
        String graphName,
        Path outputDirectory,
        Path outputResourcesDirectory,
        String outputPackage,
        String jooqPackage,
        List<Path> classpathRoots,
        ClassLoader codegenLoader
    ) {
        this(schemaInputs, schemaFileExtensions, basedir, graphName, outputDirectory, outputResourcesDirectory,
            outputPackage, jooqPackage, classpathRoots, codegenLoader, List.of(),
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
        String graphName,
        Path outputDirectory,
        String outputPackage,
        String jooqPackage,
        List<Path> classpathRoots
    ) {
        this(schemaInputs, DEFAULT_SCHEMA_FILE_EXTENSIONS, basedir, graphName, outputDirectory,
            defaultResourcesDirectory(outputDirectory), outputPackage, jooqPackage,
            classpathRoots, Thread.currentThread().getContextClassLoader(), List.of(),
            LintConfig.empty(), SessionStateConfig.none());
    }

    /** Six-arg overload for unit-tier callers that don't care about classpath scanning. */
    public RewriteContext(
        List<SchemaInput> schemaInputs,
        Path basedir,
        String graphName,
        Path outputDirectory,
        String outputPackage,
        String jooqPackage
    ) {
        this(schemaInputs, DEFAULT_SCHEMA_FILE_EXTENSIONS, basedir, graphName, outputDirectory,
            defaultResourcesDirectory(outputDirectory), outputPackage, jooqPackage,
            List.of(), Thread.currentThread().getContextClassLoader(), List.of(),
            LintConfig.empty(), SessionStateConfig.none());
    }

    private static Path defaultResourcesDirectory(Path outputDirectory) {
        Path parent = outputDirectory.getParent();
        return (parent != null ? parent : outputDirectory).resolve("generated-resources-graphitron");
    }
}
