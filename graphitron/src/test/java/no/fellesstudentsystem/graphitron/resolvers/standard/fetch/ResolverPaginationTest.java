package no.fellesstudentsystem.graphitron.resolvers.standard.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.DUMMY_CONNECTION;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.SPLIT_QUERY_WRAPPER;

@DisplayName("Paginated resolvers - Resolvers with pagination")
public class ResolverPaginationTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/pagination";
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
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
                "query(Integer first, String after, String other,",
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
                "query(Wrapper wrapper, Integer first, String after, String other,",
                "queryForWrapper(ctx, ids, other, pageSize, after,"
        );
    }
}
