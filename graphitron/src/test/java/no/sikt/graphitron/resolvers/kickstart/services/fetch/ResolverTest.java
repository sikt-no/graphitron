package no.sikt.graphitron.resolvers.kickstart.services.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.kickstart.fetch.FetchResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.RESOLVER_FETCH_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch service resolvers - Resolvers that call custom services")
public class ResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/kickstart/fetch/services";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RESOLVER_FETCH_SERVICE);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Basic root service with no extra parameters")
    void defaultCase() {
        assertGeneratedContentMatches("operation/default");
    }

    @Test
    @DisplayName("Basic root service with a parameter")
    void withInput() {
        assertGeneratedContentContains("operation/withInput", "query(String id,", "resolverFetchService.query(id)");
    }

    @Test
    @DisplayName("Root service with pagination")
    void withPagination() {
        assertGeneratedContentMatches("operation/withPagination", CUSTOMER_CONNECTION);
    }

    @Test
    @DisplayName("Root service with pagination and a record input")
    void withPaginationAndRecord() {
        assertGeneratedContentContains(
                "operation/withPaginationAndRecord", Set.of(CUSTOMER_CONNECTION, CUSTOMER_INPUT_TABLE),
                "query(Integer first, String after, CustomerInputTable in,",
                "inRecord = transform.customerInputTableToJOOQRecord(in, \"in\")",
                "resolverFetchService.queryList(inRecord, pageSize, after",
                "resolverFetchService.countQueryList(inRecord"
        );
    }

    @Test
    @DisplayName("Basic service with no extra parameters")
    void splitQuery() {
        assertGeneratedContentMatches("splitquery/default", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Basic service with a parameter")
    void splitQueryWithInput() {
        assertGeneratedContentContains(
                "splitquery/withInput", Set.of(SPLIT_QUERY_WRAPPER),
                "query(Wrapper wrapper, String id,",
                "resolverFetchService.query(ids, id)"
        );
    }

    @Test
    @DisplayName("Service with pagination")
    void splitQueryWithPagination() {
        assertGeneratedContentMatches("splitquery/withPagination", SPLIT_QUERY_WRAPPER, CUSTOMER_CONNECTION);
    }

    @Test
    @DisplayName("Service with pagination and a record input")
    void splitQueryWithPaginationAndRecord() {
        assertGeneratedContentContains(
                "splitquery/withPaginationAndRecord", Set.of(SPLIT_QUERY_WRAPPER, CUSTOMER_CONNECTION, CUSTOMER_INPUT_TABLE),
                "query(Wrapper wrapper, Integer first, String after, CustomerInputTable in,",
                "inRecord = transform.customerInputTableToJOOQRecord(in, \"in\")",
                "resolverFetchService.queryMap(ids, inRecord, pageSize, after",
                "resolverFetchService.countQueryMap(ids, inRecord"
        );
    }
}
