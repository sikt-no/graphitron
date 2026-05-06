package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.CountOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.EntityOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.InterfaceOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

/**
 * Verifies the {@code .hint("/* DataFetcher=... *‍/")} call emitted by every entry-point query
 * generator. One test per generator that emits a hint:
 * {@code FetchMappedObjectDBMethodGenerator}, {@code FetchCountDBMethodGenerator},
 * {@code FetchSingleTableInterfaceDBMethodGenerator}, {@code FetchMultiTableDBMethodGenerator},
 * {@code FetchNodeImplementationDBMethodGenerator},
 * {@code FetchEntityImplementationDBMethodGenerator}.
 */
@DisplayName("Query hints - DataFetcher identification embedded as SQL comment")
public class QueryHintTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new MapOnlyFetchDBClassGenerator(schema),
                new CountOnlyFetchDBClassGenerator(schema),
                new InterfaceOnlyFetchDBClassGenerator(schema),
                new EntityOnlyFetchDBClassGenerator(schema)
        );
    }

    @BeforeEach
    void enableHint() {
        GeneratorConfig.setQueryHintEnabled(true);
    }

    @Test
    @DisplayName("FetchMappedObject: plain root query gets a DataFetcher= hint")
    void plainQueryGetsHint() {
        assertGeneratedContentContains(
                "output/default", Set.of(CUSTOMER_TABLE),
                ".hint(\"/* DataFetcher=Query.query build= */\")"
        );
    }

    @Test
    @DisplayName("FetchCount: hint includes a query= reference to the count method")
    void countQueryHintIncludesMethodReference() {
        assertGeneratedContentContains(
                "count/default", Set.of(CUSTOMER_CONNECTION),
                ".hint(\"/* DataFetcher=Query.query query=QueryDBQueries.countQueryForQuery build= */\")"
        );
    }

    @Test
    @DisplayName("FetchSingleTableInterface: discriminator-based interface gets a plain hint")
    void singleTableInterfaceQueryGetsHint() {
        assertGeneratedContentContains(
                "interfaces/singleTableInterface/default",
                ".hint(\"/* DataFetcher=Query.address build= */\")"
        );
    }

    @Test
    @DisplayName("FetchMultiTable: union/multitable interface gets a plain hint at the root")
    void multiTableQueryGetsHint() {
        assertGeneratedContentContains(
                "interfaces/multitableInterface/default",
                ".hint(\"/* DataFetcher=Query.someInterface build= */\")"
        );
    }

    @Test
    @DisplayName("FetchMultiTable: split-query multitable field gets the hint on the outer wrapping select")
    void multiTableSplitQueryGetsHint() {
        assertGeneratedContentContains(
                "interfaces/multitableInterface/splitQuery", Set.of(PERSON_WITH_EMAIL),
                ".hint(\"/* DataFetcher=Payment.staffAndCustomers build= */\")"
        );
    }

    @Test
    @DisplayName("FetchNodeImplementation: hint includes the per-implementation node method")
    void nodeQueryGetsHintWithMethodReference() {
        assertGeneratedContentContains(
                "interfaces/node/default", Set.of(NODE),
                ".hint(\"/* DataFetcher=Query.node query=CustomerDBQueries.customerForNode build= */\")"
        );
    }

    @Test
    @DisplayName("FetchEntityImplementation: federation hint is rooted at Query._entities and names the per-type method")
    void entityQueryGetsHintWithMethodReference() {
        assertGeneratedContentContains(
                "entity/default", Set.of(FEDERATION_QUERY),
                ".hint(\"/* DataFetcher=Query._entities query=CustomerDBQueries.customerFor_Entity build= */\")"
        );
    }

    @Test
    @DisplayName("Build identifier from GeneratorConfig is included in the hint")
    void buildIdAppearsInHint() {
        GeneratorConfig.setQueryHintBuildId("graphitron-test:1.2.3@deadbeef");
        assertGeneratedContentContains(
                "output/default", Set.of(CUSTOMER_TABLE),
                ".hint(\"/* DataFetcher=Query.query build=graphitron-test:1.2.3@deadbeef */\")"
        );
    }
}