package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.update.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron.TestReferenceSet.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorMutationTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "mutation";

    public GraphQLGeneratorMutationTest() {
        super(
                SRC_TEST_RESOURCES_PATH,
                List.of(
                        ENUM_RATING.get(),
                        SERVICE_CUSTOMER.get(),
                        RECORD_CUSTOMER.get(),
                        RECORD_CUSTOMER_RESPONSE_1.get(),
                        RECORD_CUSTOMER_RESPONSE_2.get()
                )
        );
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new UpdateResolverClassGenerator(schema),
                new MutationExceptionStrategyConfigurationGenerator(schema)
        );
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithInputsAndResponseObjects() {
        assertGeneratedContentMatches("inputAndResponseResolvers");
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithSimpleInputsAndResponses() {
        assertGeneratedContentMatches("simpleResolverWithService");
    }

    @Test
    void generate_mutationWithListFields_shouldGenerateResolversForLists() {
        assertGeneratedContentMatches("listResolvers");
    }

    @Test
    void generate_serviceMutation_shouldGenerateResolversWithValidationErrorHandling() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentMatches("serviceValidationErrorHandling");
    }

    @Test
    void generate_serviceMutation_shouldGenerateResolversWithoutErrorHandlingForErrorsMappedByExceptionHandler() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "MyValidationError"));
        assertGeneratedContentMatches("serviceValidationErrorHandlingWhenExceptionsHandledElsewhere");
    }

    @Test
    void generate_whenServiceNotFound_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/serviceNotFound"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Problems have been found that prevent code generation:\n" +
                        "No service with name 'SERVICE_NOT_FOUND' found.");
    }

    @Test
    void generate_whenServiceAndMutationTypeNotSet_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/serviceAndMutationTypeNotSet"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Mutation 'editCustomer' is set to generate, but has neither a service nor mutation type set.");
    }
}
