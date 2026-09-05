package no.sikt.graphitron.rewrite.maven;

import graphql.schema.idl.errors.SchemaProblem;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.config.ClasspathEntry.Origin;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.model.run.CapturePort;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.config.DependencyVersions;
import no.sikt.graphitron.model.config.ObservedVersion;
import no.sikt.graphitron.model.config.WatchedDependency;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.read.SourceStamp;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.rewrite.maven.watch.WatchErrorFormatter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared configuration surface for {@link GenerateMojo} and {@link ValidateMojo}.
 */
public abstract class AbstractRewriteMojo extends AbstractMojo {

    /** Sentinel used for validate-only invocations that do not emit code. */
    private static final String VALIDATE_ONLY_PACKAGE = RunContext.NO_OUTPUT_PACKAGE;

    /**
     * The largest a Maven checksum sidecar can be and still be read. A real one is 40 bytes and a
     * newline; the cap is what stops a file that merely ends in {@code .sha1} from being loaded
     * into memory because it happened to sit beside a jar.
     */
    private static final long MAX_CHECKSUM_SIDECAR_BYTES = 256;

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    MavenSession session;

    /**
     * This plugin's own resolved dependency graph, the reference side of the
     * dependency-currency nudge. {@code graphitron-maven-plugin} depends on {@code graphitron},
     * whose pom declares graphql-java and jOOQ at compile scope, so the realm carries the exact
     * versions graphitron was built against with no property promotion, no resource filtering, and
     * no new build wiring. A consumer who overrides {@code <plugin><dependencies>} moves the
     * reference, which is self-consistent: the advisory then reports the version the plugin
     * actually ran with.
     */
    @Parameter(defaultValue = "${plugin}", readonly = true)
    PluginDescriptor pluginDescriptor;

    @Parameter
    List<SchemaInputBinding> schemaInputs;

    /**
     * File-name suffixes that count as GraphQL schema files. Drives the
     * {@code <schemaInputs>} post-scan filter, the {@code graphitron:dev} watcher's trigger
     * filter, and the {@link SchemaProblemDiagnostic} orphan scan. Matched with
     * {@link String#endsWith(String)} on the file-name component, case-sensitively; a missing
     * leading dot is prepended.
     *
     * <p>Omit (Maven binds an empty POM list as {@code null}) to accept
     * {@link RunContext#DEFAULT_SCHEMA_FILE_EXTENSIONS}. A configured but empty list is
     * rejected at execute.
     */
    @Parameter
    List<String> schemaFileExtensions;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/graphitron")
    String outputDirectory;

    /**
     * Name this module's graph carries in the fact store: the partition dimension every SDL fact
     * row's key leads with, so one workspace store holds every module's graph without fusing
     * them. The default, the module's own artifactId, is unique within a reactor, which is
     * exactly the store's sharing scope; override it when the subgraph's published name differs
     * from the module's, or when two modules would otherwise claim one name.
     */
    @Parameter(defaultValue = "${project.artifactId}")
    String graphName;

    /**
     * Which supergraph this module's graph is a subgraph of, as a declared fact rather than one
     * inferred from colocation or from federation SDL. Neither inference can answer it: two
     * subgraphs of two different supergraphs carry indistinguishable federation SDL, and one
     * checkout can hold two supergraphs plus standalone graphs while one supergraph can span
     * checkouts. Transcribed to the fact store's {@code store_graph_supergraph} relation, which is
     * what scopes "which graphs are this graph's business" for a reader ranging over a shared
     * workspace store.
     *
     * <p>Grouping, not federation: declaring it neither makes the graph federated nor is policed
     * against the SDL's {@code @link} opt-in, so a subgraph under development may declare its home
     * before its first {@code @key} is written. Paired with {@link #graphName} it is the addressing
     * federation already uses, so a parent pom shared by one supergraph's modules can declare the
     * value once in {@code pluginManagement} and every subgraph module inherits it. Omit for a
     * standalone graph; standalone is the default rather than a state an author spells.
     */
    @Parameter
    String supergraph;

    /**
     * Where the fact store is kept between runs; the store's <em>home</em>, under which the store
     * itself keeps a compatibility-stamped subdirectory. Omit for the platform's per-user cache
     * location with a per-workspace segment, resolved by {@link #resolveStoreDirectory}; set it
     * (or pass {@code -Dgraphitron.store.directory=...}) to keep the store inside the build for
     * hermetic CI jobs or containers that discard {@code $HOME}.
     */
    @Parameter(property = "graphitron.store.directory")
    String storeDirectory;

    @Parameter
    String outputPackage;

    @Parameter
    String jooqPackage;

    /**
     * Lint suppression. A {@code <lint>} block naming rule ids to silence everywhere
     * ({@code <disabledRules>}) and type-name globs to exclude from the SDL lint engine
     * ({@code <excludedTypes>}). Threaded through {@link RunContext} so suppression is applied at
     * the one build evaluator; the {@code graphitron:dev} LSP and MCP diagnostics suppress
     * identically. A disabled rule id that resolves to no rule fails the build with the list of valid
     * ids. Omit the block to lint every author-owned type with every rule.
     */
    @Parameter
    LintBinding lint;

    /**
     * Session identity. A {@code <sessionState>} block naming the consumer's static Java
     * methods graphitron calls at the connection boundary: {@code <mount>} (as
     * {@code fqcn#method}) runs on each acquired connection before any SQL, the optional
     * {@code <unmount>} at release. Threaded through {@link RunContext} as authored
     * strings; the schema build reflects them and the connection-runtime emitters call them
     * directly. A malformed reference or an {@code <unmount>} without a {@code <mount>} fails
     * the build here; omit the block to mount no identity.
     */
    @Parameter
    SessionStateBinding sessionState;

    /**
     * Name of the database column that carries the tenant id in a database-per-tenant
     * deployment. Matched against catalog columns the way column lookups already match (Java
     * name first, then SQL name, both case-insensitive). Configuring it classifies every
     * catalog table as tenant-scoped (carries the column) or global (does not) and computes a
     * per-field tenant binding from the schema's column mappings. Omit for single-tenant
     * builds; no tenant machinery exists then.
     */
    @Parameter
    String tenantColumn;

    @FunctionalInterface
    protected interface GeneratorCall {
        void invoke(GraphQLRewriteGenerator gen);
    }

    /**
     * Body for {@link #withCodegenScope}: receives a {@link RunContext} whose
     * {@code codegenLoader} is the scope's classloader, valid only until {@code run} returns.
     */
    @FunctionalInterface
    protected interface CodegenScopeBody {
        void run(RunContext ctx) throws MojoExecutionException;
    }

    /**
     * Returns {@code true} if this goal needs {@code <outputPackage>} and {@code <jooqPackage>},
     * {@code false} if it tolerates their absence: validate-only goals substitute an inert
     * sentinel so {@code mvn graphitron:validate} works standalone from the CLI. The validate
     * pipeline never emits code, so the packages only satisfy {@link RunContext}'s non-null
     * contract.
     */
    protected abstract boolean packagesRequired();

    protected final RunContext buildContext() throws MojoExecutionException {
        return buildContext(Thread.currentThread().getContextClassLoader());
    }

    private RunContext buildContext(ClassLoader codegenLoader) throws MojoExecutionException {
        var basedir = project.getBasedir().toPath();
        var out = Path.of(outputDirectory);
        var outAbs = out.isAbsolute() ? out.normalize() : basedir.resolve(out).normalize();
        var resourcesAbs = resolveOutputResourcesDirectory(basedir);

        String effectiveOutput;
        String effectiveJooq;
        if (packagesRequired()) {
            if (outputPackage == null) {
                throw new MojoExecutionException("<outputPackage> is required for this goal");
            }
            if (jooqPackage == null) {
                throw new MojoExecutionException("<jooqPackage> is required for this goal");
            }
            effectiveOutput = outputPackage;
            effectiveJooq = jooqPackage;
        } else {
            effectiveOutput = outputPackage != null ? outputPackage : VALIDATE_ONLY_PACKAGE;
            effectiveJooq = jooqPackage != null ? jooqPackage : VALIDATE_ONLY_PACKAGE;
        }

        var extensions = effectiveSchemaFileExtensions();
        var recipe = buildSchemaRecipe(extensions);
        return new RunContext(
            expandRecipe(recipe, basedir),
            basedir,
            effectiveGraphName(),
            outAbs,
            resourcesAbs,
            effectiveOutput,
            effectiveJooq,
            resolveCompileClasspath(),
            codegenLoader,
            resolveCompileSourceRoots(),
            buildLintConfig(),
            buildSessionStateConfig(),
            tenantColumn,
            resolveDependencyVersions(),
            resolveStoreDirectory(basedir),
            recipe,
            supergraph
        );
    }

    /**
     * The one seam: the decoded recipe expanded into the {@link SchemaInput} list the context
     * carries beside it, so the pair cannot disagree at the producer. Two pieces of code used to
     * read the same {@code <schemaInputs>} beans and re-collapse the same empty strings, agreeing
     * only because they were written to agree.
     *
     * <p>This is also where the core expansion's typed result becomes author-facing prose. The
     * rendering is the mojo's dialect and stays here: a failure the freshness replay has to decide
     * on cannot be a message composed for a human at the detection site.
     */
    List<SchemaInput> expandRecipe(SchemaRecipe recipe, Path basedir)
            throws MojoExecutionException {
        if (recipe.bindings().isEmpty()) {
            // Nothing configured is not the same as configured patterns matching nothing: the
            // aggregate-empty failure below is a statement about patterns that were spelled.
            return List.of();
        }
        var expansion = recipe.expand(basedir);
        return switch (expansion) {
            case SchemaRecipe.Expansion.Resolved resolved -> {
                for (var empty : resolved.emptyPatterns()) {
                    getLog().warn("<schemaInput pattern='" + empty.pattern() + "'> (entry #"
                        + empty.entryIndex() + ") matched no files; skipping");
                }
                yield resolved.matches().stream().map(SchemaRecipe.Expansion.Match::input).toList();
            }
            case SchemaRecipe.Expansion.NoMatches noMatches -> {
                var sb = new StringBuilder("<schemaInputs> matched no files. Empty patterns:");
                for (var empty : noMatches.emptyPatterns()) {
                    sb.append("\n  entry #").append(empty.entryIndex()).append(": ").append(empty.pattern());
                }
                throw new MojoExecutionException(sb.toString());
            }
            case SchemaRecipe.Expansion.ScannerTrouble trouble -> throw new MojoExecutionException(
                "<schemaInput pattern='" + trouble.pattern() + "'> scanner error (entry #"
                    + trouble.entryIndex() + "): " + trouble.cause().getMessage(), trouble.cause());
        };
    }

    /**
     * The configured {@code <graphName>}, or the parameter's own default recomputed for callers
     * that construct the mojo programmatically (the unit tier), where Maven never applies
     * {@code defaultValue}. One truth, stated twice by necessity, not a second default.
     */
    private String effectiveGraphName() {
        return graphName != null && !graphName.isBlank() ? graphName : project.getArtifactId();
    }

    /**
     * The graph's SDL recipe: the {@code <schemaInputs>} bindings as configured (patterns, tags,
     * description notes), the effective extension filter, and the pom they were resolved from.
     * Capture persists it beside the graph, which is what lets a currency check re-expand the
     * globs over the module's base directory without building the module.
     */
    SchemaRecipe buildSchemaRecipe(Set<String> extensions) {
        var bindings = new ArrayList<SchemaRecipe.Binding>();
        if (schemaInputs != null) {
            for (SchemaInputBinding binding : schemaInputs) {
                bindings.add(new SchemaRecipe.Binding(
                    new SchemaRecipe.Entry.Pattern(binding.pattern),
                    Optional.ofNullable(binding.tag).filter(s -> !s.isEmpty()),
                    Optional.ofNullable(binding.descriptionNote).filter(s -> !s.isEmpty())));
            }
        }
        var buildFile = project != null && project.getFile() != null ? project.getFile().toPath() : null;
        return new SchemaRecipe(buildFile, bindings, List.copyOf(extensions));
    }

    /**
     * The version facts behind the dependency-currency nudge, decoded from the consumer's resolved
     * dependency graph and this plugin's own. This is the whole of the boundary's job: the
     * comparison, the predicate, and the message all live in
     * {@link no.sikt.graphitron.rewrite.dependency.DependencyVersionWarnings}, because
     * {@link Artifact} is external untyped input that must not cross into the generator.
     */
    private DependencyVersions resolveDependencyVersions() {
        return decodeDependencyVersions(
            project == null ? null : project.getArtifacts(),
            pluginDescriptor == null ? null : pluginDescriptor.getArtifacts());
    }

    /**
     * The dependency scopes a watched coordinate is observed on. Deliberately an allow-list rather
     * than a deny-list of {@code test}, because the goals resolve different scope sets:
     * {@code generate} and {@code validate} declare {@link org.apache.maven.plugins.annotations.ResolutionScope#COMPILE},
     * {@code dev} declares {@code TEST}, and Maven expands those to {@code {compile, system,
     * provided}} and {@code {compile, system, provided, runtime, test}} respectively. Admitting
     * exactly the three scopes {@code COMPILE} resolves is what makes the advisory say the same
     * thing under all three goals; excluding {@code test} alone would leave {@code runtime} leaking
     * through, so a consumer with runtime-scoped jOOQ would be nudged under {@code graphitron:dev}
     * and silent under {@code graphitron:generate}.
     *
     * <p>It is also right on its own terms: generated code names {@code org.jooq} and
     * {@code graphql.schema} types directly, so a consumer who compiles graphitron's output carries
     * both at {@code compile} or {@code provided}. A coordinate visible only at {@code runtime} or
     * {@code test} is not one the generated sources are built against.
     */
    static final Set<String> GENERATED_CODE_SCOPES =
        Set.of(Artifact.SCOPE_COMPILE, Artifact.SCOPE_PROVIDED, Artifact.SCOPE_SYSTEM);

    /**
     * Turns two artifact sets into {@code (coordinate, version-string)} pairs. Package-private so
     * the scope allow-list can be pinned with a unit test over hand-built artifacts, the way
     * {@link #collectExistingDirs} is; what that test cannot pin is Maven's resolution, so the
     * goal-invariance claim rests on {@link #GENERATED_CODE_SCOPES} naming exactly the scopes
     * {@code ResolutionScope.COMPILE} resolves.
     *
     * <p>The two sides decode differently and are deliberately separate methods rather than one
     * parameterised by a scope filter: only the consumer side is scoped, and only the consumer side
     * can put one library at several coordinates. See {@link #observedVersionsOf} and
     * {@link #referenceVersionsOf}.
     */
    static DependencyVersions decodeDependencyVersions(
        Collection<Artifact> projectArtifacts, Collection<Artifact> pluginArtifacts
    ) {
        return new DependencyVersions(
            observedVersionsOf(projectArtifacts,
                scope -> scope != null && GENERATED_CODE_SCOPES.contains(scope)),
            referenceVersionsOf(pluginArtifacts));
    }

    /**
     * Every coordinate each {@link WatchedDependency} was resolved at in {@code artifacts}, on a scope
     * {@code scopeFilter} admits.
     *
     * <p>Every match is carried rather than the first one, because a watched dependency names a
     * library and jOOQ's editions put one library at several coordinates. Maven mediates per
     * coordinate, not per library, so de-duplicating here would make the surviving observation a
     * function of artifact-set iteration order; which coordinate the advisory speaks about is a
     * decision, and decisions belong behind this boundary.
     */
    private static Map<WatchedDependency, List<ObservedVersion>> observedVersionsOf(
        Collection<Artifact> artifacts, Predicate<String> scopeFilter
    ) {
        if (artifacts == null) {
            return Map.of();
        }
        var versions = new EnumMap<WatchedDependency, List<ObservedVersion>>(WatchedDependency.class);
        for (Artifact artifact : artifacts) {
            if (artifact == null || artifact.getVersion() == null || !scopeFilter.test(artifact.getScope())) {
                continue;
            }
            WatchedDependency.of(artifact.getGroupId(), artifact.getArtifactId()).ifPresent(dep ->
                versions.computeIfAbsent(dep, k -> new ArrayList<>()).add(new ObservedVersion(
                    artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact.getVersion())));
        }
        return versions;
    }

    /**
     * The resolved version of each {@link WatchedDependency} in graphitron's own plugin realm. First
     * occurrence wins, which is unambiguous here in a way it is not on the consumer side: the realm is
     * graphitron's own build, resolving one of each. No coordinate is reported from this side, so none
     * is carried.
     *
     * <p>Unscoped on purpose. The plugin realm's scopes are a fact of graphitron's own build and do
     * not vary by goal, so filtering them would only risk dropping the reference version for no gain.
     */
    private static Map<WatchedDependency, String> referenceVersionsOf(Collection<Artifact> artifacts) {
        if (artifacts == null) {
            return Map.of();
        }
        var versions = new EnumMap<WatchedDependency, String>(WatchedDependency.class);
        for (Artifact artifact : artifacts) {
            if (artifact == null || artifact.getVersion() == null) {
                continue;
            }
            WatchedDependency.of(artifact.getGroupId(), artifact.getArtifactId())
                .ifPresent(dep -> versions.putIfAbsent(dep, artifact.getVersion()));
        }
        return versions;
    }

    /**
     * The fact store's home: the per-user cache location with a per-workspace segment, or the
     * consumer's {@code <storeDirectory>} / {@code -Dgraphitron.store.directory} override taken
     * verbatim (a pinned home is already scoped to whatever the consumer meant it to be scoped
     * to). This is the only store-home resolver in the tree; every other opener reaches the
     * store through the {@link RunContext} it built. The store itself appends a
     * compatibility-stamped subdirectory under whatever home this returns, so the value means
     * "home", never "the directory the file sits in".
     *
     * <p>The workspace segment is what makes the graph-name scoping structural rather than
     * hopeful: one file per workspace, holding every graph that workspace's modules capture, and
     * no file holding two workspaces' graphs, so two checkouts of one repository cannot thrash
     * each other's partitions through equal artifactIds. The store is a cache with no state of
     * record; a run that cannot use it boots cold and correct, so nothing here is ever worth a
     * build failure. {@code mvn clean} no longer removes it, the file no longer being build
     * output; the remedy for a damaged store is deleting the cache directory (or the one
     * workspace segment under it) by hand.
     */
    final Path resolveStoreDirectory(Path basedir) {
        // Maven binds the CLI property into the field at injection; consulting the system
        // property here mirrors that for programmatically constructed mojos (the unit tier),
        // whose runs would otherwise resolve the developer's real cache and orphan one
        // workspace segment per @TempDir. The plugin's own surefire pins it for exactly that
        // reason.
        String configured = storeDirectory != null && !storeDirectory.isBlank()
            ? storeDirectory
            : System.getProperty("graphitron.store.directory");
        if (configured != null && !configured.isBlank()) {
            var home = Path.of(configured.trim());
            return (home.isAbsolute() ? home : basedir.resolve(home)).normalize();
        }
        Path workspace = workspaceRoot(basedir);
        return userCacheRoot()
            .resolve("graphitron")
            .resolve("model")
            .resolve(workspaceSegment(workspace));
    }

    /**
     * The platform's cache convention for per-user tool state: {@code $XDG_CACHE_HOME} (falling
     * back to {@code ~/.cache}) on Linux, {@code ~/Library/Caches} on macOS,
     * {@code %LOCALAPPDATA%} on Windows. The cache convention rather than the data one because
     * the store is a cache by nature: rebuildable from sources, no state of record, always safe
     * to delete.
     */
    private static Path userCacheRoot() {
        Path home = Path.of(System.getProperty("user.home"));
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            return localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData)
                : home.resolve("AppData").resolve("Local");
        }
        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Caches");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        return xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
            ? Path.of(xdg)
            : home.resolve(".cache");
    }

    /**
     * The root directory's leaf name plus a hash of its absolute normalized path: filesystem-safe,
     * collision-free, and legible in a directory listing when a user goes looking for what is
     * filling their cache.
     */
    private static String workspaceSegment(Path workspace) {
        Path leaf = workspace.getFileName();
        String name = leaf != null ? leaf.toString() : "workspace";
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = HexFormat.of().formatHex(
                digest.digest(workspace.toString().getBytes(StandardCharsets.UTF_8)));
            return name + "-" + hash.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /**
     * The workspace a module belongs to: the outermost reactor root, resolved off the filesystem
     * aggregator chain by chaining {@link #nearestAggregator}'s one step until no ancestor pom
     * lists the current directory. Outermost rather than nearest because two subgraph modules
     * under different intermediate aggregators of one checkout have to land in one store to be
     * composable at all. Filesystem-only on purpose: the Maven session's top-level project and
     * execution root both answer "where was {@code mvn} invoked", so a module built from inside
     * its own directory would resolve a different workspace than the same module built from the
     * root and boot cold against a store one directory away; and the parent chain is a different
     * graph than the aggregator chain (an empty {@code <relativePath/>} resolves the parent from
     * the repository, an aggregator need not be the parent), so it cannot serve either. Each hop
     * is a strict ancestor of the previous, so the walk terminates on path depth; a module no
     * ancestor pom lists resolves to itself, which fires on a property of the tree (there is no
     * aggregator on disk) rather than on how Maven happened to resolve anything.
     */
    static Path workspaceRoot(Path moduleBasedir) {
        Path workspace = moduleBasedir.toAbsolutePath().normalize();
        for (Path aggregator = nearestAggregator(workspace);
             aggregator != null;
             aggregator = nearestAggregator(workspace)) {
            workspace = aggregator;
        }
        return workspace;
    }

    /**
     * An unknown disabled rule id is a typo the build must not silently ignore, so
     * {@link LintConfig#validated} throws and this surfaces it as a build failure carrying the
     * list of valid ids.
     */
    private LintConfig buildLintConfig() throws MojoExecutionException {
        if (lint == null) {
            return LintConfig.empty();
        }
        var disabled = trimmedNonBlank(lint.disabledRules).collect(Collectors.toCollection(LinkedHashSet::new));
        var excluded = trimmedNonBlank(lint.excludedTypes).toList();
        try {
            return LintConfig.validated(disabled, excluded);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * The shape rejections ({@code <unmount>} without {@code <mount>}, a malformed
     * {@code fqcn#method}) live in {@link SessionStateConfig}, not here (a {@code pom.xml}
     * defect has no SDL coordinate); this only hands the authored strings over and wraps the
     * rejection as a build failure. No reflection runs at this seam: the schema build owns it.
     */
    private SessionStateConfig buildSessionStateConfig() throws MojoExecutionException {
        if (sessionState == null) {
            return SessionStateConfig.none();
        }
        try {
            return SessionStateConfig.from(sessionState.mount, sessionState.unmount);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private static String trimOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Stream<String> trimmedNonBlank(List<String> raw) {
        if (raw == null) {
            return Stream.empty();
        }
        return raw.stream().filter(s -> s != null).map(String::trim).filter(s -> !s.isEmpty());
    }

    /**
     * Normalises {@link #schemaFileExtensions} into the set threaded through the pipeline; the
     * parameter's javadoc states the matching contract.
     */
    Set<String> effectiveSchemaFileExtensions() throws MojoExecutionException {
        if (schemaFileExtensions == null) {
            return RunContext.DEFAULT_SCHEMA_FILE_EXTENSIONS;
        }
        var normalised = new LinkedHashSet<String>();
        for (String raw : schemaFileExtensions) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            normalised.add(trimmed.startsWith(".") ? trimmed : "." + trimmed);
        }
        if (normalised.isEmpty()) {
            throw new MojoExecutionException(
                "<schemaFileExtensions> must contain at least one entry; "
                    + "omit the parameter to accept the default ["
                    + String.join(", ", RunContext.DEFAULT_SCHEMA_FILE_EXTENSIONS) + "]");
        }
        return Set.copyOf(normalised);
    }

    /**
     * Derives the {@code generated-resources/graphitron} root from the project build directory.
     * The relative segment is the Maven {@code generated-resources/<plugin-name>} convention,
     * not user-configurable. The {@code basedir/target} fallback serves hand-built
     * {@link MavenProject} test instances with no build directory set ({@code DevMojoTest},
     * {@code CodegenLoaderTest}, {@code GenerateMojoTest}).
     */
    final Path resolveOutputResourcesDirectory(Path basedir) {
        var buildDirectory = project.getBuild() != null
            ? project.getBuild().getDirectory()
            : null;
        var targetDir = buildDirectory != null
            ? Path.of(buildDirectory)
            : basedir.resolve("target");
        return (targetDir.isAbsolute() ? targetDir : basedir.resolve(targetDir))
            .resolve("generated-resources/graphitron")
            .normalize();
    }

    /**
     * The {@code graphitron-mcp-rag} cache root under the build directory (same test-instance
     * fallback as {@link #resolveOutputResourcesDirectory(Path)}). The semantic catalog index
     * persists its content-hash-keyed Lucene directories here, so it survives {@code dev}
     * restarts and dies on {@code mvn clean}.
     */
    final Path resolveRagCacheDirectory(Path basedir) {
        var buildDirectory = project.getBuild() != null
            ? project.getBuild().getDirectory()
            : null;
        var targetDir = buildDirectory != null
            ? Path.of(buildDirectory)
            : basedir.resolve("target");
        return (targetDir.isAbsolute() ? targetDir : basedir.resolve(targetDir))
            .resolve("graphitron-mcp-rag")
            .normalize();
    }

    /**
     * The graphitron-exclusive class output root ({@code target/graphitron-classes}) the
     * incremental compile driver writes into (same test-instance fallback as
     * {@link #resolveOutputResourcesDirectory(Path)}). Graphitron alone writes here, never the
     * shared {@code target/classes}; that exclusivity is what makes the incremental compile
     * sound by construction, and the directory sits first on the run classpath so a fresh copy
     * shadows any stale copy in {@code target/classes}.
     */
    final Path resolveGraphitronClassesDirectory(Path basedir) {
        var buildDirectory = project.getBuild() != null
            ? project.getBuild().getDirectory()
            : null;
        var targetDir = buildDirectory != null
            ? Path.of(buildDirectory)
            : basedir.resolve("target");
        return (targetDir.isAbsolute() ? targetDir : basedir.resolve(targetDir))
            .resolve("graphitron-classes")
            .normalize();
    }

    /**
     * The classified compile classpath: the consumer's compile dep graph plus every reactor
     * sibling's {@code target/classes}, one {@link ClasspathEntry} per path with the
     * {@link ClasspathEntry.Origin} decided here, at the one producer. The codegen loader, the
     * incremental compile engine and the dev execution loader project every entry's path; the
     * class census reads the non-{@code TRANSITIVE} ones; the nameability rule reads the whole
     * classification. One list whose elements carry the decision, rather than a second, narrower
     * resolve method beside this one: two sibling path lists are structurally interchangeable and
     * their subset relation would live only in prose.
     */
    protected final List<ClasspathEntry> resolveCompileClasspath() throws MojoExecutionException {
        List<String> compileElements;
        try {
            compileElements = project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException(
                "Failed to assemble the project compile classpath.", e);
        }
        return classifyCompileClasspath(
            compileElements,
            projectOutputDirectory(),
            project.getArtifacts(),
            project.getDependencies(),
            reactorOutputCoordinates());
    }

    /** This module's own compile-output directory, normalized; the {@code PROJECT} arm's anchor. */
    private Path projectOutputDirectory() {
        String dir = project.getBuild() == null ? null : project.getBuild().getOutputDirectory();
        return dir == null
            ? project.getBasedir().toPath().resolve("target/classes").toAbsolutePath().normalize()
            : Path.of(dir).toAbsolutePath().normalize();
    }

    /**
     * Every reactor project's compile-output directory mapped to the name the {@code SIBLING}
     * rejection message needs: {@code groupId:artifactId} for a loaded reactor project, the
     * module directory name for a {@link #siblingModuleBasedirs() convention-scanned sibling}
     * (an unloaded sibling has no {@link MavenProject} to read coordinates from). Insertion
     * order follows {@link #reactorProjects()} then declared {@code <modules>} order, matching
     * what {@link #resolveCompileSourceRoots()} follows on the source-root side.
     */
    private Map<Path, String> reactorOutputCoordinates() {
        var byPath = new LinkedHashMap<Path, String>();
        for (MavenProject p : reactorProjects()) {
            String dir = p.getBuild() == null ? null : p.getBuild().getOutputDirectory();
            if (dir == null) continue;
            byPath.putIfAbsent(Path.of(dir).toAbsolutePath().normalize(),
                p.getGroupId() + ":" + p.getArtifactId());
        }
        for (Path base : siblingModuleBasedirs()) {
            Path fileName = base.getFileName();
            byPath.putIfAbsent(base.resolve("target/classes").toAbsolutePath().normalize(),
                fileName != null ? fileName.toString() : base.toString());
        }
        return byPath;
    }

    /**
     * The classification decode: Maven's untyped view of the classpath in, the classified
     * {@link ClasspathEntry} list out, so {@link Artifact} and {@link Dependency} never cross
     * into {@code graphitron} (the {@link #decodeDependencyVersions} precedent). Per path, in
     * compile-classpath order with the reactor outputs folded in behind:
     *
     * <ul>
     *   <li>this module's own output: {@code PROJECT};</li>
     *   <li>a resolved artifact this module declares: {@code DECLARED};</li>
     *   <li>a reactor project's output the module does not declare: {@code SIBLING}, carrying
     *       the module name the build-side rejection message needs;</li>
     *   <li>a resolved artifact the module does not declare: {@code TRANSITIVE};</li>
     *   <li>a path no artifact and no reactor project accounts for: {@code DECLARED} with no
     *       coordinate, keeping the entry in the census, because dropping an unattributable
     *       entry would silently defeat both the census and the rule it feeds.</li>
     * </ul>
     *
     * <p>Directness reads {@link Artifact#getDependencyTrail()} where resolution populated it (a
     * two-element trail is the artifact itself under the project root, so anything longer arrived
     * through another dependency), and falls back to joining the artifact against
     * {@code declaredDependencies} on groupId, artifactId, type and classifier, never version,
     * since dependencyManagement rewrites versions. No scope set is minted here: intersecting the
     * declared coordinates with what {@code getCompileClasspathElements()} returned leaves the
     * scope question answered by Maven and by {@link #GENERATED_CODE_SCOPES}' existing argument.
     */
    static List<ClasspathEntry> classifyCompileClasspath(
        List<String> compileClasspathElements,
        Path projectOutputDirectory,
        Collection<Artifact> resolvedArtifacts,
        Collection<Dependency> declaredDependencies,
        Map<Path, String> reactorOutputs
    ) {
        var artifactByPath = new LinkedHashMap<Path, Artifact>();
        if (resolvedArtifacts != null) {
            for (Artifact artifact : resolvedArtifacts) {
                if (artifact == null || artifact.getFile() == null) continue;
                artifactByPath.putIfAbsent(
                    artifact.getFile().toPath().toAbsolutePath().normalize(), artifact);
            }
        }
        var declaredKeys = new HashSet<String>();
        if (declaredDependencies != null) {
            for (Dependency dependency : declaredDependencies) {
                if (dependency == null) continue;
                declaredKeys.add(dependencyKey(dependency.getGroupId(), dependency.getArtifactId(),
                    dependency.getType(), dependency.getClassifier()));
            }
        }
        var byPath = new LinkedHashMap<Path, ClasspathEntry>();
        for (String element : compileClasspathElements) {
            if (element == null) continue;
            Path path = Path.of(element).toAbsolutePath().normalize();
            if (byPath.containsKey(path)) continue;
            byPath.put(path, classifyElement(path, projectOutputDirectory, artifactByPath,
                declaredKeys, reactorOutputs));
        }
        for (Map.Entry<Path, String> reactor : reactorOutputs.entrySet()) {
            Path path = reactor.getKey();
            if (byPath.containsKey(path) || !Files.isDirectory(path)) continue;
            Origin origin = path.equals(projectOutputDirectory) ? Origin.PROJECT : Origin.SIBLING;
            byPath.put(path, new ClasspathEntry(path,
                origin, origin == Origin.PROJECT ? null : reactor.getValue()));
        }
        return List.copyOf(byPath.values());
    }

    private static ClasspathEntry classifyElement(
        Path path, Path projectOutputDirectory, Map<Path, Artifact> artifactByPath,
        Set<String> declaredKeys, Map<Path, String> reactorOutputs
    ) {
        // Taken once per element and attached to whichever arm claims it: the stamp is a fact
        // about the file, and none of the four origins below changes what the bytes are.
        String stamp = resolvedIdentity(path);
        if (path.equals(projectOutputDirectory)) {
            return new ClasspathEntry(path, Origin.PROJECT, null, stamp);
        }
        Artifact artifact = artifactByPath.get(path);
        if (artifact != null && isDirect(artifact, declaredKeys)) {
            return new ClasspathEntry(path, Origin.DECLARED,
                artifact.getGroupId() + ":" + artifact.getArtifactId(), stamp);
        }
        String reactorCoordinate = reactorOutputs.get(path);
        if (reactorCoordinate != null) {
            // A reactor module's output this module did not declare, whether it arrived through
            // the reactor fold or transitively through another dependency: offerable in the
            // census, rejected by the build naming the module, which is SIBLING's whole point.
            return new ClasspathEntry(path, Origin.SIBLING, reactorCoordinate, stamp);
        }
        if (artifact != null) {
            return new ClasspathEntry(path, Origin.TRANSITIVE,
                artifact.getGroupId() + ":" + artifact.getArtifactId(), stamp);
        }
        return new ClasspathEntry(path, Origin.DECLARED, null, stamp);
    }

    /**
     * The content identity a repository already established for {@code path}, read off the Maven
     * checksum sidecar Maven wrote beside it at download, or null where there is nothing to read.
     * This is the only code in the tree that knows what a checksum sidecar is; everything
     * downstream sees a stamp or sees none.
     *
     * <p>What the sidecar's <em>presence</em> means is the point, not that SHA-1 is a good hash. A
     * jar with one was resolved from a repository, and repositories do not republish a coordinate
     * under new bytes, so the path is the identity and re-hashing it every round re-establishes
     * something already established. A locally installed artifact gets {@code _remote.repositories}
     * and no checksum sidecar, which is exactly the population that can change underneath a running
     * session, so it keeps being hashed.
     *
     * <p>One way that inference breaks: the sidecar is written once at download and never
     * maintained, so installing over a release coordinate leaves it vouching for bytes that are
     * gone. Two stats close it. A sidecar older than the jar it describes is not trusted, and the
     * jar falls back to hashing. The whole check is re-taken every round, the classpath being
     * resolved per round, so a jar overwritten mid-session stops being trusted on the next one.
     */
    private static String resolvedIdentity(Path path) {
        Path sidecar = path.resolveSibling(path.getFileName() + ".sha1");
        try {
            if (!Files.isRegularFile(path) || !Files.isRegularFile(sidecar)
                || Files.size(sidecar) > MAX_CHECKSUM_SIDECAR_BYTES
                || Files.getLastModifiedTime(sidecar)
                    .compareTo(Files.getLastModifiedTime(path)) < 0) {
                return null;
            }
            // Maven writes the bare digest; the sha1sum form ("<hex>  <name>") is tolerated by
            // taking the first token, and anything that is not a hex digest is rejected by
            // SourceStamp, which owns how the second scheme is spelled.
            String text = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
            int end = text.length();
            for (int i = 0; i < text.length(); i++) {
                if (Character.isWhitespace(text.charAt(i))) {
                    end = i;
                    break;
                }
            }
            return SourceStamp.ofSha1(text.substring(0, end));
        } catch (IOException | RuntimeException e) {
            // An unreadable or undecodable sidecar is one we have no identity from, which is the
            // same answer as having none at all: the jar gets hashed.
            return null;
        }
    }

    /**
     * Whether {@code artifact} was declared by this module rather than dragged in transitively.
     * The dependency trail, where resolution populated it, states this in one place: element zero
     * is the project itself, so a two-element trail is a direct dependency. The declared join is
     * the fallback for an unpopulated trail only.
     */
    private static boolean isDirect(Artifact artifact, Set<String> declaredKeys) {
        List<String> trail = artifact.getDependencyTrail();
        if (trail != null && trail.size() >= 2) {
            return trail.size() == 2;
        }
        return declaredKeys.contains(dependencyKey(artifact.getGroupId(),
            artifact.getArtifactId(), artifact.getType(), artifact.getClassifier()));
    }

    /** The declared-join key: groupId, artifactId, type and classifier, never version. */
    private static String dependencyKey(String groupId, String artifactId, String type, String classifier) {
        return groupId + ":" + artifactId
            + ":" + (type == null || type.isEmpty() ? "jar" : type)
            + ":" + (classifier == null ? "" : classifier);
    }

    /**
     * The reactor projects whose roots the LSP scans: every project in the Maven session, so
     * {@code mvn graphitron:dev} run from one module sees services / tables declared in sibling
     * modules of the same reactor. Falls back to the current project alone when the session is
     * unavailable (unit-tier callers that bypass the full lifecycle).
     */
    private Iterable<MavenProject> reactorProjects() {
        return session != null && session.getAllProjects() != null
            ? session.getAllProjects()
            : List.of(project);
    }

    /**
     * True when the reactor is a single project: {@code mvn graphitron:dev} run from inside one
     * sub-module of a multi-module build, where Maven loads only that module's pom and sibling
     * service / condition / record classes would be invisible to the catalog scan. This is the
     * shape {@link #siblingModuleBasedirs()} widens. A genuine multi-module reactor is excluded
     * ({@code getAllProjects()} carries the full set even under {@code -pl} filtering), as are
     * sessionless unit-tier callers; for both, the walk-up must stay inert.
     */
    boolean singleProjectReactor() {
        return session != null
            && session.getAllProjects() != null
            && session.getAllProjects().size() == 1;
    }

    /**
     * Sibling module basedirs to widen the scan / walk over when this is a
     * {@link #singleProjectReactor() single-project reactor}, in declared {@code <modules>}
     * document order; empty otherwise.
     */
    List<Path> siblingModuleBasedirs() {
        if (!singleProjectReactor()) {
            return List.of();
        }
        return siblingModuleBasedirs(project.getBasedir().toPath());
    }

    /**
     * Walks up from {@code currentBasedir} to the nearest ancestor {@code pom.xml} whose
     * {@code <modules>} resolve to include it, and returns that ancestor's <em>other</em>
     * modules' basedirs in declared document order; empty when no ancestor lists the current
     * project, so a genuine standalone module is unaffected.
     *
     * <p>Declared {@code <modules>} order is the only ordering input: a {@code Files.list} over
     * the parent is unordered and would break the catalog's determinism guarantee. Resolving
     * sibling directories by convention, rather than constructing {@link MavenProject} instances
     * for modules the session never loaded, is the deliberate scope of this fallback; a custom
     * {@code <build>} output/source directory in a sibling is out of scope.
     */
    static List<Path> siblingModuleBasedirs(Path currentBasedir) {
        Path current = currentBasedir.toAbsolutePath().normalize();
        Path aggregator = nearestAggregator(current);
        if (aggregator == null) {
            return List.of();
        }
        var siblings = new ArrayList<Path>();
        for (String module : parseModules(aggregator.resolve("pom.xml"))) {
            Path moduleBase = resolveModuleBasedir(aggregator, module);
            if (moduleBase != null && !moduleBase.equals(current)) {
                siblings.add(moduleBase);
            }
        }
        return List.copyOf(siblings);
    }

    /**
     * One step of the aggregator chain, shared by the sibling scan and the workspace resolver so
     * the two questions share one notion of "which reactor is this module part of": the nearest
     * ancestor directory whose {@code pom.xml}'s {@code <modules>} resolve to include
     * {@code currentBasedir}, or {@code null} when no ancestor lists it. Answered off the
     * filesystem, so it gives the same answer from the reactor root and from inside the module.
     */
    private static Path nearestAggregator(Path currentBasedir) {
        Path current = currentBasedir.toAbsolutePath().normalize();
        for (Path dir = current.getParent(); dir != null; dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            for (String module : parseModules(pom)) {
                Path moduleBase = resolveModuleBasedir(dir, module);
                if (current.equals(moduleBase)) {
                    return dir;
                }
            }
        }
        return null;
    }

    /**
     * The {@code <modules>} entries of {@code pom} in document order. Lenient: a malformed or
     * unreadable ancestor pom yields no modules rather than failing the goal. Profile-scoped
     * {@code <modules>} are not consulted; the top-level aggregator layout is the supported
     * shape.
     */
    private static List<String> parseModules(Path pom) {
        try (var reader = Files.newBufferedReader(pom)) {
            Model model = new MavenXpp3Reader().read(reader, false);
            return model.getModules() != null ? model.getModules() : List.of();
        } catch (IOException | XmlPullParserException e) {
            return List.of();
        }
    }

    private static Path resolveModuleBasedir(Path ancestorDir, String module) {
        if (module == null || module.isBlank()) {
            return null;
        }
        Path resolved = ancestorDir.resolve(module.trim()).toAbsolutePath().normalize();
        Path fileName = resolved.getFileName();
        if (fileName != null && fileName.toString().equals("pom.xml")) {
            return resolved.getParent();
        }
        return resolved;
    }

    /**
     * Compile source-root directories from every reactor project: the hand-written
     * {@code src/main/java} roots plus the generated-sources roots discovered on disk by
     * {@link #generatedSourceRoots(MavenProject)} (jOOQ output among them). The LSP parses
     * these to recover Java declaration positions and Javadoc for goto-definition / hover.
     *
     * <p>Resolved over the same {@link #reactorProjects()} set as
     * {@link #reactorOutputCoordinates()}, so the scan path and the walk path cannot drift in
     * which modules they cover: a class scanned for completion is a class whose source root is
     * walked for goto-definition.
     *
     * <p>The generated-sources half is taken from disk rather than from
     * {@code project.getCompileSourceRoots()}, which only carries a generated-sources root once
     * the owning module's codegen plugin has executed in <em>this</em> session; a sibling jOOQ
     * module built in a prior session would otherwise be jumpable in completion but dead on
     * goto-definition. The disk scan is lifecycle-independent, the property the classpath side
     * already has, and a root the plugin also registered dedups away in
     * {@link #collectExistingDirs}, so the widening is a no-op under a full-lifecycle goal.
     */
    private List<Path> resolveCompileSourceRoots() {
        var roots = new LinkedHashSet<>(
            collectExistingDirs(reactorProjects(), AbstractRewriteMojo::compileSourceRootsOf));
        // Same sibling set as reactorOutputCoordinates(): a sibling scanned for
        // completion also gets its source roots walked for goto-definition.
        for (Path base : siblingModuleBasedirs()) {
            addExistingDir(roots, base.resolve("src/main/java"));
            for (String generated : generatedSourceRootsUnder(base.resolve("target"))) {
                addExistingDir(roots, Path.of(generated));
            }
        }
        return new ArrayList<>(roots);
    }

    /**
     * The same normalise-and-keep-existing discipline {@link #collectExistingDirs} applies to
     * reactor-project dirs, for a single loose path.
     */
    private static void addExistingDir(LinkedHashSet<Path> into, Path candidate) {
        Path path = candidate.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            into.add(path);
        }
    }

    /**
     * The walked source roots for one project: {@code getCompileSourceRoots()} unioned with
     * {@link #generatedSourceRoots(MavenProject)}. The single per-module definition of "what is
     * walked", shared by {@link #resolveCompileSourceRoots()} and
     * {@link #unwalkedScannedModules(Iterable)} so the resolver and the diagnostic cannot
     * disagree.
     */
    static Collection<String> compileSourceRootsOf(MavenProject p) {
        var roots = new ArrayList<String>();
        if (p.getCompileSourceRoots() != null) {
            roots.addAll(p.getCompileSourceRoots());
        }
        roots.addAll(generatedSourceRoots(p));
        return roots;
    }

    /**
     * The existing immediate subdirectories of
     * {@code ${project.build.directory}/generated-sources/} (for example
     * {@code target/generated-sources/jooq}).
     *
     * <p>Discovered from disk by convention, not parsed out of any code generator's plugin
     * configuration: the {@code generated-sources/<tool>} layout is what every generator
     * follows, while the plugin coordinate and its configurable {@code <directory>} are not, so
     * a POM-config scan would be plugin-specific and fragile. Over-inclusion
     * (annotation-processor output, graphitron's own emitted resolvers) is cheap for the
     * parse-only {@code SourceWalker} and harmless for what it writes: the store's
     * {@code java_} family holds a row per declaration, so a file that need not have been
     * walked adds rows nobody joins rather than colliding with the ones somebody does.
     *
     * <p>Empty when the project has no build directory or no {@code generated-sources} on disk.
     * Sorted for a deterministic order across filesystems.
     */
    static List<String> generatedSourceRoots(MavenProject project) {
        var build = project.getBuild();
        if (build == null || build.getDirectory() == null) {
            return List.of();
        }
        return generatedSourceRootsUnder(Path.of(build.getDirectory()));
    }

    /**
     * The existing immediate subdirectories of {@code <targetDir>/generated-sources/}, sorted
     * for a deterministic order across filesystems. {@link #generatedSourceRoots(MavenProject)}
     * delegates here; the sibling-widening path calls it directly against
     * {@code <siblingBasedir>/target}, since an unloaded sibling has no {@link MavenProject} to
     * read a build directory from.
     */
    static List<String> generatedSourceRootsUnder(Path targetDir) {
        Path generatedSources = targetDir.resolve("generated-sources");
        if (!Files.isDirectory(generatedSources)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(generatedSources)) {
            return entries
                .filter(Files::isDirectory)
                .map(Path::toString)
                .sorted()
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Shared traversal behind {@link #resolveCompileSourceRoots()} and
     * {@link #unwalkedScannedModules(Iterable)}: normalise the directories {@code extractor}
     * pulls off each project and keep the existing ones, de-duplicated in encounter order.
     * Package-private so the classpath/source-root parity can be pinned with a unit test over
     * hand-built projects, without standing up a {@link MavenSession}.
     */
    static List<Path> collectExistingDirs(
        Iterable<MavenProject> projects, Function<MavenProject, Collection<String>> extractor
    ) {
        var roots = new LinkedHashSet<Path>();
        for (MavenProject p : projects) {
            Collection<String> dirs = extractor.apply(p);
            if (dirs == null) continue;
            for (String dir : dirs) {
                if (dir == null) continue;
                Path path = Path.of(dir).toAbsolutePath().normalize();
                if (Files.isDirectory(path)) {
                    roots.add(path);
                }
            }
        }
        return new ArrayList<>(roots);
    }

    /** The scanned-but-unwalked reactor modules for the current session; see
     *  {@link #unwalkedScannedModules(Iterable)}. {@code DevMojo} renders these
     *  as a startup {@code WARN}. */
    List<String> unwalkedScannedModules() {
        return unwalkedScannedModules(reactorProjects());
    }

    /**
     * Reactor modules whose compile output is scanned for the LSP catalog but which contribute
     * no walked source root, so a class found for completion has no source position and every
     * goto-definition / hover on its declarations is a silent no-jump. This is the residue the
     * {@link #generatedSourceRoots(MavenProject)} auto-include cannot close, for example a
     * table class that arrives only as a published dependency JAR with no {@code .java} to
     * walk.
     *
     * <p>A pure function over the projects because the determination is a per-module set
     * difference and {@link #collectExistingDirs} deliberately flattens away the owning-project
     * provenance that difference needs; the Mojo only renders the result. "Walked" is decided
     * by {@link #compileSourceRootsOf(MavenProject)}, the same definition
     * {@link #resolveCompileSourceRoots()} uses, so the two cannot disagree. Returns the
     * offending modules' {@link MavenProject#getId() ids} in reactor order.
     */
    static List<String> unwalkedScannedModules(Iterable<MavenProject> projects) {
        var unwalked = new ArrayList<String>();
        for (MavenProject p : projects) {
            boolean scanned = p.getBuild() != null
                && p.getBuild().getOutputDirectory() != null
                && Files.isDirectory(
                    Path.of(p.getBuild().getOutputDirectory()).toAbsolutePath().normalize());
            if (!scanned) {
                continue;
            }
            boolean walked = !collectExistingDirs(
                List.of(p), AbstractRewriteMojo::compileSourceRootsOf).isEmpty();
            if (!walked) {
                unwalked.add(p.getId());
            }
        }
        return unwalked;
    }

    /**
     * The run's schema files, for the diagnostic that lists what was loaded. An exhaustive switch
     * over the source arms rather than a string projection: a label names no file, so it has nothing
     * to contribute to a listing of files and nothing to compare against the orphan scan's walk.
     */
    private static Set<Path> loadedSchemaFiles(RunContext ctx) {
        var files = new LinkedHashSet<Path>();
        for (SchemaInput input : ctx.schemaInputs()) {
            switch (input.source()) {
                case SchemaSource.File file -> files.add(file.path());
                case SchemaSource.Named ignored -> { }
            }
        }
        return files;
    }

    /**
     * Builds the context and invokes the generator through a single error-handling path so
     * every goal surfaces {@link RuntimeException}s wrapped as {@link MojoExecutionException}.
     * Goals with work after a successful generator call (e.g. registering the generated roots
     * with Maven) read the returned {@link RunContext} so they reuse the paths
     * {@link #buildContext} computed.
     *
     * <p>The returned context's {@code codegenLoader} has been closed by the time this method
     * returns; callers must not call back into the reflection seams off the returned context.
     * The path-shaped fields ({@link RunContext#outputDirectory},
     * {@link RunContext#outputResourcesDirectory}, {@link RunContext#basedir}) remain
     * valid.
     */
    protected final RunContext runGenerator(GeneratorCall call) throws MojoExecutionException {
        var holder = new RunContext[1];
        withCodegenScope(ctx -> {
            holder[0] = ctx;
            // One store for the whole invocation, opened by the port on the pass's own capture and
            // given back here. The generator is handed the port rather than the directory, so a
            // goal that grows a second pass shares this store with it instead of opening another.
            try (CapturePort capture = CapturePort.holding(ctx.storeDirectory())) {
                call.invoke(new GraphQLRewriteGenerator(ctx, capture));
            } catch (SchemaProblem e) {
                var loaded = loadedSchemaFiles(ctx);
                // Wrap the SchemaProblem in a null-message intermediary so Maven's
                // DefaultExceptionHandler does not append SchemaProblem.getMessage()
                // ("errors=[...]") to our formatted diagnostic. The original
                // SchemaProblem stays on the cause chain for `-e` / `-X` consumers.
                throw new MojoExecutionException(
                    SchemaProblemDiagnostic.format(e, loaded, ctx.basedir(), ctx.schemaFileExtensions()),
                    new RuntimeException((String) null, e));
            } catch (ValidationFailedException e) {
                // Render the carried errors so one-shot `generate` / `validate` surface the same
                // file:line:col detail DevMojo renders, whichever build stage raised the
                // exception. The null-message intermediary works as in the SchemaProblem arm
                // above: it keeps Maven from appending the bare "N schema validation error(s)"
                // count while the cause chain stays intact for `-e` / `-X` consumers.
                throw new MojoExecutionException(
                    validationFailureMessage(e.errors()),
                    new RuntimeException((String) null, e));
            } catch (RuntimeException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        });
        return holder[0];
    }

    /**
     * Renders a {@link ValidationFailedException}'s carried errors into the one-shot mojo
     * failure message. Delegates to {@link WatchErrorFormatter#format}, the same renderer the
     * {@code graphitron:dev} loop uses, so the one-shot and dev surfaces cannot drift; the
     * {@code null} previous-key set drops the dev-only delta line. The header mirrors the
     * {@link SchemaProblemDiagnostic} arm so both schema-failure surfaces read alike.
     */
    static String validationFailureMessage(List<ValidationError> errors) {
        return "GraphQL schema validation failed:\n\n" + WatchErrorFormatter.format(errors, null);
    }

    /**
     * Runs {@code body} inside a freshly-built codegen scope. The loader is published both to
     * the {@link RunContext} (explicit threading for the in-process reflection sites) and
     * as the thread's context classloader (defense-in-depth for third-party transitive callees,
     * e.g. graphql-java / jOOQ / consumer-class static initializers). The previous TCCL is
     * restored and the loader closed to release JAR file descriptors, which matters for the
     * dev-mode loop that rebuilds the loader on every regeneration cycle.
     */
    protected final void withCodegenScope(CodegenScopeBody body) throws MojoExecutionException {
        var previousTccl = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader codegenLoader = buildCodegenLoader()) {
            Thread.currentThread().setContextClassLoader(codegenLoader);
            var ctx = buildContext(codegenLoader);
            body.run(ctx);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to close codegen classloader", e);
        } finally {
            Thread.currentThread().setContextClassLoader(previousTccl);
        }
    }

    /**
     * The project-aware classloader the reflection path uses to resolve consumer
     * service / record / condition / jOOQ-catalog classes: every entry of
     * {@link #resolveCompileClasspath()}, which is also what the context carries as
     * {@code classpathRoots}. Reading the one classified list rather than reassembling it here is
     * what keeps the census a projection of the loader's list; when the two were assembled
     * separately they agreed by coincidence, and the coincidence had already broken. The loader
     * projects every entry while the census skips the {@code TRANSITIVE} ones, so a class can
     * resolve here without being nameable in a schema; the nameability rule
     * ({@code ClasspathNameability} over the same list) is what rejects a schema that names one.
     * The parent is the plugin's own loader so the generator's classes still resolve; delegation
     * is therefore parent-first for anything the plugin also carries, which is why the
     * nameability rule probes entries for the name instead of asking where a loaded class came
     * from.
     */
    private URLClassLoader buildCodegenLoader() throws MojoExecutionException {
        var urls = new LinkedHashSet<URL>();
        for (ClasspathEntry entry : resolveCompileClasspath()) {
            try {
                urls.add(entry.path().toUri().toURL());
            } catch (MalformedURLException e) {
                throw new MojoExecutionException(
                    "Failed to add classpath entry " + entry.path() + " to the codegen classpath.", e);
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
    }
}
