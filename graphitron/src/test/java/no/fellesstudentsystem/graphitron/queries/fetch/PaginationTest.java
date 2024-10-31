package no.fellesstudentsystem.graphitron.queries.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.*;

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

    @Test
    @DisplayName("No pagination on non-connection multiset")
    void multiset() {
        resultDoesNotContain("multiset", Set.of(CUSTOMER_TABLE),
                ".getOrderByValues(",
                ".seek("
        );
    }
}
