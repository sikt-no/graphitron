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

    /**
     * The Maven project this plugin is being used in.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    /**
     * Directories to search for GraphQL schema files. The plugin will process all schema files found
     * in these directories and their subdirectories.
     */
    @Parameter(property = "generate.schemaRootDirectories", required = true)
    private Set<String> schemaRootDirectories;

    /**
     * Name of the file containing description suffixes to be added to schema elements based on their feature flags.
     * These files are looked for in each feature directory.
     */
    @Parameter(property = "generate.descriptionSuffixFilename", defaultValue = "description-suffix.md")
    private String descriptionSuffixFilename;

    /**
     * Whether to remove generator directives from the output schema. Generator directives are
     * implementation details needed for Graphitron code generation but not needed in the runtime schema.
     */
    @Parameter(property = "generate.removeGeneratorDirectives", defaultValue = "true")
    private boolean removeGeneratorDirectives;

    /**
     * Whether to make the schema compatible with Apollo Federation by adding federation types and directives.
     */
    @Parameter(property = "generate.makeApolloFederation", defaultValue = "false")
    private boolean makeApolloFederation;

    /**
     * Whether to expand GraphQL connection types into full GraphQL Cursor Connections Specification-compliant structures.
     */
    @Parameter(property = "generate.expandConnections", defaultValue = "true")
    private boolean expandConnections;

    /**
     * Whether to add feature flags to the schema based on directory structure.
     */
    @Parameter(property = "generate.addFeatureFlags", defaultValue = "false")
    private boolean addFeatureFlags;

    /**
     * The name of the output schema file. Used when generating a single output schema.
     */
    @Parameter(property = "generate.outputSchema")
    private String outputSchema;

    /**
     * Configuration for multiple output schemas with different feature flags.
     * Each output schema can have its own set of feature flags and filename.
     */
    @Parameter(property = "generate.outputSchemas")
    private Set<OutputSchema> outputSchemas;

    /**
     * Set of directive names to remove from the output schema.
     */
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