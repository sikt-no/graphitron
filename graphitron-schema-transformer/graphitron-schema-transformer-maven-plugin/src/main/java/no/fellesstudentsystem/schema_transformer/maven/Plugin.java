package no.fellesstudentsystem.schema_transformer.maven;

import no.fellesstudentsystem.schema_transformer.FeatureSchema;
import no.fellesstudentsystem.schema_transformer.OutputSchema;
import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import no.fellesstudentsystem.schema_transformer.TransformConfig;
import no.fellesstudentsystem.schema_transformer.schema.SchemaReader;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.schema_transformer.SchemaTransformer.*;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.writeSchemaToDirectory;
import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_RESOURCES;

@Mojo(name = "generate", defaultPhase = GENERATE_RESOURCES)
public class Plugin extends AbstractMojo {
    private static final String TARGET_PATH = "target/generated-resources/graphql_transformer/";

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "generate.schemaRootDirectories", required = true)
    private Set<String> schemaRootDirectories;

    @Parameter(property = "generate.descriptionSuffixFilename", defaultValue = "description-suffix.md")
    private String descriptionSuffixFilename;

    @Parameter(property = "generate.removeGeneratorDirectives", defaultValue = "true")
    private boolean removeGeneratorDirectives;

    @Parameter(property = "generate.makeApolloFederation", defaultValue = "false")
    private boolean makeApolloFederation;

    @Parameter(property = "generate.expandConnections", defaultValue = "true")
    private boolean expandConnections;

    @Parameter(property = "generate.addFeatureFlags", defaultValue = "false")
    private boolean addFeatureFlags;

    @Parameter(property = "generate.outputSchema")
    private String outputSchema;

    @Parameter(property = "generate.outputSchemas")
    private Set<OutputSchema> outputSchemas;

    @Parameter(property = "generate.directivesToRemove")
    private Set<String> directivesToRemove;

    @Override
    public void execute() throws MojoExecutionException {
        var actualTarget = project.getBasedir().toPath().resolve(TARGET_PATH);
        var schemaFiles = SchemaReader.findSchemaFilesRecursivelyInDirectory(schemaRootDirectories);
        var descriptionSuffixForFeatures = SchemaReader.createDescriptionSuffixForFeatureMap(schemaRootDirectories, descriptionSuffixFilename);
        var outputDirectory = actualTarget.toString();
        var config = new TransformConfig(schemaFiles, directivesToRemove, descriptionSuffixForFeatures, makeApolloFederation, addFeatureFlags, removeGeneratorDirectives, expandConnections);
        var transformer = new SchemaTransformer(config);
        var newSchema = transformer.transformSchema();
        var features = outputSchemas != null && !outputSchemas.isEmpty() ? splitFeatures(reloadSchema(newSchema), outputSchemas) : List.<FeatureSchema>of();
        try {
            Files.createDirectories(actualTarget);
            if (outputSchema != null && !outputSchema.isEmpty()) {
                writeSchemaToDirectory(newSchema, outputSchema, outputDirectory);
            }
            for (var f : features) {
                writeSchemaToDirectory(f.schema(), f.fileName(), outputDirectory);
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        var resource = new Resource();
        resource.setDirectory(actualTarget.toString());
        project.addResource(resource);
    }
}
