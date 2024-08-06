package no.fellesstudentsystem.graphitron_newtestorder.resolvers.services.fetch;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.RESOLVER_FETCH_SERVICE;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.DUMMY_TYPE_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.SPLIT_QUERY_WRAPPER;

@DisplayName("Fetch service resolvers - Resolvers that call custom services with records")
public class OutputRecordTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/services";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RESOLVER_FETCH_SERVICE, DUMMY_RECORD);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_TYPE_RECORD);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Root service returning Java records")
    void returningJavaRecord() {
        assertGeneratedContentContains(
                "operation/returningJavaRecord",
                "public CompletableFuture<DummyTypeRecord>",
                "resolverFetchService.queryJavaRecord(),",
                "transform.dummyTypeRecordToGraphType(response, \"\")"
        );
    }

    @Test
    @DisplayName("Service returning Java records wrapped in a non-record graph type") // The naming of the methods may be a bit off here. This test just ensures the resolver uses the right one.
    void returningWrappedJavaRecord() {
        assertGeneratedContentContains(
                "operation/returningWrappedJavaRecord",
                "public CompletableFuture<Wrapper>",
                "resolverFetchService.queryJavaRecord(),",
                "transform.wrapperRecordToGraphType(response, \"\")"
        );
    }

    @Test
    @DisplayName("Service returning Java records")
    void returningJavaRecordOnSplitQuery() {
        assertGeneratedContentContains(
                "splitquery/returningJavaRecord", Set.of(SPLIT_QUERY_WRAPPER),
                "public CompletableFuture<DummyTypeRecord>",
                "resolverFetchService.queryJavaRecord(ids)",
                "transform.dummyTypeRecordToGraphType(response, \"\")"
        );
    }
}
