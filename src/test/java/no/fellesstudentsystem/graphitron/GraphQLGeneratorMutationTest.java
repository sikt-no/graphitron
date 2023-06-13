package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.exceptions.TestException;
import no.fellesstudentsystem.graphitron.exceptions.TestExceptionCause;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphitron.services.TestPermisjonService;
import no.fellesstudentsystem.graphitron.services.TestPersonService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GraphQLGeneratorMutationTest {
    public static final String
            SRC_TEST_RESOURCES_PATH = "mutation",
            SRC_TEST_RESOURCES = "src/test/resources/" + SRC_TEST_RESOURCES_PATH + "/";
    @TempDir
    Path tempOutputDirectory;

    private final Map<String, Class<?>>
            exceptionOverrides = Map.of("EXCEPTION_TEST", TestException.class, "EXCEPTION_TEST_CAUSE", TestExceptionCause.class),
            serviceOverrides = Map.of("TEST_PERSON", TestPersonService.class, "TEST_PERMISJON", TestPermisjonService.class);

    @AfterEach
    void teardown() {
        TestCommon.teardown();
    }

    private Map<String, String> generateFiles(String schemaParentFolder) throws IOException {
        return generateFiles(schemaParentFolder, false);
    }

    private Map<String, String> generateFiles(String schemaParentFolder, boolean warnDirectives) throws IOException {
        var test = new TestCommon(schemaParentFolder, SRC_TEST_RESOURCES_PATH, tempOutputDirectory);

        var processedSchema = GraphQLGenerator.getProcessedSchema(warnDirectives);
        processedSchema.validate();
        List<ClassGenerator<? extends GenerationTarget>> generators = List.of(
                new UpdateResolverClassGenerator(processedSchema, exceptionOverrides, serviceOverrides),
                new UpdateDBClassGenerator(processedSchema)
        );

        test.setGenerators(generators);
        return test.generateFiles();
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithInputsAndResponseObjects() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("inputAndResponseResolvers");
    }

    @Test
    void generate_mutationWithListFields_shouldGenerateResolversForLists() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("listResolvers");
    }

    @Test
    void generate_mutationWithNestedInputs_shouldGenerateResolversForNestedStructures() throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder("nestedResolvers");
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
    void generate_whenServiceNotFound_shouldThrowException() {
        assertThatThrownBy(() -> generateFiles("error/serviceNotFound"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Requested to generate a method for 'endrePersonSimple' that calls service 'SERVICE_NOT_FOUND', " +
                                "but no such service was found in 'no.fellesstudentsystem.codegenenums.GeneratorService'"
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

    private void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String schemaFolder, String expectedOutputFolder) throws IOException {
        Map<String, String> generatedFiles = generateFiles(schemaFolder);
        TestCommon.assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(SRC_TEST_RESOURCES + expectedOutputFolder, generatedFiles);
    }

    private void assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(String resourceRootFolder) throws IOException {
        assertThatGeneratedFilesMatchesExpectedFilesInOutputFolder(resourceRootFolder, resourceRootFolder);
    }
}
