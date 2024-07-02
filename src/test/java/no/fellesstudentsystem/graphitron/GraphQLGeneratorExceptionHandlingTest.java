package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.ExceptionToErrorMappingProviderGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                new ExceptionToErrorMappingProviderGenerator(processedSchema));
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
    @DisplayName("Test generation of exception strategy supporting 'constraint validation violation' exceptions")
    void testValidationExceptionGeneration() throws IOException {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "MyValidationError"));
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("validationException");
    }

    @Test
    @DisplayName("Test generation of exception strategy supporting 'data access' exceptions")
    void testDataAccessExceptionGeneration() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("dataAccessException");
    }

    @Test
    @DisplayName("Test generation of exception strategy supporting both 'data access' and 'constraint validation violation' exceptions")
    void testDataAccessExceptionAndValidationGeneration() throws IOException {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "MyValidationError"));
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("dataAccessException", "validationAndDataAccessException");
    }

    @Test
    @DisplayName("Test generation of exception strategy supporting 'business logic' exceptions")
    void testBusinessLogicExceptionGeneration() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("businessLogicException");
    }

    @Test
    @DisplayName("Test that 'IllegalArgumentException' is thrown when 'className' directive argument is not defined for error handler of type 'GENERIC'")
    void testMissingClassNameArgument() {
        assertThatThrownBy(() -> generateFiles("error/generalHandlerWithoutClassName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'className´ directive argument must be defined for error handler of type GENERIC");
    }

    @Test
    @DisplayName("Test that 'IllegalArgumentException' is thrown when 'className' points to class not available on classpath")
    void testBadClassNameArgument() {
        assertThatThrownBy(() -> generateFiles("error/unavailableClassName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to find exception className: org.example.exception.ThereIsNoSuchException, declared for mutation: editCustomerWithOtherError");
    }
}