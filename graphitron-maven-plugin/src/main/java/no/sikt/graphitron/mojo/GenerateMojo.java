package no.sikt.graphitron.mojo;

import com.kobylynskyi.graphql.codegen.java.JavaGraphQLCodegen;
import com.kobylynskyi.graphql.codegen.model.MappingConfig;
import com.kobylynskyi.graphql.codegen.model.RelayConfig;
import graphql.parser.ParserOptions;
import no.sikt.graphitron.configuration.Extension;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generate.Generator;
import no.sikt.graphitron.generate.GraphQLGenerator;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.kobylynskyi.graphql.codegen.model.MappingConfigDefaultValuesInitializer.initDefaultValues;
import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;

/**
 * Mojo for a single run of the code generation.
 */
@Mojo(name = "generate", defaultPhase = GENERATE_SOURCES)
public class GenerateMojo extends AbstractMojo implements Generator {
    /**
     * The Maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    /**
     * The location where the code should be exported to.
     */
    @Parameter(property = "generate.outputPath", defaultValue = "${project.build.directory}/generated-sources")
    private String outputPath;

    /**
     * The package where the code should be exported to.
     */
    @Parameter(property = "generate.outputPackage", defaultValue = "no.sikt.graphql")
    private String outputPackage;

    /**
     * The comma-separated locations of the schema files to use for code generation.
     */
    @Parameter(property = "generate.schemaFiles", defaultValue = "${project.basedir}/target/generated-resources/schema.graphql", required = true)
    private Set<String> schemaFiles;

    /**
     * The comma-separated locations of the schema files to provide to the user.
     */
    @Parameter(property = "generate.userSchemaFiles")
    private Set<String> userSchemaFiles;

    /**
     * The output folder for jOOQ generated code.
     */
    @Parameter(property = "generate.jooqGeneratedPackage")
    private String jooqGeneratedPackage;

    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "generate.externalReferences")
    @SuppressWarnings("unused")
    private List<ExternalMojoClassReference> externalReferences;

    /**
     * Extra scalars that can be used in code generation. In addition to the default scalars provided by the graphql
     * Java and <a href="https://github.com/graphql-java/graphql-java-extended-scalars"> Extended Scalars</a> libraries
     */
    @Parameter(property = "generate.scalars")
    @SuppressWarnings("unused")
    private List<ExternalMojoClassReference> scalars;

    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "generate.externalReferenceImports")
    @SuppressWarnings("unused")
    private Set<String> externalReferenceImports;

    /**
     * Transforms that apply to all records, or a subset of records.
     */
    @Parameter(property = "generate.globalRecordTransforms")
    @SuppressWarnings("unused")
    private List<GlobalTransform> globalRecordTransforms;

    @Parameter(property = "generate.extensions")
    @SuppressWarnings("unused")
    private List<Extension> extensions;

    @Parameter(property = "generate.recordValidation")
    @SuppressWarnings("unused")
    private RecordValidation recordValidation;

    @Parameter(property = "generate.maxAllowedPageSize", defaultValue = "1000")
    @SuppressWarnings("unused")
    private int maxAllowedPageSize;

    @Parameter(property = "generate.makeKickstart", defaultValue = "false")
    @SuppressWarnings("unused")
    private boolean makeKickstart;

    @Parameter(property = "generate.makeNodeStrategy", defaultValue = "false")
    @SuppressWarnings("unused")
    private boolean makeNodeStrategy;

    @Override
    public void execute() throws MojoExecutionException {
        GeneratorConfig.loadProperties(this);

        if (makeKickstart) {
            try {
                graphqlCodeGen();
            } catch (IOException e) {
                throw new MojoExecutionException(e);
            }
        } else {
            getGraphqlCodegenCustomTypeMapping();
        }

        GraphQLGenerator.generate();
        project.addCompileSourceRoot(getOutputPath());
    }

    private void graphqlCodeGen() throws IOException {
        MappingConfig mappingConfig = getGraphqlCodeGenConfig();

        ParserOptions.Builder parserOptionBuilder = ParserOptions.newParserOptions()
                .maxTokens(Integer.MAX_VALUE)
                .maxCharacters(Integer.MAX_VALUE)
                .maxWhitespaceTokens(Integer.MAX_VALUE)
                .maxRuleDepth(Integer.MAX_VALUE);
        ParserOptions.setDefaultParserOptions(parserOptionBuilder.build());

        new JavaGraphQLCodegen(getSchemaFiles().stream().toList(), null,
                new File(GeneratorConfig.outputDirectory()), mappingConfig, null).generate();
    }

    /**
     * @return Custom config for graphql-codegen
     */
    private MappingConfig getGraphqlCodeGenConfig() {
        var config = new MappingConfig();
        initDefaultValues(config);

        var customTypesMapping = getGraphqlCodegenCustomTypeMapping();
        config.setCustomTypesMapping(customTypesMapping);

        config.setApiPackageName(getApiPackageName());
        config.setModelPackageName(getModelPackageName());
        config.setModelValidationAnnotation("jakarta.validation.constraints.NotNull");
        config.setGenerateEqualsAndHashCode(true);

        // This sets the relay config to look for a non-existent directive to disable the relay handling.
        var relayConfig = new RelayConfig();
        relayConfig.setDirectiveName("NONE");
        config.setRelayConfig(relayConfig);
        config.setFieldsWithResolvers(Set.of("@splitQuery"));
        config.setApiReturnType("java.util.concurrent.CompletableFuture");
        config.setGenerateDataFetchingEnvironmentArgumentInApis(true);
        config.setResolverParentInterface("graphql.kickstart.tools.GraphQLResolver<{{TYPE}}>");
        config.setQueryResolverParentInterface("graphql.kickstart.tools.GraphQLQueryResolver");
        config.setMutationResolverParentInterface("graphql.kickstart.tools.GraphQLMutationResolver");
        config.setSubscriptionResolverParentInterface("graphql.kickstart.tools.GraphQLSubscriptionResolver");

        return config;
    }

    private Map<String, String> getGraphqlCodegenCustomTypeMapping() {
        Set<Class<?>> userConfiguredScalarClasses = scalars.stream()
                .map(ExternalMojoClassReference::classReference)
                .collect(Collectors.toSet());
        return ScalarUtils.initialize(userConfiguredScalarClasses).getScalarTypeNameMapping();
    }

    @Override
    public String getOutputPath() {
        return outputPath;
    }

    @Override
    public String getOutputPackage() {
        return outputPackage;
    }

    @Override
    public String getApiPackageName() {
        return outputPackage + ".api";
    }

    @Override
    public String getModelPackageName() {
        return outputPackage + ".model";
    }

    @Override
    public Set<String> getSchemaFiles() {
        return schemaFiles;
    }

    @Override
    public Set<String> getUserSchemaFiles() {
        if (userSchemaFiles == null || userSchemaFiles.isEmpty()) {
            return schemaFiles;
        }
        return userSchemaFiles;
    }

    @Override
    public String getJooqGeneratedPackage() {
        return jooqGeneratedPackage;
    }

    @Override
    public RecordValidation getRecordValidation() {
        return recordValidation;
    }

    @Override
    public int getMaxAllowedPageSize() {
        return maxAllowedPageSize;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public void setOutputPackage(String outputPackage) {
        this.outputPackage = outputPackage;
    }

    public void setSchemaFiles(Set<String> schemaFiles) {
        this.schemaFiles = schemaFiles;
    }

    @Override
    public List<? extends ExternalReference> getExternalReferences() {
        return externalReferences;
    }

    @Override
    public List<GlobalTransform> getGlobalTransforms() {
        return globalRecordTransforms;
    }

    @Override
    public List<Extension> getExtensions() {
        return extensions;
    }

    @Override
    public boolean makeKickstart() {
        return makeKickstart;
    }

    @Override
    public boolean makeNodeStrategy() {
        return makeNodeStrategy;
    }

    public void setJooqGeneratedPackage(String jooqGeneratedPackage) {
        this.jooqGeneratedPackage = jooqGeneratedPackage;
    }

    @Override
    public Set<String> getExternalReferenceImports() {
        return externalReferenceImports;
    }
}
