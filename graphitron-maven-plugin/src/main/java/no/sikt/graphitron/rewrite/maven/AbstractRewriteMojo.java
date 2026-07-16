package no.sikt.graphitron.rewrite.maven;

import graphql.schema.idl.errors.SchemaProblem;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.ValidationFailedException;
import no.sikt.graphitron.rewrite.maven.watch.WatchErrorFormatter;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared configuration surface for {@link GenerateMojo} and {@link ValidateMojo}.
 */
public abstract class AbstractRewriteMojo extends AbstractMojo {

    /** Sentinel used for validate-only invocations that do not emit code. */
    private static final String VALIDATE_ONLY_PACKAGE = "validation.unused";

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    MavenSession session;

    @Parameter
    List<SchemaInputBinding> schemaInputs;

    /**
     * File-name suffixes that count as GraphQL schema files. Drives the
     * {@code <schemaInputs>} post-scan filter, the {@code graphitron:dev} watcher's trigger
     * filter, and the {@code SchemaProblemDiagnostic} orphan scan. Suffixes are matched with
     * {@code String.endsWith(String)} on the file-name component; leading dots are optional (both
     * {@code .graphqls} and {@code graphqls} normalise to {@code .graphqls}). Case-sensitive
     * on case-sensitive filesystems; Graphitron does not lower-case.
     *
     * <p>Omit (or leave empty in the POM, which Maven binds as {@code null}) to accept the
     * default {@code [".graphqls", ".graphql"]}. A configured but empty list is rejected at
     * Mojo execute.
     */
    @Parameter
    List<String> schemaFileExtensions;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/graphitron")
    String outputDirectory;

    @Parameter
    String outputPackage;

    @Parameter
    String jooqPackage;

    @Parameter
    List<NamedReferenceBinding> namedReferences;

    /**
     * Lint suppression. A {@code <lint>} block naming rule ids to silence everywhere
     * ({@code <disabledRules>}) and type-name globs to exclude from the SDL lint engine
     * ({@code <excludedTypes>}). Threaded through {@link RewriteContext} so suppression is applied at
     * the one build evaluator; the {@code graphitron:dev} LSP and MCP diagnostics suppress
     * identically. A disabled rule id that resolves to no rule fails the build with the list of valid
     * ids. Omit the block to lint every author-owned type with every rule.
     */
    @Parameter
    LintBinding lint;

    /**
     * Session identity. A {@code <sessionState>} block naming how per-request identity
     * is mounted on the pinned connection: either consumer-authored database callables
     * ({@code <connect call>} / {@code <disconnect call>}, with an optional OUT {@code handle}) or the
     * Postgres {@code <variables>} sugar ({@code <variable name claim>}) that generates both hook
     * halves. Threaded through {@link RewriteContext};
     * {@link no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator} emits the
     * concrete hook from it. An unpaired or handle-inconsistent block fails the build. Omit to mount no
     * identity ({@code SessionHook.NONE}).
     */
    @Parameter
    SessionStateBinding sessionState;

    @FunctionalInterface
    protected interface GeneratorCall {
        void invoke(GraphQLRewriteGenerator gen);
    }

    /**
     * Body for {@link #withCodegenScope}: receives a {@link RewriteContext} whose
     * {@code codegenLoader} is wired to a freshly-built {@link URLClassLoader} that has been
     * installed as the thread's context classloader. The loader is closed and the previous TCCL
     * restored after {@code run} returns (or throws).
     */
    @FunctionalInterface
    protected interface CodegenScopeBody {
        void run(RewriteContext ctx) throws MojoExecutionException;
    }

    /**
     * Returns {@code true} if this goal needs {@code <outputPackage>} and
     * {@code <jooqPackage>}, {@code false} if it tolerates their absence
     * (validate-only goals substitute an inert sentinel). The sentinel lets
     * {@code mvn graphitron:validate} work standalone from the CLI
     * without per-consumer execution wiring; the validate pipeline never
     * emits code, so the package strings are only used to satisfy
     * {@link RewriteContext}'s non-null contract.
     */
    protected abstract boolean packagesRequired();

    protected final RewriteContext buildContext() throws MojoExecutionException {
        return buildContext(Thread.currentThread().getContextClassLoader());
    }

    private RewriteContext buildContext(ClassLoader codegenLoader) throws MojoExecutionException {
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
        var expansion = SchemaInputExpander.expand(schemaInputs, basedir, extensions);
        for (var ep : expansion.emptyPatterns()) {
            getLog().warn("<schemaInput pattern='" + ep.pattern() + "'> (entry #"
                + ep.entryIndex() + ") matched no files; skipping");
        }
        return new RewriteContext(
            expansion.inputs(),
            extensions,
            basedir,
            outAbs,
            resourcesAbs,
            effectiveOutput,
            effectiveJooq,
            toNamedReferenceMap(namedReferences),
            resolveClasspathRoots(),
            codegenLoader,
            resolveCompileSourceRoots(),
            buildLintConfig(),
            buildSessionStateConfig()
        );
    }

    /**
     * Builds the {@link LintConfig} from the {@code <lint>} block, trimming blanks and validating every
     * disabled rule id against {@code LintRule.id()}. An unknown id is a typo the build must not
     * silently ignore, so {@link LintConfig#validated} throws and this wraps it as a
     * {@link MojoExecutionException} carrying the list of valid ids. Returns {@link LintConfig#empty()}
     * when the block is omitted.
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
     * Builds the {@link SessionStateConfig} from the {@code <sessionState>} block, mapping the POM
     * binding into the raw shape {@code SessionStateConfig.from(...)} reconciles and validates. The
     * pairing, handle-consistency, and one-form-only rejections live in the config object (a
     * {@code pom.xml} defect has no SDL coordinate), which throws {@link IllegalArgumentException}; this
     * wraps it as a {@link MojoExecutionException}. Returns {@link SessionStateConfig#none()} when the
     * block is omitted (mounts no identity: {@code SessionHook.NONE}).
     */
    private SessionStateConfig buildSessionStateConfig() throws MojoExecutionException {
        if (sessionState == null) {
            return SessionStateConfig.none();
        }
        var connect = toRawHook(sessionState.connect);
        var disconnect = toRawHook(sessionState.disconnect);
        try {
            var variables = new ArrayList<SessionStateConfig.Variable>();
            if (sessionState.variables != null) {
                for (var v : sessionState.variables) {
                    if (v == null) continue;
                    variables.add(new SessionStateConfig.Variable(trimOrNull(v.name), trimOrNull(v.claim)));
                }
            }
            return SessionStateConfig.from(connect, disconnect, variables, sessionState.stateSurvivesTransactions);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Maps a POM {@code <connect>}/{@code <disconnect>} element to the raw shape: {@code null} when the
     * element is absent, a {@link SessionStateConfig.RawHook} with a {@code null} call when it is present
     * but empty (the unmount-free marker).
     */
    private static SessionStateConfig.RawHook toRawHook(SessionStateBinding.HookBinding hook) {
        if (hook == null) {
            return null;
        }
        return new SessionStateConfig.RawHook(trimOrNull(hook.call), hook.handle);
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
     * Normalises the {@code <schemaFileExtensions>} parameter into the set threaded through the
     * pipeline. Each entry is trimmed; entries missing a leading dot get one prepended; case is
     * preserved. A configured but empty list is rejected with a clear message. Returns the
     * default {@link RewriteContext#DEFAULT_SCHEMA_FILE_EXTENSIONS} when the field is null
     * (parameter omitted from the POM).
     */
    Set<String> effectiveSchemaFileExtensions() throws MojoExecutionException {
        if (schemaFileExtensions == null) {
            return RewriteContext.DEFAULT_SCHEMA_FILE_EXTENSIONS;
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
                    + String.join(", ", RewriteContext.DEFAULT_SCHEMA_FILE_EXTENSIONS) + "]");
        }
        return Set.copyOf(normalised);
    }

    /**
     * Derives the {@code generated-resources/graphitron} root from
     * {@code project.getBuild().getDirectory()} with a {@code basedir/target}
     * fallback. The relative segment is a hardcoded Maven convention
     * ({@code generated-resources/<plugin-name>}); not user-configurable.
     *
     * <p>The fallback handles hand-built {@link MavenProject} instances used by
     * unit-tier callers ({@code DevMojoTest}, {@code CodegenLoaderTest},
     * {@code GenerateMojoTest}), where {@code project.getBuild()} returns
     * either {@code null} or a default {@code Build} with no directory set.
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
     * Derives the {@code graphitron-mcp-rag} cache root under {@code project.build.directory},
     * with the same {@code basedir/target} fallback {@link #resolveOutputResourcesDirectory(Path)}
     * uses for hand-built {@link MavenProject} test instances. The semantic catalog index persists
     * its content-hash-keyed Lucene directories here, so it survives {@code dev} restarts and dies on
     * {@code mvn clean}.
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
     * incremental compile driver writes into, derived from {@code project.build.directory} with the same
     * {@code basedir/target} fallback {@link #resolveOutputResourcesDirectory(Path)} uses for hand-built
     * {@link MavenProject} test instances. A directory graphitron solely writes (never the shared
     * {@code target/classes}), which is what makes the incremental compile sound by construction; it is
     * placed first on the run classpath so a fresh copy shadows any stale copy in {@code target/classes}.
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
     * The compile classpath the incremental compile engine scans once at dev startup: every entry
     * in {@code project.getCompileClasspathElements()} (the consumer's compile dep graph plus its own
     * {@code target/classes}) unioned with every reactor sibling's {@code target/classes} (the same set
     * {@link #resolveClasspathRoots()} feeds the LSP catalog scan). This is exactly a
     * {@code StandardJavaFileManager}'s input set; the engine additionally front-loads its own output dir
     * so already-compiled units resolve as dependencies of a later round. The generated code's references
     * <em>into</em> consumer / jOOQ / dependency classes are already compiled and resolve off this path.
     */
    protected final List<Path> resolveCompileClasspath() throws MojoExecutionException {
        var paths = new LinkedHashSet<Path>();
        try {
            for (String element : project.getCompileClasspathElements()) {
                paths.add(Path.of(element).toAbsolutePath().normalize());
            }
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException(
                "Failed to assemble the project compile classpath for incremental compile.", e);
        }
        paths.addAll(resolveClasspathRoots());
        return new ArrayList<>(paths);
    }

    /**
     * The reactor projects whose roots the LSP scans: every project in the
     * Maven session, so a consumer running {@code mvn graphitron:dev} from one
     * module sees services / tables declared in sibling modules of the same
     * reactor. Falls back to the current project alone when the session is
     * unavailable (unit-tier callers that bypass the full lifecycle), which
     * matches pre-multi-module behaviour.
     */
    private Iterable<MavenProject> reactorProjects() {
        return session != null && session.getAllProjects() != null
            ? session.getAllProjects()
            : List.of(project);
    }

    /**
     * True when the Maven reactor for this invocation is a single project, i.e.
     * {@code mvn graphitron:dev} was run from inside one sub-module of a
     * multi-module build (Maven loads only that module's pom). This is the shape
     * the sibling walk-up ({@link #siblingModuleBasedirs()}) targets: the reactor
     * contains the current module alone, so its sibling service / condition /
     * record classes would otherwise be invisible to the catalog scan.
     *
     * <p>Returns {@code false} for a genuine multi-module reactor (run from the
     * parent, where {@code getAllProjects()} carries the full set even under
     * {@code -pl} filtering) and for unit-tier callers with no session, where the
     * walk-up must stay inert.
     */
    boolean singleProjectReactor() {
        return session != null
            && session.getAllProjects() != null
            && session.getAllProjects().size() == 1;
    }

    /**
     * Sibling module basedirs to widen the scan / walk over when this is a
     * {@link #singleProjectReactor() single-project reactor}, in declared
     * {@code <modules>} document order; empty otherwise (including every
     * multi-module reactor and unit-tier caller). Delegates the directory walk to
     * {@link #siblingModuleBasedirs(Path)} from the current project's basedir.
     */
    List<Path> siblingModuleBasedirs() {
        if (!singleProjectReactor()) {
            return List.of();
        }
        return siblingModuleBasedirs(project.getBasedir().toPath());
    }

    /**
     * Walks up from {@code currentBasedir} to the nearest ancestor {@code pom.xml}
     * whose {@code <modules>} list resolves to include {@code currentBasedir}, and
     * returns that ancestor's <em>other</em> modules' basedirs in declared document
     * order. The walk stops at the first such ancestor (the standard aggregator
     * layout) and returns empty when none lists the current project, so a genuine
     * standalone module is unaffected.
     *
     * <p>Module entries are resolved against the ancestor's directory and compared
     * by normalised absolute path; an entry that points straight at a {@code pom.xml}
     * is normalised to its containing directory. Declared {@code <modules>} order is
     * the only ordering input (no {@code Files.list} over the parent, which is
     * unordered and would break the catalog's determinism guarantee). Resolving
     * sibling directories by convention, rather than constructing {@link MavenProject}
     * instances for modules the session never loaded, is the deliberate scope of this
     * fallback (a custom {@code <build>} output/source directory in a sibling is out
     * of scope).
     */
    static List<Path> siblingModuleBasedirs(Path currentBasedir) {
        Path current = currentBasedir.toAbsolutePath().normalize();
        for (Path dir = current.getParent(); dir != null; dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            var siblings = new ArrayList<Path>();
            boolean listsCurrent = false;
            for (String module : parseModules(pom)) {
                Path moduleBase = resolveModuleBasedir(dir, module);
                if (moduleBase == null) {
                    continue;
                }
                if (moduleBase.equals(current)) {
                    listsCurrent = true;
                } else {
                    siblings.add(moduleBase);
                }
            }
            if (listsCurrent) {
                return List.copyOf(siblings);
            }
        }
        return List.of();
    }

    /**
     * Reads the {@code <modules>} entries of {@code pom} in document order, lenient
     * about an off-spec pom (a malformed or unreadable ancestor pom yields no
     * modules rather than failing the goal). Profile-scoped {@code <modules>} are
     * not consulted; the standard top-level aggregator layout is the supported
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

    /**
     * Resolves a {@code <module>} entry to its basedir relative to the ancestor
     * pom's directory. A module declared as a path to a {@code pom.xml} file
     * normalises to that file's parent directory; the common directory form
     * resolves directly. Returns {@code null} for an empty entry.
     */
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
     * Compile-output directories from every reactor project. The LSP catalog
     * scans each one for service / condition / record class candidates.
     * External jars on the compile classpath ({@code ~/.m2}) are intentionally
     * not scanned: services live in reactor source code, not third-party
     * libraries.
     *
     * <p>When the session is a single-project reactor (a consumer running
     * {@code mvn graphitron:dev} from inside one sub-module, so
     * {@code getAllProjects()} sees only that module), the sibling modules
     * declared by the nearest ancestor pom are folded in by convention through
     * {@link #siblingModuleBasedirs()}: each sibling's {@code target/classes}.
     * The widening rides the same {@link #collectExistingDirs} existence filter
     * and dedup as the reactor roots, so a sibling already present in a
     * non-trivial reactor collapses to a no-op.
     */
    private List<Path> resolveClasspathRoots() {
        var roots = new LinkedHashSet<>(collectExistingDirs(reactorProjects(), p -> {
            String dir = p.getBuild() == null ? null : p.getBuild().getOutputDirectory();
            return dir == null ? List.of() : List.of(dir);
        }));
        for (Path base : siblingModuleBasedirs()) {
            addExistingDir(roots, base.resolve("target/classes"));
        }
        return new ArrayList<>(roots);
    }

    /**
     * Compile source-root directories from every reactor project: the
     * hand-written {@code src/main/java} roots plus the generated-sources roots
     * discovered on disk by {@link #generatedSourceRoots(MavenProject)} (jOOQ
     * output among them). The LSP parses these to recover Java declaration
     * positions and Javadoc for goto-definition / hover on both halves.
     *
     * <p>Resolved over the same {@link #reactorProjects()} set as
     * {@link #resolveClasspathRoots()} through the shared
     * {@link #collectExistingDirs} traversal, so the scan path and the walk path
     * cannot drift in which modules they cover: a class scanned for completion
     * is a class whose source root is walked for goto-definition.
     *
     * <p>The generated-sources half is taken from disk rather than from
     * {@code project.getCompileSourceRoots()}, which only carries a
     * generated-sources root once the owning module's codegen plugin has
     * executed in <em>this</em> session. A sibling jOOQ module scanned for
     * completion (its {@code target/classes} exists from a prior build) but not
     * built this session would otherwise contribute zero source roots, leaving
     * its table classes jumpable in completion but dead on goto-definition. The
     * disk scan is lifecycle-independent, the same property the classpath side
     * already has, so it restores parity for that case. A root the plugin
     * also registered collapses with the discovered one in
     * {@link #collectExistingDirs} (dedup by normalised absolute path), so the
     * widening is a no-op under a full-lifecycle goal.
     */
    private List<Path> resolveCompileSourceRoots() {
        var roots = new LinkedHashSet<>(
            collectExistingDirs(reactorProjects(), AbstractRewriteMojo::compileSourceRootsOf));
        // Mirror the classpath-side widening on the source side so a sibling
        // scanned for completion also has its source root walked for
        // goto-definition: same sibling set, same parity invariant.
        // Per sibling: src/main/java plus the disk-discovered generated-sources/*.
        for (Path base : siblingModuleBasedirs()) {
            addExistingDir(roots, base.resolve("src/main/java"));
            for (String generated : generatedSourceRootsUnder(base.resolve("target"))) {
                addExistingDir(roots, Path.of(generated));
            }
        }
        return new ArrayList<>(roots);
    }

    /**
     * Normalises a loose directory path and adds it to {@code into} if it exists
     * on disk, the same per-directory discipline {@link #collectExistingDirs}
     * applies to reactor-project dirs. The {@link LinkedHashSet} preserves
     * encounter order and dedups a path already contributed by a reactor project.
     */
    private static void addExistingDir(LinkedHashSet<Path> into, Path candidate) {
        Path path = candidate.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            into.add(path);
        }
    }

    /**
     * The walked source roots for one project: its hand-written
     * {@code getCompileSourceRoots()} unioned with the disk-discovered
     * {@link #generatedSourceRoots(MavenProject) generatedSourceRoots}. The
     * single per-module definition of "what is walked", shared by
     * {@link #resolveCompileSourceRoots()} and
     * {@link #unwalkedScannedModules(Iterable)} so the resolver and the
     * unwalked-module diagnostic cannot disagree on the answer. De-duplication
     * of overlapping entries is left to {@link #collectExistingDirs}.
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
     * {@code target/generated-sources/jooq}, {@code .../graphitron},
     * {@code .../annotations}).
     *
     * <p>Discovered from disk by convention, not parsed out of any code
     * generator's plugin configuration (D1): the {@code generated-sources/<tool>}
     * layout is what every generator follows, while the plugin coordinate and its
     * configurable {@code <directory>} are not, so a POM-config scan would be
     * plugin-specific and fragile. Over-inclusion (annotation-processor output,
     * graphitron's own emitted resolvers) is cheap for the parse-only
     * {@code SourceWalker} and harmless for the class / field maps; the one
     * method-map hazard (a cross-file {@code (FQN, name, arity)} collision routing
     * to {@code Ambiguous}) cannot arise because graphitron emits into the
     * consumer's {@code outputPackage}, disjoint from the jOOQ table package the
     * catalog joins against.
     *
     * <p>Returns an empty list when the project has no build directory or no
     * {@code generated-sources} directory on disk. Sorted for a deterministic
     * order across filesystems.
     */
    static List<String> generatedSourceRoots(MavenProject project) {
        var build = project.getBuild();
        if (build == null || build.getDirectory() == null) {
            return List.of();
        }
        return generatedSourceRootsUnder(Path.of(build.getDirectory()));
    }

    /**
     * The existing immediate subdirectories of {@code <targetDir>/generated-sources/},
     * sorted for a deterministic order across filesystems. The
     * {@link MavenProject}-keyed {@link #generatedSourceRoots(MavenProject)} delegates
     * here; the sibling-widening path ({@link #resolveCompileSourceRoots()}) calls it
     * directly against {@code <siblingBasedir>/target}, since the convention is fixed
     * and an unloaded sibling has no {@link MavenProject} to read a build directory
     * from. Returns an empty list when no {@code generated-sources} directory
     * exists on disk.
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
     * Shared traversal behind {@link #resolveClasspathRoots()} and
     * {@link #resolveCompileSourceRoots()}: for each project, normalise the
     * directories {@code extractor} pulls off it to absolute paths and keep the
     * existing ones, de-duplicated in encounter order. Package-private so the
     * classpath/source root parity can be pinned with a unit test over
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
     * Reactor modules whose compile output ({@code target/classes}) is scanned
     * for the LSP catalog but which contribute no walked source root, so a class
     * found for completion has no source position and every goto-definition /
     * hover on its declarations is a silent no-jump.
     *
     * <p>This is the residue the {@link #generatedSourceRoots(MavenProject)}
     * auto-include cannot close: the auto-include fixes the reactor-source case
     * (a sibling whose generated sources are on disk) outright; this names what
     * remains, for example a table class that arrives only as a published
     * dependency JAR with no {@code .java} to walk.
     *
     * <p>The determination is a per-module set difference, not the count
     * comparison the {@code graphitron:dev} startup line does, and
     * {@link #collectExistingDirs} deliberately flattens away the owning-project
     * provenance that difference needs. So it lives here as a pure function over
     * the projects, unit-pinned the way {@link #collectExistingDirs} is; the
     * Mojo only renders the result. "Walked" is decided by
     * {@link #compileSourceRootsOf(MavenProject)}, the same definition
     * {@link #resolveCompileSourceRoots()} uses, so the two cannot disagree.
     * Returns the offending modules' {@link MavenProject#getId() ids} in reactor
     * order.
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
     * Builds the context and invokes the generator through a single
     * error-handling path so every goal surfaces {@link RuntimeException}s
     * wrapped as {@link MojoExecutionException}. Goals that have work to do
     * after a successful generator call (e.g. registering the generated
     * source / resource roots with Maven) read the returned
     * {@link RewriteContext} so they reuse the paths {@link #buildContext}
     * computed instead of recomputing them.
     *
     * <p>The returned context's {@code codegenLoader} has been closed by the
     * time this method returns; callers must not call back into the
     * reflection seams off the returned context. The path-shaped fields
     * ({@link RewriteContext#outputDirectory},
     * {@link RewriteContext#outputResourcesDirectory},
     * {@link RewriteContext#basedir}) remain valid.
     */
    protected final RewriteContext runGenerator(GeneratorCall call) throws MojoExecutionException {
        var holder = new RewriteContext[1];
        withCodegenScope(ctx -> {
            holder[0] = ctx;
            try {
                call.invoke(new GraphQLRewriteGenerator(ctx));
            } catch (SchemaProblem e) {
                var loaded = ctx.schemaInputs().stream()
                    .map(si -> si.sourceName())
                    .toList();
                // Wrap the SchemaProblem in a null-message intermediary so Maven's
                // DefaultExceptionHandler does not append SchemaProblem.getMessage()
                // ("errors=[...]") to our formatted diagnostic. The original
                // SchemaProblem stays on the cause chain for `-e` / `-X` consumers.
                throw new MojoExecutionException(
                    SchemaProblemDiagnostic.format(e, loaded, ctx.basedir(), ctx.schemaFileExtensions()),
                    new RuntimeException((String) null, e));
            } catch (ValidationFailedException e) {
                // Render the carried errors into the failure summary so one-shot `generate` /
                // `validate` surface the same file:line:col detail DevMojo already renders, no
                // matter which build stage raised the exception (validate(), the federation-recipe
                // rewrap in GraphitronSchemaBuilder.buildBundle, or TagLinkSynthesiser.apply).
                // Wrap the cause in a null-message intermediary, as the SchemaProblem arm does, so
                // Maven's DefaultExceptionHandler does not append the bare "N schema validation
                // error(s)" count after our detail; the ValidationFailedException stays on the
                // cause chain for `-e` / `-X` consumers.
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
     * Renders a {@link ValidationFailedException}'s carried errors into the one-shot mojo failure
     * message. Delegates the per-error tree to {@link WatchErrorFormatter#format}, the same renderer
     * the {@code graphitron:dev} loop uses, so the one-shot and dev surfaces cannot drift; the
     * {@code null} previous-key set drops the dev-only delta line. The leading header mirrors the
     * {@code SchemaProblemDiagnostic} arm so both schema-failure surfaces read alike.
     */
    static String validationFailureMessage(List<ValidationError> errors) {
        return "GraphQL schema validation failed:\n\n" + WatchErrorFormatter.format(errors, null);
    }

    /**
     * Runs {@code body} inside a freshly-built codegen scope: a project-aware
     * {@link URLClassLoader} over the consumer's compile classpath plus every reactor
     * sibling's {@code target/classes}, parented on the plugin loader. The loader is
     * published to both the {@link RewriteContext} (explicit threading for the in-process
     * reflection sites) and the running thread's context classloader (defense-in-depth for
     * third-party transitive callees, e.g. graphql-java / jOOQ / consumer-class static
     * initializers). The previous TCCL is restored in {@code finally} and the URLClassLoader
     * is closed to release JAR file descriptors, which matters for the dev-mode loop that
     * rebuilds the loader on every regeneration cycle.
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
     * Build the project-aware classloader the reflection path uses to resolve consumer
     * service / record / condition / jOOQ-catalog classes. URLs are every entry in
     * {@code project.getCompileClasspathElements()} (the consumer's declared compile dep graph
     * plus its own {@code target/classes}) and every reactor sibling's {@code target/classes}
     * (already collected by {@link #resolveClasspathRoots()} for the LSP catalog scan). The
     * parent is the plugin's own loader so the generator's classes still resolve and any
     * consumer-side override under {@code <plugin><dependencies>} still wins through the parent
     * chain.
     */
    private URLClassLoader buildCodegenLoader() throws MojoExecutionException {
        var urls = new LinkedHashSet<URL>();
        try {
            for (String element : project.getCompileClasspathElements()) {
                urls.add(Path.of(element).toUri().toURL());
            }
        } catch (DependencyResolutionRequiredException | MalformedURLException e) {
            throw new MojoExecutionException(
                "Failed to assemble the project compile classpath for codegen reflection.", e);
        }
        for (Path root : resolveClasspathRoots()) {
            try {
                urls.add(root.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new MojoExecutionException(
                    "Failed to add reactor sibling output directory " + root + " to codegen classpath.", e);
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
    }

    private static Map<String, String> toNamedReferenceMap(List<NamedReferenceBinding> refs) {
        if (refs == null) return Map.of();
        return refs.stream().collect(Collectors.toUnmodifiableMap(r -> r.name, r -> r.className));
    }
}
