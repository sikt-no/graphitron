package no.sikt.graphitron.mojo;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_RESOURCES;

/**
 * Mojo for standalone schema transformation.
 */
@Mojo(name = "transform", defaultPhase = GENERATE_RESOURCES)
public class TransformMojo extends AbstractMojo {
    private static final String TARGET_PATH = "target/generated-resources/graphql_transformer/";

    /**
     * The Maven project this plugin is being used in.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    /**
     * Transform configuration settings.
     */
    @Parameter(required = true)
    private TransformPluginConfiguration transform;

    @Override
    public void execute() throws MojoExecutionException {
        validateConfiguration();

        var outputDirectory = project.getBasedir().toPath().resolve(TARGET_PATH);

        try {
            var runner = new SchemaTransformRunner(transform);
            runner.execute(outputDirectory, false);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        var resource = new Resource();
        resource.setDirectory(outputDirectory.toString());
        project.addResource(resource);
    }

    private void validateConfiguration() throws MojoExecutionException {
        if (transform == null) {
            throw new MojoExecutionException("Transform configuration is required. " +
                    "Please add a <transform> section to your configuration.");
        }
        if (transform.getSchemaRootDirectories() == null || transform.getSchemaRootDirectories().isEmpty()) {
            throw new MojoExecutionException("schemaRootDirectories is required in transform configuration.");
        }
        var hasOutputSchema = transform.getOutputSchema() != null && !transform.getOutputSchema().isEmpty();
        var hasOutputSchemas = transform.getOutputSchemas() != null && !transform.getOutputSchemas().isEmpty();
        if (!hasOutputSchema && !hasOutputSchemas) {
            throw new MojoExecutionException("Either outputSchema or outputSchemas must be specified in transform configuration.");
        }
    }
}
