package no.sikt.graphitron.resolvers.datafetchers.standard.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.FetchClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_CONNECTION;
import static no.sikt.graphitron.common.configuration.SchemaComponent.SPLIT_QUERY_WRAPPER;

@DisplayName("Paginated resolvers - Resolvers with pagination")
public class ResolverPaginationTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/datafetchers/fetch/pagination";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchClassGenerator(schema));
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_CONNECTION);
    }

    @Test
    @DisplayName("Basic root resolver with no extra parameters")
    void defaultCase() {
        assertGeneratedContentMatches("operation/default");
    }

    @Test
    @DisplayName("Root resolver with an additional parameter")
    void withOtherInput() {
        assertGeneratedContentContains(
                "operation/withOtherInput",
                "Integer) _args.get(\"first\"",
                "String) _args.get(\"after\"",
                "String) _args.get(\"other\"",
                "queryForQuery(ctx, other, pageSize, after,"
        );
    }

    @Test
    @DisplayName("Basic resolver with no extra parameters")
    void splitQuery() {
        assertGeneratedContentMatches("splitquery/default", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Resolver with an additional parameter")
    void splitQueryWithOtherInput() {
        assertGeneratedContentContains(
                "splitquery/withOtherInput", Set.of(SPLIT_QUERY_WRAPPER),
                "Integer) _args.get(\"first\"",
                "String) _args.get(\"after\"",
                "String) _args.get(\"other\"",
                "queryForWrapper(ctx, ids, other, pageSize, after,"
        );
    }

    @Test
    @DisplayName("Root resolver returning multi-table interface")
    @Disabled("Disablet inntil GGG-79")
    void multitableInterface() {
        assertGeneratedContentContains("operation/multitableInterface",
                "CompletableFuture<TitledConnection>",
                "QueryDBQueries.titledForQuery("
        );
    }

    @Test
    @DisplayName("Root resolver returning a single table interface")
    void singleTableInterface() {
        assertGeneratedContentContains("operation/singleTableInterface",
                "CompletableFuture<AddressConnection>",
                "loadPaginated",
                "QueryDBQueries.addressForQuery("
        );
    }
}
