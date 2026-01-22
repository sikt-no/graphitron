package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.OptionalSelect;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generate.Generator;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.validation.ValidationHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.util.List;
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
