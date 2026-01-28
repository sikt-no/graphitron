package no.sikt.graphitron.mojo;

import no.fellesstudentsystem.schema_transformer.FeatureSchema;
import no.fellesstudentsystem.schema_transformer.OutputSchema;
import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import no.fellesstudentsystem.schema_transformer.TransformConfig;
import no.fellesstudentsystem.schema_transformer.schema.SchemaReader;
import no.fellesstudentsystem.schema_transformer.schema.SchemaWriter;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_RESOURCES;

@Mojo(name = "transform", defaultPhase = GENERATE_RESOURCES)
public class TransformMojo extends AbstractMojo {
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
    @Parameter(property = "transform.descriptionSuffixFilename", defaultValue = "description-suffix.md")
    private String descriptionSuffixFilename;

    /**
     * Whether to remove generator directives from the output schema. Generator directives are
     * implementation details needed for Graphitron code generation but not needed in the runtime schema.
     */
    @Parameter(property = "transform.removeGeneratorDirectives", defaultValue = "true")
    private boolean removeGeneratorDirectives;

    /**
     * Whether to remove all Apollo federation directives and types from the schema.
     * E.g. when hosting a subgraph in a non-federated environment.
     */
    @Parameter(property = "transform.removeFederationDefinitions", defaultValue = "false")
    private boolean removeFederationDefinitions;

    /**
     * Whether to expand GraphQL connection types into full GraphQL Cursor Connections Specification-compliant structures.
     */
    @Parameter(property = "transform.expandConnections", defaultValue = "true")
    private boolean expandConnections;

    /**
     * Whether to add feature flags to the schema based on directory structure.
     */
    @Parameter(property = "transform.addFeatureFlags", defaultValue = "false")
    private boolean addFeatureFlags;

    /**
     * The name of the output schema file. Used when generating a single output schema.
     */
    @Parameter(property = "transform.outputSchema")
    private String outputSchema;

    /**
     * Configuration for multiple output schemas with different feature flags.
     * Each output schema can have its own set of feature flags and filename.
     */
    @Parameter(property = "transform.outputSchemas")
    private Set<OutputSchema> outputSchemas;

    /**
     * Set of directive names to remove from the output schema.
     */
    @Parameter(property = "transform.directivesToRemove")
    private Set<String> directivesToRemove;

    /**
     * Whether to add the Apollo Federation `@key` directive to types implementing the Node interface
     */
    @Parameter(property = "transform.addKeyDirectiveToNodes", defaultValue = "false")
    private boolean addKeyDirectiveToNodes;

    @Override
    public void execute() throws MojoExecutionException {
        var actualTarget = project.getBasedir().toPath().resolve(TARGET_PATH);
        var schemaFiles = new ArrayList<>(SchemaReader.findSchemaFilesRecursivelyInDirectory(schemaRootDirectories));
        schemaFiles.add("/directives.graphqls"); // Auto-include built-in directives from classpath
        var descriptionSuffixForFeatures = SchemaReader.createDescriptionSuffixForFeatureMap(schemaRootDirectories, descriptionSuffixFilename);
        var outputDirectory = actualTarget.toString();
        var config = new TransformConfig(schemaFiles, directivesToRemove, descriptionSuffixForFeatures, addFeatureFlags, removeGeneratorDirectives, expandConnections, addKeyDirectiveToNodes);
        var transformer = new SchemaTransformer(config);
        var newSchema = transformer.transformSchema();
        var features = outputSchemas != null && !outputSchemas.isEmpty()
                       ? SchemaTransformer.splitFeatures(SchemaTransformer.reloadSchema(newSchema, removeFederationDefinitions), outputSchemas)
                       : List.<FeatureSchema>of();
        try {
            Files.createDirectories(actualTarget);
            if (outputSchema != null && !outputSchema.isEmpty()) {
                SchemaWriter.writeSchemaToDirectory(newSchema, outputSchema, outputDirectory, removeFederationDefinitions);
            }
            for (var f : features) {
                SchemaWriter.writeSchemaToDirectory(f.schema(), f.fileName(), outputDirectory, removeFederationDefinitions);
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        var resource = new Resource();
        resource.setDirectory(actualTarget.toString());
        project.addResource(resource);
    }
}
