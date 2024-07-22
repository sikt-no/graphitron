package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.update.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.dummygenerators.ReducedRecordMapperClassGenerator;
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
                new UpdateDBClassGenerator(schema),
                new MutationExceptionStrategyConfigurationGenerator(schema),
                new ReducedRecordMapperClassGenerator(schema) // Temporary solution until validation tests are moved.
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
    void generate_mutation_shouldGenerateResolversAndQueriesWithSimpleInputsAndResponses() {
        assertGeneratedContentMatches("simpleResolverWithMutationType");
    }

    @Test
    void generate_mutationWithListFields_shouldGenerateResolversForLists() {
        assertGeneratedContentMatches("listResolvers");
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithValidationErrorHandling() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentMatches("validationErrorHandling");
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
    void generate_whenHasSetUpdateType_shouldGenerateUpdateQueriesAndResolvers() {
        assertGeneratedContentMatches("updateQueries");
    }

    @Test
    void generate_whenHasSetUpdateType_shouldGenerateIterableUpdateQueriesAndResolvers() {
        assertGeneratedContentMatches("updateIterableQueries");
    }

    @Test
    void generate_whenHasSetInsertType_shouldGenerateInsertQueriesAndResolvers() {
        assertGeneratedContentMatches("insertQueries");
    }

    @Test
    void generate_whenHasSetInsertType_shouldGenerateIterableInsertQueriesAndResolvers() {
        assertGeneratedContentMatches("insertIterableQueries");
    }

    @Test
    void generate_whenHasSetUpsertType_shouldGenerateUpsertQueriesAndResolvers() {
        assertGeneratedContentMatches("upsertQueries");
    }

    @Test
    void generate_whenHasSetUpsertType_shouldGenerateIterableUpsertQueriesAndResolvers() {
        assertGeneratedContentMatches("upsertIterableQueries");
    }

    @Test
    void generate_whenHasSetDeleteType_shouldGenerateDeleteQueriesAndResolvers() {
        assertGeneratedContentMatches("deleteQueries");
    }

    @Test
    void generate_whenHasSetDeleteType_shouldGenerateIterableDeleteQueriesAndResolvers() {
        assertGeneratedContentMatches("deleteIterableQueries");
    }

    @Test
    void generate_whenHasSetMutationType_shouldGenerateNestedQueriesAndResolvers() {
        assertGeneratedContentMatches("mutationWithNestedResponse");
    }

    @Test
    void generate_whenHasSetMutationTypeWithMismatchedIterability_shouldGenerateAdjustedNestedQueriesAndResolvers() {
        assertGeneratedContentMatches("mismatchedIterabilityQueries");
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

    @Test
    void generate_whenMutationTypeHasNoID_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/mutationTypeWithoutID"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not find a suitable ID to return for 'editCustomerWithoutID'.");
    }

    @Test
    void generate_whenMutationTypeHasNoRecord_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/mutationTypeWithoutRecord"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Must have at least one table reference when generating resolvers with queries. Mutation 'editCustomer' has no tables attached.");
    }
}
