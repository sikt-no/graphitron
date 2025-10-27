package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
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
                ", String name, Integer _iv_pageSize, String after,"
        );
    }

    @Test
    @DisplayName("Connection not on the root level")
    void splitQuery() {
        assertGeneratedContentMatches("splitQuery", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("No pagination on non-connection multiset")
    void multiset() {
        resultDoesNotContain("multiset", Set.of(CUSTOMER_TABLE),
                ".getOrderByValues(",
                ".seek("
        );
    }
}
