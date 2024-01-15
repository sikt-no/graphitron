package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.ExceptionToErrorMapping;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorExceptionHandlingTest extends TestCommon {

    public static final String SRC_TEST_RESOURCES_PATH = "exception";
    private final List<ExceptionToErrorMapping> exceptionToErrorMappings = List.of(
            new ExceptionToErrorMapping(
                    "editCustomerWithOtherError",
                    "OtherError",
                    "20997", null, null, "This is an error"
            ),
            new ExceptionToErrorMapping(
                    "editCustomerWithOtherError",
                    "OtherError",
                    "20998", null, "bad word detected", null
            ),
            new ExceptionToErrorMapping(
                    "editCustomerWithUnionError",
                    "OtherError",
                    "1337", "22", "data error", "This is an error for the union type"
            )
    );

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
        GeneratorConfig.setExceptionToErrorMappings(exceptionToErrorMappings);
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("validationException", "dataAccessException");
    }

    @Test
    void generate_mutation_shouldGenerateExceptionStrategySupportingDataAccessExceptionsAndConstraintValidationViolationExceptions() throws IOException {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "MyValidationError"));
        GeneratorConfig.setExceptionToErrorMappings(exceptionToErrorMappings);
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("validationException", "validationAndDataAccessException");
    }

    @Test
    void generate_mutation_shouldThrowExceptionWhenUnrecognizedMutationInMapping() {
        var exceptionToErrorMappings = List.of(
                new ExceptionToErrorMapping(
                        "nonExistentMutation",
                        "OtherError",
                        "20997", null, "some specific error", "This is an error"
                ));

        GeneratorConfig.setExceptionToErrorMappings(exceptionToErrorMappings);
        assertThatThrownBy(() -> assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("validationException"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mutation 'nonExistentMutation' defined in exceptionToErrorMappings is not found in the GraphQL schema.");
    }

    @Test
    void generate_mutation_shouldThrowExceptionWhenUnrecognizedErrorTypeInMapping() {
        var exceptionToErrorMappings = List.of(
                new ExceptionToErrorMapping(
                        "editCustomerWithOtherError",
                        "NonExistentErrorType",
                        "20997", null, "some specific error", "This is an error"
                ));

        GeneratorConfig.setExceptionToErrorMappings(exceptionToErrorMappings);
        assertThatThrownBy(() -> assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("validationException"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Mutation 'editCustomerWithOtherError' does not return any errors of type 'NonExistentErrorType' as defined in exceptionToErrorMappings");
    }

}
