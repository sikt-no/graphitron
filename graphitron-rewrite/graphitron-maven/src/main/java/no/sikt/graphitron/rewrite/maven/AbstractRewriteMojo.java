package no.sikt.graphitron.rewrite.maven;

import graphql.schema.idl.errors.SchemaProblem;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared configuration surface for {@link GenerateMojo} and {@link ValidateMojo}.
 */
public abstract class AbstractRewriteMojo extends AbstractMojo {

    /** Sentinel used for validate-only invocations that do not emit code. */
    private static final String VALIDATE_ONLY_PACKAGE = "validation.unused";

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    @Parameter
    List<SchemaInputBinding> schemaInputs;

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
        var basedir = project.getBasedir().toPath();
        var out = Path.of(outputDirectory);
        var outAbs = out.isAbsolute() ? out.normalize() : basedir.resolve(out).normalize();

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

        return new RewriteContext(
            SchemaInputExpander.expand(schemaInputs, basedir),
            basedir,
            outAbs,
            effectiveOutput,
            effectiveJooq,
            toNamedReferenceMap(namedReferences)
        );
    }

    /**
     * Builds the context and invokes the generator through a single
     * error-handling path so every goal surfaces {@link RuntimeException}s
     * wrapped as {@link MojoExecutionException}. Goals that have work to do
     * after a successful generator call add it after this returns.
     */
    protected final void runGenerator(GeneratorCall call) throws MojoExecutionException {
        var ctx = buildContext();
        try {
            call.invoke(new GraphQLRewriteGenerator(ctx));
        } catch (SchemaProblem e) {
            var loaded = ctx.schemaInputs().stream()
                .map(si -> si.sourceName())
                .toList();
            throw new MojoExecutionException(
                SchemaProblemDiagnostic.format(e, loaded, ctx.basedir()), e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private static Map<String, String> toNamedReferenceMap(List<NamedReferenceBinding> refs) {
        if (refs == null) return Map.of();
        return refs.stream().collect(Collectors.toUnmodifiableMap(r -> r.name, r -> r.className));
    }
}
