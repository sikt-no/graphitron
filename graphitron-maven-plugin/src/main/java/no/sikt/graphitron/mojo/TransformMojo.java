package no.sikt.graphitron.mojo;

import no.fellesstudentsystem.schema_transformer.FeatureSchema;
import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import no.fellesstudentsystem.schema_transformer.TransformConfig;
import no.fellesstudentsystem.schema_transformer.schema.SchemaWriter;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_RESOURCES;

/**
 * Mojo for standalone schema transformation without code generation.
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
    private TransformConfiguration transform;

    @Override
    public void execute() throws MojoExecutionException {
        validateConfiguration();

        var actualTarget = project.getBasedir().toPath().resolve(TARGET_PATH);
        var schemaFiles = SchemaTransformHelper.collectSchemaFiles(transform.getSchemaRootDirectories());
        var descriptionSuffixForFeatures = SchemaTransformHelper.createDescriptionSuffixMap(
                transform.getSchemaRootDirectories(), transform.getDescriptionSuffixFilename());
        var outputDirectory = actualTarget.toString();

        var config = new TransformConfig(
                schemaFiles,
                transform.getDirectivesToRemove(),
                descriptionSuffixForFeatures,
                transform.isAddFeatureFlags(),
                transform.isRemoveGeneratorDirectives(),
                transform.isExpandConnections());

        var transformer = new SchemaTransformer(config);
        var newSchema = transformer.transformSchema();

        var outputSchemas = transform.getOutputSchemas();
        var features = outputSchemas != null && !outputSchemas.isEmpty()
                ? SchemaTransformer.splitFeatures(
                        SchemaTransformer.reloadSchema(newSchema, transform.isRemoveFederationDefinitions()),
                        outputSchemas)
                : List.<FeatureSchema>of();

        try {
            Files.createDirectories(actualTarget);
            var outputSchema = transform.getOutputSchema();
            if (outputSchema != null && !outputSchema.isEmpty()) {
                SchemaWriter.writeSchemaToDirectory(
                        newSchema, outputSchema, outputDirectory, transform.isRemoveFederationDefinitions());
            }
            for (var f : features) {
                SchemaWriter.writeSchemaToDirectory(
                        f.schema(), f.fileName(), outputDirectory, transform.isRemoveFederationDefinitions());
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        var resource = new Resource();
        resource.setDirectory(actualTarget.toString());
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
