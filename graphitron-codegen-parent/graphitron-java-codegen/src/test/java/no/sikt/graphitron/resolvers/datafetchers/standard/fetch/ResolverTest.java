package no.sikt.graphitron.resolvers.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch resolvers - Resolvers for queries")
public class ResolverTest extends GeneratorTest {

    // Disabled until GGG-104
    @Override
    protected boolean validateSchema() {
        return false;
    }

    @Override
    protected String getSubpath() {
        return "resolvers/datafetchers/fetch/standard";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_TYPE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("Basic root resolver with no parameters")
    void defaultCase() {
        assertGeneratedContentMatches("operation/default");
    }

    @Test
    @DisplayName("Root resolvers with various input data types")
    void inputDatatypes() {
        assertGeneratedContentMatches("operation/inputDatatypes", DUMMY_INPUT, DUMMY_ENUM);
    }

    @Test
    @DisplayName("Input name starts with a capital letter")
    void wrongInputCapitalisation() {
        assertGeneratedContentContains(
                "operation/wrongInputCapitalisation",
                "iN =", ".get(\"IN\")", ", iN,"
        );
    }

    @Test
    @DisplayName("Input name for an input type starts with a capital letter")
    void wrongInputTypeCapitalisation() {
        assertGeneratedContentContains(
                "operation/wrongInputTypeCapitalisation",
                Set.of(DUMMY_INPUT),
                "iN =", ".get(\"IN\")", ", iN,"
        );
    }

    @Test
    @DisplayName("Root resolver that returns a list")
    void returningList() {
        assertGeneratedContentContains("operation/returningList", "public static DataFetcher<CompletableFuture<List<DummyType>>> query()");
    }

    @Test
    @DisplayName("Root resolver that returns an optional list")
    void returningOptionalList() {
        assertGeneratedContentContains("operation/returningOptionalList", "public static DataFetcher<CompletableFuture<List<DummyType>>> query()");
    }

    @Test
    @DisplayName("Root resolver that is not generated")
    void notGenerated() {
        assertGeneratedContentMatches("operation/notGenerated");
    }

    @Test
    @DisplayName("Basic resolver with no parameters")
    void splitQuery() {
        assertGeneratedContentMatches("splitquery/default", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolvers with various input data types")
    void splitQueryInputDatatypes() {
        assertGeneratedContentMatches("splitquery/inputDatatypes", DUMMY_INPUT, DUMMY_ENUM, SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolver that returns a list")
    void splitQueryReturningList() {
        assertGeneratedContentContains("splitquery/returningList", Set.of(SPLIT_QUERY_WRAPPER), "public static DataFetcher<CompletableFuture<List<DummyType>>> query()");
    }

    @Test
    @DisplayName("Resolver that returns an optional list")
    void splitQueryReturningOptionalList() {
        assertGeneratedContentContains("splitquery/returningOptionalList", Set.of(SPLIT_QUERY_WRAPPER), "public static DataFetcher<CompletableFuture<List<DummyType>>> query()");
    }

    @Test
    @DisplayName("Resolver created from a type that inherits the table from another type")
    void splitQueryFromTypeWithoutTable() {
        assertGeneratedContentMatches("splitquery/fromTypeWithoutTable", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Resolver that is not generated")
    void splitQueryNotGenerated() {
        assertGeneratedContentMatches("splitquery/notGenerated", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Root resolver returning multi-table interface")
    void multitableInterface() {
        assertGeneratedContentContains("operation/multitableInterface",
                "CompletableFuture<List<Titled>>",
                "QueryDBQueries.titledForQuery("
        );
    }

    @Test
    @DisplayName("Root resolver returning single table interface")
    void singleTableInterface() {
        assertGeneratedContentContains("operation/singleTableInterface",
                "CompletableFuture<List<Address>>",
                "QueryDBQueries.addressForQuery("
        );
    }
}
