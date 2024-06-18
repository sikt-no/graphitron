package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalClassReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.JavaRecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.update.UpdateResolverClassGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorMutationTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "mutation";
    private final List<ExternalClassReference> references = List.of(
            new ExternalClassReference("RATING_TEST", "no.fellesstudentsystem.graphitron.enums.RatingTest"),
            new ExternalClassReference("TEST_CUSTOMER", "no.fellesstudentsystem.graphitron.services.TestCustomerService"),
            new ExternalClassReference("TEST_FILM", "no.fellesstudentsystem.graphitron.services.TestFilmService"),
            new ExternalClassReference("EXCEPTION_TEST", "no.fellesstudentsystem.graphitron.exceptions.TestException"),
            new ExternalClassReference("EXCEPTION_TEST_CAUSE", "no.fellesstudentsystem.graphitron.exceptions.TestExceptionCause"),
            new ExternalClassReference("TEST_CUSTOMER_RECORD", "no.fellesstudentsystem.graphitron.records.TestCustomerRecord"),
            new ExternalClassReference("TEST_CUSTOMER_INNER_RECORD", "no.fellesstudentsystem.graphitron.records.TestCustomerInnerRecord"),
            new ExternalClassReference("TEST_CUSTOMER_RESPONSE1", "no.fellesstudentsystem.graphitron.records.EditCustomerResponse1"),
            new ExternalClassReference("TEST_CUSTOMER_RESPONSE2", "no.fellesstudentsystem.graphitron.records.EditCustomerResponse2"),
            new ExternalClassReference("TEST_CUSTOMER_RESPONSE3", "no.fellesstudentsystem.graphitron.records.EditCustomerResponse3"),
            new ExternalClassReference("TEST_CUSTOMER_RESPONSE4", "no.fellesstudentsystem.graphitron.records.EditCustomerResponse4"),
            new ExternalClassReference("TEST_CUSTOMER_ADDRESS_RESPONSE", "no.fellesstudentsystem.graphitron.records.EditCustomerAddressResponse"),
            new ExternalClassReference("TEST_FILM_RECORD", "no.fellesstudentsystem.graphitron.records.TestFilmRecord")
    );

    public GraphQLGeneratorMutationTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected Map<String, List<String>> generateFiles(String schemaParentFolder) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new UpdateResolverClassGenerator(processedSchema),
                new UpdateDBClassGenerator(processedSchema),
                new MutationExceptionStrategyConfigurationGenerator(processedSchema),
                new TransformerClassGenerator(processedSchema),
                new RecordMapperClassGenerator(processedSchema, true),
                new RecordMapperClassGenerator(processedSchema, false),
                new JavaRecordMapperClassGenerator(processedSchema, true),
                new JavaRecordMapperClassGenerator(processedSchema, false)
        );

        return generateFiles(generators);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                references,
                List.of(),
                List.of()
        );
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithInputsAndResponseObjects() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("inputAndResponseResolvers");
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithSimpleInputsAndResponses() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("simpleResolverWithService");
    }

    @Test
    void generate_mutation_shouldGenerateResolversAndQueriesWithSimpleInputsAndResponses() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("simpleResolverWithMutationType");
    }

    @Test
    void generate_mutationWithListFields_shouldGenerateResolversForLists() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("listResolvers");
    }

    @Test
    void generate_serviceMutationWithNestedInputs_shouldGenerateResolversForNestedStructures() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithNestedTypes");
    }

    @Test
    void generate_serviceMutationWithRecordInputs_shouldGenerateResolversForSimpleFields() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithSimpleFields");
    }

    @Test
    void generate_serviceMutationWithNestedRecordInputs_shouldGenerateResolversForNestedRecordStructures() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithNestedRecordTypes");
    }

    @Test
    void generate_serviceMutationWithNestedSchemaTypes_shouldGenerateResolversForNestedOutputStructures() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithNestedSchemaTypes");
    }

    @Test
    void generate_serviceMutationWithNestedRecordFieldMapping_shouldGenerateResolversForNestedStructures() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithNestedRecordFieldMapping");
    }

    @Test
    void generate_serviceMutationWithEnumInputMapping_shouldGenerateResolversWithEnums() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithEnumInputMapping");
    }

    @Test
    void generate_serviceMutationWithEnumOutputMapping_shouldGenerateResolversWithEnums() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithEnumOutputMapping");
    }

    @Test
    void generate_serviceMutationWithWrongMapping_shouldSkipIncorrectMappings() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithWrongRecordMappings");
    }

    @Test
    void generate_serviceMutationWithoutTable_shouldGenerateResolverWithJavaRecordMapping() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithJavaRecordWithoutTable");
    }

    @Test
    void generate_serviceMutationWithJavaRecordResponse_shouldGenerateResolverWithTypeMapping() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithJavaRecordReturnType");
    }

    @Test //TODO fjerne denne? Det er vel analogt case med det som blir generert til EditCustomerInputAndResponseGeneratedResolver. Mulig dette caset ga mer mening mot kjerneAPI
    void generate_mutation_shouldGenerateResolversWithIDsNotInDBs() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mapIDsNotInDB");
    }

    @Test
    void generate_serviceMutation_shouldGenerateResolversWithErrorHandling() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("exceptionServiceResolvers");
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithValidationErrorHandling() throws IOException {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("validationErrorHandling");
    }

    @Test
    void generate_serviceMutation_shouldGenerateResolversWithValidationErrorHandling() throws IOException {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceValidationErrorHandling");
    }

    @Test
    void generate_serviceMutation_shouldGenerateResolversWithoutErrorHandlingForErrorsMappedByExceptionHandler() throws IOException {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "MyValidationError"));
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceValidationErrorHandlingWhenExceptionsHandledElsewhere");
    }

    @Test
    void generate_whenHasSetUpdateType_shouldGenerateUpdateQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("updateQueries");
    }

    @Test
    void generate_whenHasSetUpdateType_shouldGenerateIterableUpdateQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("updateIterableQueries");
    }

    @Test
    void generate_whenHasSetInsertType_shouldGenerateInsertQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("insertQueries");
    }

    @Test
    void generate_whenHasSetInsertType_shouldGenerateIterableInsertQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("insertIterableQueries");
    }

    @Test
    void generate_whenHasSetUpsertType_shouldGenerateUpsertQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("upsertQueries");
    }

    @Test
    void generate_whenHasSetUpsertType_shouldGenerateIterableUpsertQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("upsertIterableQueries");
    }

    @Test
    void generate_whenHasSetDeleteType_shouldGenerateDeleteQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("deleteQueries");
    }

    @Test
    void generate_whenHasSetDeleteType_shouldGenerateIterableDeleteQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("deleteIterableQueries");
    }

    @Test
    void generate_whenHasSetMutationType_shouldGenerateNestedQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mutationWithNestedResponse");
    }

    @Test
    void generate_whenHasSetMutationType_shouldGenerateQueriesWithEnumInputs() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("enumInputQueries");
    }

    @Test
    void generate_whenHasSetMutationTypeWithMismatchedIterability_shouldGenerateAdjustedNestedQueriesAndResolvers() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mismatchedIterabilityQueries");
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
