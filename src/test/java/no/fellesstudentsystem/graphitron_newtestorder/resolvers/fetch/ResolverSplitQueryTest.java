package no.fellesstudentsystem.graphitron_newtestorder.resolvers.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

@DisplayName("Fetch resolvers - Resolvers for queries")
public class ResolverSplitQueryTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "resolvers/standard/fetch/splitquery";

    public ResolverSplitQueryTest() {
        super(SRC_TEST_RESOURCES_PATH);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Basic resolver with no parameters")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Resolvers with various input data types")
    void inputDatatypes() {
        assertGeneratedContentMatches("inputDatatypes");
    }
}
