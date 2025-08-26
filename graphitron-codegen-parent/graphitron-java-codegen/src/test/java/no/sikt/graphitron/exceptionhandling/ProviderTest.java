package no.sikt.graphitron.exceptionhandling;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.exception.ExceptionToErrorMappingProviderGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.ERROR;
import static no.sikt.graphitron.common.configuration.SchemaComponent.MUTATION_RESPONSE;

@DisplayName("Exception handling - Exception mapping provider class generation")
public class ProviderTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "exceptions/provider";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(ERROR);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new ExceptionToErrorMappingProviderGenerator(schema));
    }

    @BeforeEach
    public void setup() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
    }

    @Test
    @DisplayName("One mutation with one set of errors")
    void defaultCase() {
        assertGeneratedContentMatches("default", MUTATION_RESPONSE);
    }

    @Test
    @DisplayName("One query with one set of errors")
    void query() {
        assertGeneratedContentContains(
                "query",
                "var queryGenericList = List.of(m1);genericMappingsForOperation.put(\"query\", queryGenericList"
        );
    }

    @Test
    @DisplayName("No errors exist")
    void noErrors() {
        assertNothingGenerated("noErrors");
    }

    @Test
    @DisplayName("Error without handler")
    void noHandlers() {
        assertNothingGenerated("noHandlers", Set.of(MUTATION_RESPONSE));
    }

    @Test
    @DisplayName("A handler that uses the matching input")
    void withMatches() {
        assertGeneratedContentContains(
                "withMatches", Set.of(MUTATION_RESPONSE),
                "GenericExceptionMappingContent(\"java.lang.IllegalArgumentException\", \"MATCH\""
        );
    }

    @Test
    @DisplayName("A handler that uses the description input")
    void withDescription() {
        assertGeneratedContentContains("withDescription", Set.of(MUTATION_RESPONSE), "SomeError(path, \"DESC\"");
    }

    @Test
    @DisplayName("One error with a handler and one without")
    void onlyOneHandled() {
        assertGeneratedContentContains("onlyOneHandled", Set.of(MUTATION_RESPONSE), "-> new SomeError0");
        resultDoesNotContain("onlyOneHandled", Set.of(MUTATION_RESPONSE), "-> new SomeError1");
    }

    @Test
    @DisplayName("Validation set to false")
    void noValidation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(false, null));
        assertGeneratedContentMatches("default", MUTATION_RESPONSE);
    }

    @Test  // These are the same for mutations with and without services, so added just this one test for services.
    @DisplayName("One mutation using service directive")
    void service() {
        assertGeneratedContentContains("service", "-> new SomeError");
    }

    @Test
    @DisplayName("Two handlers for one error")
    void multipleHandlers() {
        assertGeneratedContentContains(
                "multipleHandlers", Set.of(MUTATION_RESPONSE),
                "m1 = new GenericExceptionContentToErrorMapping(" +
                        "new GenericExceptionMappingContent(\"java.lang.IllegalArgumentException\", null),(path, msg) -> new SomeError",
                "m2 = new GenericExceptionContentToErrorMapping(" +
                        "new GenericExceptionMappingContent(\"java.lang.IllegalStateException\", null),(path, msg) -> new SomeError",
                "List.of(m1, m2)"
        );
    }

    @Test
    @DisplayName("Two handlers for two errors")
    void multiple() {
        assertGeneratedContentContains(
                "multiple",
                "-> new SomeError0",
                "-> new SomeError1",
                "mutation0GenericList = List.of(m1)",
                "genericMappingsForOperation.put(\"mutation0\", mutation0GenericList",
                "mutation1GenericList = List.of(m2)",
                "genericMappingsForOperation.put(\"mutation1\", mutation1GenericList"
        );
    }

    @Test // Note, this produces illegal code.
    @DisplayName("Two handlers for two errors in the same response")
    void multipleInOneResponse() {
        assertGeneratedContentContains(
                "multipleInOneResponse", Set.of(MUTATION_RESPONSE),
                "-> new SomeError0",
                "-> new SomeError1",
                "mutationGenericList = List.of(m1)",
                "mutationGenericList = List.of(m2)"
        );
    }

    @Test
    @DisplayName("Error type with a database handler")
    void databaseHandledError() {
        assertGeneratedContentContains(
                "databaseHandledError", Set.of(MUTATION_RESPONSE),
                "m1 = new DataAccessExceptionContentToErrorMapping(" +
                        "new DataAccessExceptionMappingContent(null, null),(path, msg) -> new SomeError(",
                "mutationDatabaseList = List.of(m1)",
                "dataAccessMappingsForOperation.put(\"mutation\", mutationDatabaseList"
        );
    }

    @Test
    @DisplayName("Two database handlers for one error")
    void multipleDatabaseHandlers() {
        assertGeneratedContentContains(
                "multipleDatabaseHandlers", Set.of(MUTATION_RESPONSE),
                "m1 = new DataAccessExceptionContentToErrorMapping(" +
                        "new DataAccessExceptionMappingContent(\"C_0\", null),(path, msg) -> new SomeError",
                "m2 = new DataAccessExceptionContentToErrorMapping(" +
                        "new DataAccessExceptionMappingContent(\"C_1\", null),(path, msg) -> new SomeError",
                "List.of(m1, m2)"
        );
    }

    @Test
    @DisplayName("A database handler with code set")
    void databaseWithCode() {
        assertGeneratedContentContains(
                "databaseWithCode", Set.of(MUTATION_RESPONSE),
                "DataAccessExceptionMappingContent(\"CODE\","
        );
    }

    @Test
    @DisplayName("A database handler with matches set")
    void databaseWithMatches() {
        assertGeneratedContentContains(
                "databaseWithMatches", Set.of(MUTATION_RESPONSE),
                "DataAccessExceptionMappingContent(null, \"MATCHES\")"
        );
    }

    @Test
    @DisplayName("Error type with both a database handler and generic handler")
    void bothGenericAndDatabase() {
        assertGeneratedContentContains(
                "bothGenericAndDatabase", Set.of(MUTATION_RESPONSE),
                "m1 = new DataAccessExceptionContentToErrorMapping",
                "m2 = new GenericExceptionContentToErrorMapping",
                "mutationDatabaseList = List.of(m1)",
                "mutationGenericList = List.of(m2)"
        );
    }

    @Test // Note, this produces illegal code.
    @DisplayName("Two error types with both a database handler and generic handler within the same response")
    void bothGenericAndDatabaseInMultipleErrorsForOneResponse() {
        assertGeneratedContentContains(
                "bothGenericAndDatabaseInMultipleErrorsForOneResponse", Set.of(MUTATION_RESPONSE),
                "m1 = new DataAccessExceptionContentToErrorMapping(" +
                        "new DataAccessExceptionMappingContent(null, null),(path, msg) -> new SomeError0",
                "m2 = new GenericExceptionContentToErrorMapping(" +
                        "new GenericExceptionMappingContent(\"java.lang.IllegalArgumentException\", null),(path, msg) -> new SomeError0",
                "m3 = new DataAccessExceptionContentToErrorMapping(" +
                        "new DataAccessExceptionMappingContent(null, null),(path, msg) -> new SomeError1",
                "m4 = new GenericExceptionContentToErrorMapping(" +
                        "new GenericExceptionMappingContent(\"java.lang.IllegalArgumentException\", null),(path, msg) -> new SomeError1",
                "mutationDatabaseList = List.of(m1)",
                "mutationGenericList = List.of(m2)",
                "mutationDatabaseList = List.of(m3)",
                "mutationGenericList = List.of(m4)"
        );
    }

    @Test
    @DisplayName("Union with one handled error")
    void union() {
        assertGeneratedContentContains(
                "union", Set.of(MUTATION_RESPONSE),
                "m1 = new GenericExceptionContentToErrorMapping(",
                "mutationGenericList = List.of(m1)"
        );
    }

    @Test
    @DisplayName("Union with one handled error and one unhandled")
    void unionWithUnhandledError() {
        assertGeneratedContentContains(
                "unionWithUnhandledError", Set.of(MUTATION_RESPONSE),
                "mutationGenericList = List.of(m1)");
    }

    @Test
    @DisplayName("Union with two handled errors")
    void unionMultipleHandlers() {
        assertGeneratedContentContains(
                "unionMultipleHandlers", Set.of(MUTATION_RESPONSE),
                "new SomeError0(",
                "new SomeError1(",
                "mutationGenericList = List.of(m1, m2)"
        );
    }

    @Test
    @DisplayName("Union with a database handled error")
    void unionDatabase() {
        assertGeneratedContentContains(
                "unionDatabase", Set.of(MUTATION_RESPONSE),
                "m1 = new DataAccessExceptionContentToErrorMapping(",
                "mutationDatabaseList = List.of(m1)"
        );
    }

    @Test
    @DisplayName("Union with two handled database errors")
    void unionMultipleDatabaseHandlers() {
        assertGeneratedContentContains(
                "unionMultipleDatabaseHandlers", Set.of(MUTATION_RESPONSE),
                "new SomeError0(",
                "new SomeError1(",
                "mutationDatabaseList = List.of(m1, m2)"
        );
    }

    @Test
    @DisplayName("Union with generic and database handled errors")
    void unionMultipleMixedHandlers() {
        assertGeneratedContentContains(
                "unionMultipleMixedHandlers", Set.of(MUTATION_RESPONSE),
                "m1 = new DataAccessExceptionContentToErrorMapping(" +
                        "new DataAccessExceptionMappingContent(null, null),(path, msg) -> new SomeError1",
                "m2 = new GenericExceptionContentToErrorMapping(" +
                        "new GenericExceptionMappingContent(\"java.lang.IllegalArgumentException\", null),(path, msg) -> new SomeError0",
                "mutationDatabaseList = List.of(m1)",
                "mutationGenericList = List.of(m2)"
        );
    }

    @Test
    @DisplayName("Union with both generic and database handlers for multiple errors")
    void unionMultipleErrorsWithMultipleMixedHandlers() {
        assertGeneratedContentContains(
                "unionMultipleErrorsWithMultipleMixedHandlers", Set.of(MUTATION_RESPONSE),
                "m1 = new DataAccessExceptionContentToErrorMapping(" +
                        "new DataAccessExceptionMappingContent(null, null),(path, msg) -> new SomeError0",
                "m2 = new DataAccessExceptionContentToErrorMapping(" +
                        "new DataAccessExceptionMappingContent(null, null),(path, msg) -> new SomeError1",
                "m3 = new GenericExceptionContentToErrorMapping(" +
                        "new GenericExceptionMappingContent(\"java.lang.IllegalArgumentException\", null),(path, msg) -> new SomeError0",
                "m4 = new GenericExceptionContentToErrorMapping(" +
                        "new GenericExceptionMappingContent(\"java.lang.IllegalArgumentException\", null),(path, msg) -> new SomeError1",
                "mutationDatabaseList = List.of(m1, m2)",
                "mutationGenericList = List.of(m3, m4)"
        );
    }
}
