package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

@DisplayName("Fetch tableService queries")
public class TableServiceTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/tableService";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new DBClassGenerator(schema));
    }

    @Test
    @DisplayName("TableService test")
    void testTableService() {
        assertGeneratedContentContains("default",
                "var customerTableService = new CustomerTableService();",
                        "_customer = customerTableService.customerTable(_customer)",
                        ".from(_customer)");
    }

    @Test
    @DisplayName("TableService arguments test")
    void testWithArgsTableService() {
        assertGeneratedContentContains("withArgs",
                "var customerTableService = new CustomerTableService();",
                "_customer = customerTableService.customerTable(_customer, first_name)",
                ".from(_customer)");
    }

    @Test
    @DisplayName("TableService paginated test")
    void testConnectionTableService() {
        assertGeneratedContentMatches("paginated");
    }
    @Test
    @DisplayName("TableService splitQuery test")
    void testSplitQueryTableService() {
        assertGeneratedContentMatches("splitQuery");
    }
}
