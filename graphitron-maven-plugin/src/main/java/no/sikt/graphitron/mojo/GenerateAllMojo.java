package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generate.GraphQLGenerator;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.mojo.GenerateAllExecutor.DEFAULT_SCHEMA_FILENAME;
import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;

/**
 * Combined Mojo that performs schema transformation and code generation in a single execution.
 */
@Mojo(name = "generate-all", defaultPhase = GENERATE_SOURCES)
public class GenerateAllMojo extends AbstractGenerateMojo {
    private static final String TRANSFORM_TARGET_PATH = "target/generated-resources/graphql_transformer/";

    @Parameter(required = true)
    private TransformConfiguration transform;

    @Override
    public Set<String> getUserSchemaFiles() {
        if (userSchemaFiles == null || userSchemaFiles.isEmpty()) {
            return Set.of(DEFAULT_SCHEMA_FILENAME);
        }
        return userSchemaFiles;
    }

    @Override
    public void execute() throws MojoExecutionException {
        validateConfiguration();

        var transformOutputDir = project.getBasedir().toPath().resolve(TRANSFORM_TARGET_PATH);

        try {
            // 1. Execute schema transformation
            var executor = new GenerateAllExecutor(transform);
            var result = executor.execute(transformOutputDir);

            // 2. Add transform output as resource
            var resource = new Resource();
            resource.setDirectory(transformOutputDir.toString());
            project.addResource(resource);

            // 3. Configure and run code generation
            this.schemaFiles = Set.of(result.generatorSchemaPath());
            GeneratorConfig.loadProperties(this);
            initializeScalars();
            GraphQLGenerator.generate();

            // 4. Add generated sources
            project.addCompileSourceRoot(getOutputPath());

        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void validateConfiguration() throws MojoExecutionException {
        if (transform == null) {
            throw new MojoExecutionException("Transform configuration is required. " +
                    "Please add a <transform> section to your configuration.");
        }
        if (transform.getSchemaRootDirectories() == null || transform.getSchemaRootDirectories().isEmpty()) {
            throw new MojoExecutionException("schemaRootDirectories is required in transform configuration.");
        }
    }

    private void initializeScalars() {
        Set<Class<?>> userConfiguredScalarClasses = scalars == null ? Set.of() : scalars.stream()
                .map(ExternalMojoClassReference::classReference)
                .collect(Collectors.toSet());
        ScalarUtils.initialize(userConfiguredScalarClasses);
    }
}
