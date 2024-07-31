package no.fellesstudentsystem.graphitron_newtestorder.resolvers.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.CUSTOMER_CONNECTION_ORDER;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.SPLIT_QUERY_WRAPPER;

@DisplayName("Sorted fetch resolvers - Resolvers with special ordering")
public class SortingTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/sorting";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Basic resolver with a sorting parameter")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_CONNECTION_ORDER);
    }

    @Test
    @DisplayName("Basic resolver with a sorting parameter on a split query")
    void splitQuery() {
        assertGeneratedContentMatches("splitQuery", SPLIT_QUERY_WRAPPER, CUSTOMER_CONNECTION_ORDER);
    }
}
