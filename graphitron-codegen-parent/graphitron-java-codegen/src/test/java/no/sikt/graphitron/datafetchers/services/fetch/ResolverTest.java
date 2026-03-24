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

import static org.assertj.core.api.Assertions.assertThat;

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
        assertGeneratedContentContains("operation/withInput", "String _mi_id = _iv_env.getArgument(\"id\")", "resolverFetchService.query(_mi_id)");
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
        assertGeneratedContentContains("operation/withContextInputAndArgument", "query(_mi_i, _cf_ctxField)");
    }

    @Test
    @DisplayName("Service with both a context parameter and a pagination parameters")
    void withContextInputAndPagination() {
        assertGeneratedContentContains(
                "operation/withContextInputAndPagination",
                Set.of(DUMMY_CONNECTION),
                "query(_iv_pageSize, _mi_after, _cf_ctxField)",
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
                "inRecord = _iv_transform.customerInputTableToJOOQRecord(_mi_in, _iv_transform.getArgumentPresence().child(\"in\"), \"in\")",
                "resolverFetchService.queryList(_mi_inRecord, _iv_pageSize, _mi_after",
                "resolverFetchService.countQueryList(_mi_inRecord"
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
                "String _mi_id = _iv_env.getArgument(\"id\")",
                "resolverFetchService.query(_iv_keys, _mi_id)"
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
                "inRecord = _iv_transform.customerInputTableToJOOQRecord(_mi_in, _iv_transform.getArgumentPresence().child(\"in\"), \"in\")",
                "resolverFetchService.queryMap(_iv_keys, _mi_inRecord, _iv_pageSize, _mi_after",
                "resolverFetchService.countQueryMap(_iv_keys, _mi_inRecord"
        );
    }

    // We had a bug where service dependencies would be applied to all subsequent methods generated by a method generator.
    @Test
    @DisplayName("Service followed by normal query")
    void queryAfterService() {
        assertGeneratedContentContains(
                "operation/queryAfterService",
                "var _rs_resolverFetchService",
                "queryNormal() {return _iv_env -> {return"
        );
    }

    @Test
    @DisplayName("When optional field 'totalCount' is included in the schema for a field having a service and pagination, generate the count method")
    void serviceWithPaginationAndAllOptionalFieldsIncluded() {
        assertGeneratedContentContains(
                "operation/withPaginationAndAllOptionalFieldsIncluded",
                Set.of(CUSTOMER_CONNECTION),
                """
                .loadPaginated(
                _iv_pageSize,
                () -> _rs_resolverFetchService.queryList(_iv_pageSize, _mi_after),
                (_iv_keys) -> _rs_resolverFetchService.countQueryList(),
                (_iv_recordTransform,
                """
        );
    }

    @Test
    @DisplayName("When optional field 'totalCount' is not included in the schema for a field having a service and pagination, do not generate the count method")
    void serviceWithPaginationAndNoOptionalFieldsIncluded() {
        assertGeneratedContentContains(
                "operation/withPaginationAndNoOptionalFieldsIncluded",
                Set.of(CUSTOMER_CONNECTION_WITH_NO_OPTIONALS),
                """
                .loadPaginated(
                _iv_pageSize,
                () -> _rs_resolverFetchService.queryList(_iv_pageSize, _mi_after),
                (_iv_recordTransform, _iv_response) -> _iv_recordTransform.customerTableRecordToGraphType(_iv_response, "")
                );
                """
        );
    }

    @Test
    @DisplayName("Query service returning table type should auto-fetch from DB via DataFetcherHelper")
    void returningTableWithAutoFetch() {
        assertGeneratedContentContains(
                "operation/returningTableWithAutoFetch", Set.of(CUSTOMER_TABLE),
                "_iv_serviceResult.key().into(",
                "new DataFetcherHelper(_iv_env).load(_iv_serviceKey"
        );
    }

    @Test
    @DisplayName("Query service returning table type should NOT use record-to-graph transform")
    void returningTableWithAutoFetchShouldNotTransform() {
        resultDoesNotContain(
                "operation/returningTableWithAutoFetch", Set.of(CUSTOMER_TABLE),
                "customerTableRecordToGraphType"
        );
    }

    @Test
    @DisplayName("Listed service returning table type should use loadByResolverKeys for batch auto-fetch")
    void returningTableListWithAutoFetch() {
        assertGeneratedContentContains(
                "operation/returningTableListWithAutoFetch", Set.of(CUSTOMER_TABLE),
                "_iv_serviceResult.stream().map(_iv_r -> _iv_r.key().into(",
                "new DataFetcherHelper(_iv_env).loadByResolverKeys(_iv_serviceKeys"
        );
    }

    @Test
    @DisplayName("Listed service returning table type should NOT use record-to-graph transform")
    void returningTableListWithAutoFetchShouldNotTransform() {
        resultDoesNotContain(
                "operation/returningTableListWithAutoFetch", Set.of(CUSTOMER_TABLE),
                "customerTableRecordToGraphType"
        );
    }

    @Test
    @DisplayName("@table field on @record type returned by @service should be implicit @splitQuery")
    void implicitSplitQueryFromRecord() {
        var implicit = generateFiles("operation/implicitSplitQueryFromRecord", Set.of(CUSTOMER_TABLE, DUMMY_TYPE_RECORD));
        var explicit = generateFiles("operation/explicitSplitQueryFromRecord", Set.of(CUSTOMER_TABLE, DUMMY_TYPE_RECORD));
        assertThat(implicit).as("Implicit @splitQuery should generate same output as explicit @splitQuery").isEqualTo(explicit);
    }

    @Test
    @DisplayName("@table field on @record type nested inside a plain wrapper returned by @service should be implicit @splitQuery")
    void implicitSplitQueryFromWrappedRecord() {
        var implicit = generateFiles("operation/implicitSplitQueryFromWrappedRecord", Set.of(CUSTOMER_TABLE, DUMMY_TYPE_RECORD));
        var explicit = generateFiles("operation/explicitSplitQueryFromWrappedRecord", Set.of(CUSTOMER_TABLE, DUMMY_TYPE_RECORD));
        assertThat(implicit).as("Implicit @splitQuery through wrapper should generate same output as explicit @splitQuery").isEqualTo(explicit);
    }
}
