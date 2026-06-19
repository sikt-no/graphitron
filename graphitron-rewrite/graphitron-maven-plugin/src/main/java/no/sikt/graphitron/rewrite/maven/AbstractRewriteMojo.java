package no.sikt.graphitron.rewrite.maven;

import graphql.schema.idl.errors.SchemaProblem;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationFailedException;
import no.sikt.graphitron.rewrite.maven.watch.WatchErrorFormatter;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
            resolveCompileSourceRoots()
        );
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
     * Compile-output directories from every reactor project. The LSP catalog
     * scans each one for service / condition / record class candidates, so a
     * consumer running {@code mvn graphitron:dev} from one module sees
     * services declared in sibling modules of the same reactor.
     *
     * <p>Falls back to the current project's own output directory when the
     * Maven session is unavailable (e.g. unit-tier callers that bypass the
     * full lifecycle); the result there is identical to pre-multi-module
     * behaviour. External jars on the compile classpath ({@code ~/.m2}) are
     * intentionally not scanned: services live in reactor source code, not
     * third-party libraries.
     */
    private List<Path> resolveClasspathRoots() {
        var roots = new LinkedHashSet<Path>();
        Iterable<MavenProject> projects = session != null && session.getAllProjects() != null
            ? session.getAllProjects()
            : List.of(project);
        for (MavenProject p : projects) {
            String dir = p.getBuild() == null ? null : p.getBuild().getOutputDirectory();
            if (dir == null) continue;
            Path path = Path.of(dir).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                roots.add(path);
            }
        }
        return new ArrayList<>(roots);
    }

    /**
     * Compile source-root directories from every reactor project: the
     * hand-written {@code src/main/java} roots plus any generated-sources roots
     * a prior plugin registered (jOOQ output among them). The LSP catalog
     * parses these to recover Java declaration positions and Javadoc for
     * goto-definition / hover, and to refine jOOQ table / column positions from
     * file-level to per-line.
     *
     * <p>Mirrors {@link #resolveClasspathRoots()}: scoped to reactor source, not
     * third-party jars, and falls back to the current project's own source
     * roots when the Maven session is unavailable (unit-tier callers). Only
     * existing directories are kept, so the pre-{@code mvn compile} state where
     * generated sources are absent contributes nothing and the catalog falls
     * back to file-level / {@code UNKNOWN} positions.
     */
    private List<Path> resolveCompileSourceRoots() {
        var roots = new LinkedHashSet<Path>();
        Iterable<MavenProject> projects = session != null && session.getAllProjects() != null
            ? session.getAllProjects()
            : List.of(project);
        for (MavenProject p : projects) {
            List<String> sourceRoots = p.getCompileSourceRoots();
            if (sourceRoots == null) continue;
            for (String dir : sourceRoots) {
                if (dir == null) continue;
                Path path = Path.of(dir).toAbsolutePath().normalize();
                if (Files.isDirectory(path)) {
                    roots.add(path);
                }
            }
        }
        return new ArrayList<>(roots);
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
