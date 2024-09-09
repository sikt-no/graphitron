package no.fellesstudentsystem.graphitron_newtestorder.resolvers.standard.edit;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.RecordValidation;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.exception.MutationExceptionStrategyConfigurationGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.RESOLVER_MUTATION_SERVICE;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.ERROR;

@DisplayName("Mutation resolvers - Resolvers for mutations")
public class ValidationHandlingTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/edit/validation";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RESOLVER_MUTATION_SERVICE);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(ERROR);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new MutationExceptionStrategyConfigurationGenerator(schema));
    }

    @Test
    @DisplayName("One query")
    void defaultCase() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "ValidationError"));
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Two queries")
    void twoQueries() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "ValidationError"));
        assertGeneratedContentContains(
                "twoQueries",
                "\"mutation0\", errors -> {",
                "mutationsForException.get(ValidationViolationGraphQLException.class).add(\"mutation1\")",
                "mutationsForException.get(IllegalArgumentException.class).add(\"mutation1\")",
                "\"mutation1\", errors -> {"
        );
    }

    @Test
    @DisplayName("No validation")
    void noValidation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(false, "ValidationError"));
        assertFilesAreGenerated("noValidation");
    }

    @Test
    @DisplayName("No errors")
    void noErrors() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "ValidationError"));
        assertGeneratedContentMatches("noErrors");
    }

    @Test
    @DisplayName("Union error type")
    void union() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "ValidationError0"));  // Putting the union itself here does not work.
        assertGeneratedContentContains("union", ".setErrors((List<ValidationUnion>) errors");
    }

    @Test
    @DisplayName("Union error type including one handler")
    void unionWithHandled() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, "ValidationError"));
        assertGeneratedContentContains("unionWithHandled", ".setErrors((List<ValidationUnion>) errors");
    }
}
