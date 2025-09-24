package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.db.DBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

@DisplayName("Fetch tableMethod queries")
public class TableMethodTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/tableMethod";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new DBClassGenerator(schema));
    }

    @Test
    @DisplayName("TableMethod test")
    void testTableMethod() {
        assertGeneratedContentContains("default",
                "var customerTableMethod = new CustomerTableMethod();",
                        "_customer = customerTableMethod.customerTable(_customer)",
                        ".from(_customer)");
    }

    @Test
    @DisplayName("TableMethod arguments test")
    void testWithArgsTableMethod() {
        assertGeneratedContentContains("withArgs",
                "var customerTableMethod = new CustomerTableMethod();",
                "_customer = customerTableMethod.customerTable(_customer, first_name)",
                ".from(_customer)");
    }

    @Test
    @DisplayName("TableMethod paginated test")
    void testConnectionTableMethod() {
        assertGeneratedContentMatches("paginated");
    }
    @Test
    @DisplayName("TableMethod splitQuery test")
    void testSplitQueryTableMethod() {
        assertGeneratedContentMatches("splitQuery");
    }
}
