package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.enums.FileTest;
import no.fellesstudentsystem.graphitron.enums.KjonnTest;
import no.fellesstudentsystem.graphitron.exceptions.TestException;
import no.fellesstudentsystem.graphitron.exceptions.TestExceptionCause;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron.services.TestPermisjonService;
import no.fellesstudentsystem.graphitron.services.TestPersonService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorMutationTest extends TestCommon {
    public static final String SRC_TEST_RESOURCES_PATH = "mutation";

    private final Map<String, Class<?>>
            exceptions = Map.of("EXCEPTION_TEST", TestException.class, "EXCEPTION_TEST_CAUSE", TestExceptionCause.class),
            services = Map.of("TEST_PERSON", TestPersonService.class, "TEST_PERMISJON", TestPermisjonService.class),
            enums = Map.of("FILE_TEST", FileTest.class, "KJONN_TEST", KjonnTest.class);

    public GraphQLGeneratorMutationTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected Map<String, List<String>> generateFiles(String schemaParentFolder, boolean warnDirectives) throws IOException {
        var processedSchema = getProcessedSchema(schemaParentFolder, warnDirectives);
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new UpdateResolverClassGenerator(processedSchema),
                new UpdateDBClassGenerator(processedSchema)
        );

        return generateFiles(generators);
    }

    @Override
    protected void setProperties() {
        GeneratorConfig.setProperties(
                DEFAULT_SYSTEM_PACKAGE,
                Set.of(),
                tempOutputDirectory.toString(),
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                enums,
                Map.of(),
                services,
                exceptions
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
    void generate_mutationWithNestedInputs_shouldGenerateResolversForNestedStructures() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("serviceResolversWithNestedTypes");
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithIDsNotInDBs() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("mapIDsNotInDB");
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithErrorHandling() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("exceptionResolvers");
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
                .hasMessage(
                        "Requested to generate a method for 'endrePersonSimple' that calls service 'SERVICE_NOT_FOUND', " +
                                "but no such service was found."
                );
    }

    @Test
    void generate_whenServiceMethodNotFound_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/serviceMethodNotFound"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Service 'no.fellesstudentsystem.graphitron.services.TestPersonService'" +
                                " contains no method with the name 'endrePersonSimple'" +
                                " and 2 parameter(s), which is required to generate the resolver."
                );
    }

    @Test
    void generate_whenServiceAndMutationTypeNotSet_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/serviceAndMutationTypeNotSet"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Mutation 'endrePerson' is set to generate, but has neither a service nor mutation type set.");
    }

    @Test
    void generate_whenMutationTypeHasNoID_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/mutationTypeWithoutID"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not find a suitable ID to return for 'endrePersonWithoutID'.");
    }

    @Test
    void generate_whenMutationTypeHasNoRecord_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/mutationTypeWithoutRecord"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Must have at least one record reference when generating resolvers with queries. Mutation 'endrePerson' has no records attached.");
    }
}
