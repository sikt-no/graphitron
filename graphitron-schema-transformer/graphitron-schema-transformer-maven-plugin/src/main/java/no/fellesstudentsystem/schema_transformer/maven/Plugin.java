package no.fellesstudentsystem.schema_transformer.maven;

import no.fellesstudentsystem.schema_transformer.SchemaConfig;
import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_RESOURCES;

@Mojo(name = "generate", defaultPhase = GENERATE_RESOURCES)
public class Plugin extends AbstractMojo {
    private static final String TARGET_PATH = "target/generated-resources/graphql_transformer/";

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Parameter(required = true, readonly = false)
    private Set<String> schemaRootDirectories;

    @Parameter(required = true, readonly = false)
    private String descriptionSuffixFilename;

    @Parameter(property = "generate.makeApolloFederation", defaultValue = "false")
    private boolean makeApolloFederation;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            var schemaFiles = SchemaConfig.findSchemaFilesRecursivelyInDirectory(schemaRootDirectories);
            var descriptionSuffixForFeatures = SchemaConfig.createDescriptionSuffixForFeatureMap(schemaRootDirectories, descriptionSuffixFilename);

            var actualTarget = project.getBasedir().toPath().resolve(TARGET_PATH);
            Files.createDirectories(actualTarget);
            SchemaTransformer.transformSchema(schemaFiles, descriptionSuffixForFeatures, actualTarget.toString(), makeApolloFederation);

            var resource = new Resource();
            resource.setDirectory(actualTarget.toString());
            project.addResource(resource);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}
