package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.validation.ValidationHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;

/**
 * Mojo for a single run of the code generation.
 */
@Mojo(name = "generate", defaultPhase = GENERATE_SOURCES)
public class GenerateMojo extends AbstractGenerateMojo {

    @Override
    public Set<String> getUserSchemaFiles() {
        if (userSchemaFiles == null || userSchemaFiles.isEmpty()) {
            //default to schemaFiles
            return schemaFiles;
        }
        return userSchemaFiles;
    }

    @Override
    public void execute() throws MojoExecutionException {
        try {
            GeneratorConfig.loadProperties(this);
            getGraphqlCodegenCustomTypeMapping();
            GraphQLGenerator.generate();
            project.addCompileSourceRoot(getOutputPath());
        } catch (Exception e) {
            // Log warnings for visibility before throwing
            ValidationHandler.logWarnings();
            throw new MojoExecutionException("\n" + e.getMessage(), e);
        }
    }

    private Map<String, String> getGraphqlCodegenCustomTypeMapping() {
        Set<Class<?>> userConfiguredScalarClasses = scalars.stream()
                .map(ExternalMojoClassReference::classReference)
                .collect(Collectors.toSet());
        return ScalarUtils.initialize(userConfiguredScalarClasses).getScalarTypeNameMapping();
    }
}
