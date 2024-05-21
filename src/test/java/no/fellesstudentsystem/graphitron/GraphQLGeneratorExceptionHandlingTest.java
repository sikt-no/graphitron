package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.DataAccessExceptionToErrorMappingProviderGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GraphQLGeneratorExceptionHandlingTest extends TestCommon {

    public static final String SRC_TEST_RESOURCES_PATH = "exception";

    public GraphQLGeneratorExceptionHandlingTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected Map<String, List<String>> generateFiles(String schemaParentFolder) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new MutationExceptionStrategyConfigurationGenerator(processedSchema),
                new DataAccessExceptionToErrorMappingProviderGenerator(processedSchema));
        return generateFiles(generators);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                List.of(),
                List.of(),
                List.of()
        );
    }

    @Test
    void generate_mutation_shouldGenerateExceptionStrategySupportingConstraintValidationViolationExceptions() throws IOException {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "MyValidationError"));
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("validationException");
    }

    @Test
    void generate_mutation_shouldGenerateExceptionStrategySupportingDataAccessExceptions() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("dataAccessException");
    }

    @Test
    void generate_mutation_shouldGenerateExceptionStrategySupportingDataAccessExceptionsAndConstraintValidationViolationExceptions() throws IOException {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "MyValidationError"));
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("dataAccessException", "validationAndDataAccessException");
    }
}
