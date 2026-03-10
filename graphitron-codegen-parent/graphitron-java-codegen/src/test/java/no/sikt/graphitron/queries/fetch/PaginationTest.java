package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.PaginationOnlyDBClassGenerator;
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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new PaginationOnlyDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Connection with no other fields")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_CONNECTION_WITH_NO_OPTIONALS);
    }

    @Test
    @DisplayName("Connection with an extra field")
    void withOtherField() {
        assertGeneratedContentContains(
                "withOtherField",
                Set.of(CUSTOMER_CONNECTION_WITH_NO_OPTIONALS),
                ", String _mi_name, Integer _iv_pageSize, String _mi_after,"
        );
    }

    @Test
    @DisplayName("Connection not on the root level")
    void splitQuery() {
        assertGeneratedContentMatches(
                "splitQuery",
                CUSTOMER_CONNECTION_WITH_NO_OPTIONALS,
                SPLIT_QUERY_WRAPPER
        );
    }

    @Test
    @DisplayName("No pagination on non-connection multiset")
    void multiset() {
        resultDoesNotContain(
                "multiset",
                Set.of(CUSTOMER_TABLE),
                ".getOrderByValues(",
                ".seek("
        );
    }

    @Test
    @DisplayName("When optional field 'totalCount' is included in the schema, generate the count method")
    void shouldGenerateAllOptionalFields() {
        assertGeneratedContentContains(
                "allOptionalFieldsIncluded",
                Set.of(CUSTOMER_CONNECTION),
                "countCustomersForQuery(");
    }

    @Test
    @DisplayName("When optional field 'totalCount' is not included in the schema, do not generate the count method")
    void shouldGenerateNoOptionalFields() {
        resultDoesNotContain(
                "allOptionalFieldsExcluded",
                Set.of(CUSTOMER_CONNECTION_WITH_NO_OPTIONALS),
                "countCustomersForQuery(");
    }
}
