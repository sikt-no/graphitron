package no.fellesstudentsystem.graphitron_newtestorder.queries.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphitron_newtestorder.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.CUSTOMER_CONNECTION;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.SPLIT_QUERY_WRAPPER;

@DisplayName("Query pagination - Pagination parameters applied inside queries")
public class PaginationTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/pagination";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_CONNECTION);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Connection with no other fields")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Connection with an extra field")
    void withOtherField() {
        assertGeneratedContentContains(
                "withOtherField",
                ", String name, Integer pageSize, String after,"
        );
    }

    @Test
    @DisplayName("Connection not on the root level")
    void splitQuery() {
        assertGeneratedContentMatches("splitQuery", SPLIT_QUERY_WRAPPER);
    }
}
