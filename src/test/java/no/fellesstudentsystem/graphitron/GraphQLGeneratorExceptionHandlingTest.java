package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.ExceptionToErrorMappingProviderGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorExceptionHandlingTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "exception";

    public GraphQLGeneratorExceptionHandlingTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new MutationExceptionStrategyConfigurationGenerator(schema),
                new ExceptionToErrorMappingProviderGenerator(schema)
        );
    }

    @Test
    @DisplayName("Test generation of exception strategy supporting 'constraint validation violation' exceptions")
    void testValidationExceptionGeneration() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "MyValidationError"));
        assertGeneratedContentMatches("validationException");
    }

    @Test
    @DisplayName("Test generation of exception strategy supporting 'data access' exceptions")
    void testDataAccessExceptionGeneration() {
        assertGeneratedContentMatches("dataAccessException");
    }

    @Test
    @DisplayName("Test generation of exception strategy supporting both 'data access' and 'constraint validation violation' exceptions")
    void testDataAccessExceptionAndValidationGeneration() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "MyValidationError"));
        assertGeneratedContentMatches("dataAccessException", "validationAndDataAccessException");
    }

    @Test
    @DisplayName("Test generation of exception strategy supporting 'business logic' exceptions")
    void testBusinessLogicExceptionGeneration() {
        assertGeneratedContentMatches("businessLogicException");
    }

    @Test
    @DisplayName("Test that 'IllegalArgumentException' is thrown when 'className' points to class not available on classpath")
    void testBadClassNameArgument() {
        assertThatThrownBy(() -> generateFiles("error/unavailableClassName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to find exception className: org.example.exception.ThereIsNoSuchException, declared for mutation: mutation");
    }
}