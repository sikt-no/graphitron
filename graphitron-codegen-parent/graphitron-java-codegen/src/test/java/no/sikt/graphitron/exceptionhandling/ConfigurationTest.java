package no.sikt.graphitron.exceptionhandling;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.MUTATION_RESPONSE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.VALIDATION_ERROR;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Exception handling - Exception configuration class generation")
public class ConfigurationTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "exceptions/configuration";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(VALIDATION_ERROR);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MutationExceptionStrategyConfigurationGenerator(schema));
    }

    @BeforeEach
    public void setup() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "ValidationError"));// Putting a union here does not work.
    }

    @Test
    @DisplayName("One mutation with one set of errors")
    void defaultCase() {
        assertGeneratedContentMatches("default", MUTATION_RESPONSE);
    }

    @Test  // These are the same for mutations with and without services, so added just this one test for services.
    @DisplayName("One mutation using service directive")
    void service() {
        assertGeneratedContentContains(
                "service",
                ".computeIfAbsent(ValidationViolationGraphQLException.class, k -> new HashSet<>()).add(\"mutation\")",
                ".computeIfAbsent(IllegalArgumentException.class, k -> new HashSet<>()).add(\"mutation\")",
                "payloadForMutation.put(\"mutation\", errors -> {var payload = new Response();payload.setErrors((List<ValidationError>) errors);return payload;"
        );
    }

    @Test
    @DisplayName("Validation set to false")
    void noValidation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(false, null));
        assertNothingGenerated("default", Set.of(MUTATION_RESPONSE));
    }

    @Test
    @DisplayName("No errors exist")
    void noErrors() {
        assertGeneratedContentContains(
                "noErrors",
                "{mutationsForException = new HashMap<>();payloadForMutation = new HashMap<>();}"  // Empty class
        );
    }

    @Test
    @DisplayName("Two mutations with an error")
    void twoMutations() {
        assertGeneratedContentContains(
                "twoMutations",
                "\"mutation0\", errors -> {",
                "mutationsForException.get(ValidationViolationGraphQLException.class).add(\"mutation1\")",
                "mutationsForException.get(IllegalArgumentException.class).add(\"mutation1\")",
                "\"mutation1\", errors -> {"
        );
    }

    @Test
    @DisplayName("Error type with a handler")
    void handledError() {
        assertGeneratedContentContains(
                "handledError", Set.of(MUTATION_RESPONSE),
                ".computeIfAbsent(UnsupportedOperationException.class, k ->"
        );
    }

    @Test
    @DisplayName("Error type with a database handler")
    void databaseHandledError() {
        assertGeneratedContentContains(
                "databaseHandledError", Set.of(MUTATION_RESPONSE),
                ".computeIfAbsent(DataAccessException.class, k ->"
        );
    }

    @Test
    @DisplayName("Only unrelated errors exist")
    void unrelatedError() {
        assertGeneratedContentContains(
                "unrelatedError", Set.of(MUTATION_RESPONSE),
                "{mutationsForException = new HashMap<>();payloadForMutation = new HashMap<>();}"
        );
    }

    @Test
    @DisplayName("Handler points to an invalid class name")
    void invalidClassName() {
        assertThatThrownBy(() -> generateFiles("invalidClassName", Set.of(MUTATION_RESPONSE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to find exception className: INVALID, declared for mutation: mutation");
    }

    @Test
    @DisplayName("Union error type")
    void union() {
        assertGeneratedContentContains(
                "union", Set.of(MUTATION_RESPONSE),
                ".setErrors((List<ValidationUnion>) errors"
        );
    }

    @Test
    @DisplayName("Union error type including one handler")
    void unionWithHandled() {
        assertGeneratedContentContains(
                "unionWithHandled", Set.of(MUTATION_RESPONSE),
                ".setErrors((List<ValidationUnion>) errors"
        );
    }

    @Test
    @DisplayName("Mutation with multiple error fields")
    void multipleErrorsInMutation() {
        assertGeneratedContentContains(
                "multipleErrorsInMutation", Set.of(MUTATION_RESPONSE),
                ".setErrors0((List<ValidationError>)",
                ".setErrors1((List<UnrelatedError>)"
        );
    }

    @Test
    @DisplayName("Mutation with multiple error fields including one union")
    void multipleErrorsInMutationWithUnion() {
        assertGeneratedContentContains(
                "multipleErrorsInMutationWithUnion", Set.of(MUTATION_RESPONSE),
                ".setErrors0((List<ValidationError>)",
                ".setErrors1((List<ValidationUnion>)"
        );
    }
}
