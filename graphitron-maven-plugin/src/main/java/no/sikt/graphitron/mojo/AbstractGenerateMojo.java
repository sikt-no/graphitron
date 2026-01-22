package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.configuration.OptionalSelect;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.generate.Generator;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;
import java.util.Set;

/**
 * Abstract base class for Graphitron mojos that generate code.
 * Extends {@link AbstractGraphitronMojo} with code generation specific parameters.
 */
public abstract class AbstractGenerateMojo extends AbstractGraphitronMojo implements Generator {

    /**
     * The directory where generated code should be written.
     */
    @Parameter(property = "graphitron.outputPath", defaultValue = "${project.build.directory}/generated-sources")
    protected String outputPath;
    /**
     * The package where the code should be exported to.
     */
    @Parameter(property = "graphitron.outputPackage", defaultValue = "no.sikt.graphql")
    protected String outputPackage;
    /**
     * The comma-separated locations of the schema files to provide to the user.
     */
    @Parameter(property = "graphitron.userSchemaFiles")
    protected Set<String> userSchemaFiles;
    /**
     * Extra scalars that can be used in code generation. In addition to the default scalars provided by the graphql
     * Java and <a href="https://github.com/graphql-java/graphql-java-extended-scalars"> Extended Scalars</a> libraries
     */
    @Parameter(property = "graphitron.scalars")
    @SuppressWarnings("unused")
    protected List<ExternalMojoClassReference> scalars;
    /**
     * Transforms that apply to all records, or a subset of records.
     */
    @Parameter(property = "graphitron.globalRecordTransforms")
    @SuppressWarnings("unused")
    protected List<GlobalTransform> globalRecordTransforms;
    @Parameter(property = "graphitron.recordValidation")
    @SuppressWarnings("unused")
    protected RecordValidation recordValidation;
    @Parameter(property = "graphitron.maxAllowedPageSize", defaultValue = "1000")
    @SuppressWarnings("unused")
    protected int maxAllowedPageSize;
    @Parameter(property = "graphitron.useJdbcBatchingForDeletes", defaultValue = "true")
    @SuppressWarnings("unused")
    protected boolean useJdbcBatchingForDeletes;
    @Parameter(property = "graphitron.useJdbcBatchingForInserts", defaultValue = "true")
    @SuppressWarnings("unused")
    protected boolean useJdbcBatchingForInserts;
    @Parameter(property = "graphitron.codeGenerationThresholds")
    @SuppressWarnings("unused")
    protected CodeGenerationThresholds codeGenerationThresholds;
    @Parameter(property = "generate.optionalSelect")
    @SuppressWarnings("unused")
    protected OptionalSelect optionalSelect;
    @Parameter(property = "generate.validateOverlappingInputFields", defaultValue = "true")
    @SuppressWarnings("unused")
    protected boolean validateOverlappingInputFields;

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

    @Override
    public List<GlobalTransform> getGlobalTransforms() {
        return globalRecordTransforms;
    }

    @Override
    public boolean useJdbcBatchingForDeletes() {
        return useJdbcBatchingForDeletes;
    }

    @Override
    public boolean useJdbcBatchingForInserts() {
        return useJdbcBatchingForInserts;
    }

    @Override
    public CodeGenerationThresholds getCodeGenerationThresholds() {
        return codeGenerationThresholds;
    }

    @Override
    public OptionalSelect getOptionalSelect() {
        return optionalSelect;
    }

    @Override
    public boolean validateOverlappingInputFields() {
        return validateOverlappingInputFields;
    }
}
