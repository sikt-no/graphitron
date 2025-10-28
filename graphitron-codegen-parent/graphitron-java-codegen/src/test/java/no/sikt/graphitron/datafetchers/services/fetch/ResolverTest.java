package no.sikt.graphitron.datafetchers.services.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.CONTEXT_SERVICE;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.RESOLVER_FETCH_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch service resolvers - Resolvers that call custom services")
public class ResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "datafetchers/fetch/services";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RESOLVER_FETCH_SERVICE, CONTEXT_SERVICE);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new OperationClassGenerator(schema));
    }

    @Test
    @DisplayName("Basic root service with no extra parameters")
    void defaultCase() {
        assertGeneratedContentMatches("operation/default");
    }

    @Test
    @DisplayName("Basic root service with a parameter")
    void withInput() {
        assertGeneratedContentContains("operation/withInput", "String id = _iv_env.getArgument(\"id\")", "resolverFetchService.query(id)");
    }

    @Test
    @DisplayName("Basic root service with a context parameter")
    void withContextInput() {
        assertGeneratedContentContains(
                "operation/withContextInput",
                "graphCtx = _iv_env.getGraphQlContext()",
                "GraphitronContext _iv_graphitronContext = _iv_graphCtx.get(\"graphitronContext\")",
                "ctxField = _iv_graphitronContext.getContextArgument(_iv_env, \"ctxField\")",
                "query(_cf_ctxField)"
        );
    }

    @Test
    @DisplayName("TableMethod with context input")
    void tableMethodWithContextInput() {
        assertGeneratedContentContains("operation/contextTableMethod", "QueryDBQueries.customerForQuery(_iv_ctx, _cf_ctxField, _iv_selectionSet)");
    }

    @Test
    @DisplayName("Basic root service with both a context parameter and a standard parameter")
    void withContextInputAndArgument() {
        assertGeneratedContentContains("operation/withContextInputAndArgument", "query(i, _cf_ctxField)");
    }

    @Test
    @DisplayName("Service with both a context parameter and a pagination parameters")
    void withContextInputAndPagination() {
        assertGeneratedContentContains(
                "operation/withContextInputAndPagination",
                Set.of(DUMMY_CONNECTION),
                "query(_iv_pageSize, after, _cf_ctxField)",
                ".countQuery(_cf_ctxField)"
        );
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
                "in = ResolverHelpers.transformDTO(_iv_env.getArgument(\"in\"), CustomerInputTable.class)",
                "inRecord = _iv_transform.customerInputTableToJOOQRecord(in, \"in\")",
                "resolverFetchService.queryList(inRecord, _iv_pageSize, after",
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
                "String id = _iv_env.getArgument(\"id\")",
                "resolverFetchService.query(_iv_keys, id)"
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
                "inRecord = _iv_transform.customerInputTableToJOOQRecord(in, \"in\")",
                "resolverFetchService.queryMap(_iv_keys, inRecord, _iv_pageSize, after",
                "resolverFetchService.countQueryMap(_iv_keys, inRecord"
        );
    }

    // We had a bug where service dependencies would be applied to all subsequent methods generated by a method generator.
    @Test
    @DisplayName("Service followed by normal query")
    void queryAfterService() {
        assertGeneratedContentContains(
                "operation/queryAfterService",
                "var resolverFetchService",
                "queryNormal() {return _iv_env -> {return"
        );
    }
}
