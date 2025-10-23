package no.sikt.graphitron.datafetchers.standard.fetch;

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

import static no.sikt.graphitron.common.configuration.ReferencedEntry.CONTEXT_CONDITION;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.JAVA_RECORD_CUSTOMER;
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
        return "datafetchers/fetch/standard";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_TYPE);
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(CONTEXT_CONDITION, JAVA_RECORD_CUSTOMER);
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
                "iN =", ".getArgument(\"IN\")", ", iN,"
        );
    }

    @Test
    @DisplayName("Input name for an input type starts with a capital letter")
    void wrongInputTypeCapitalisation() {
        assertGeneratedContentContains(
                "operation/wrongInputTypeCapitalisation",
                Set.of(DUMMY_INPUT),
                "iN =", ".getArgument(\"IN\")", ", iN,"
        );
    }

    @Test
    @DisplayName("Field with a condition referencing a context parameter")
    void withContextCondition() {
        assertGeneratedContentContains(
                "operation/withContextCondition",
                "ctxField = _iv_graphitronContext.getContextArgument(_iv_env, \"ctxField\")",
                "queryForQuery(_iv_ctx, _cf_ctxField, _iv_selectionSet)"
        );
    }

    @Test
    @DisplayName("Field with a argument condition referencing a context parameter")
    void withArgumentContextCondition() {
        assertGeneratedContentContains(
                "operation/withArgumentContextCondition",
                "ctxField = _iv_graphitronContext.getContextArgument(_iv_env, \"ctxField\")",
                "queryForQuery(_iv_ctx, email, _cf_ctxField, _iv_selectionSet)"
        );
    }

    @Test
    @DisplayName("Field with an input type field condition referencing a context parameter")
    void withInputTypeContextCondition() {
        assertGeneratedContentContains(
                "operation/withInputTypeContextCondition",
                "ctxField = _iv_graphitronContext.getContextArgument(_iv_env, \"ctxField\")",
                "queryForQuery(_iv_ctx, in, _cf_ctxField, _iv_selectionSet)"
        );
    }

    @Test // Note, these are sorted alphabetically.
    @DisplayName("Field with multiple conditions referencing context parameters")
    void withMultipleContextConditions() {
        assertGeneratedContentContains(
                "operation/withMultipleContextConditions",
                "ctxField1 = _iv_graphitronContext.getContextArgument(_iv_env, \"ctxField1\")",
                "ctxField2 = _iv_graphitronContext.getContextArgument(_iv_env, \"ctxField2\")",
                "queryForQuery(_iv_ctx, email, _cf_ctxField1, _cf_ctxField2, _iv_selectionSet)"
        );
    }

    @Test
    @DisplayName("Field with multiple conditions referencing the same context parameter")
    void withDuplicateContextField() {
        assertGeneratedContentContains(
                "operation/withDuplicateContextField",
                "ctxField = _iv_graphitronContext.getContextArgument(_iv_env, \"ctxField\")",
                "queryForQuery(_iv_ctx, email, _cf_ctxField, _iv_selectionSet)"
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

    @Test
    @DisplayName("SplitQuery field in Java record")
    void splitQueryFromJavaRecord() {
        assertGeneratedContentContains("splitquery/splitQueryFromJavaRecord",
                "load(",
                "addressForMyJavaRecord"
        );
    }

    @Test
    @DisplayName("Listed splitQuery field in Java record")
    void listedSplitQueryFromJavaRecord() {
        assertGeneratedContentContains("splitquery/listedSplitQueryFromJavaRecord",
                "return new DataFetcherHelper(_iv_env).loadByResolverKeys(myJavaRecord.getAddressKey()"
        );
    }
}
