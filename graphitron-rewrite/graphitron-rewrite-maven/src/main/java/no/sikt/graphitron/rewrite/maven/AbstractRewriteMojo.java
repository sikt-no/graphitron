package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.schema.ScalarMapping;
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

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    @Parameter
    List<SchemaInputBinding> schemaInputs;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/graphitron")
    String outputDirectory;

    @Parameter(required = true)
    String outputPackage;

    @Parameter(required = true)
    String jooqPackage;

    @Parameter
    List<NamedReferenceBinding> namedReferences;

    @Parameter
    List<ScalarBinding> scalars;

    @Parameter(defaultValue = "1000")
    int maxAllowedPageSize;

    protected RewriteContext buildContext() throws MojoExecutionException {
        var basedir = project.getBasedir().toPath();
        return new RewriteContext(
            SchemaInputExpander.expand(schemaInputs, basedir),
            basedir,
            Path.of(outputDirectory),
            outputPackage,
            jooqPackage,
            toNamedReferenceMap(namedReferences),
            toScalarMappings(scalars),
            maxAllowedPageSize
        );
    }

    private static Map<String, String> toNamedReferenceMap(List<NamedReferenceBinding> refs) {
        if (refs == null) return Map.of();
        return refs.stream().collect(Collectors.toUnmodifiableMap(r -> r.name, r -> r.className));
    }

    private static List<ScalarMapping> toScalarMappings(List<ScalarBinding> bindings) {
        if (bindings == null) return List.of();
        return bindings.stream().map(ScalarBinding::toScalarMapping).toList();
    }
}
