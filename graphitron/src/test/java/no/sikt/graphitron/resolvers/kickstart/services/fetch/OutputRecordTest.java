package no.sikt.graphitron.resolvers.kickstart.services.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.kickstart.fetch.FetchResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_RECORD;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.RESOLVER_FETCH_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.DUMMY_TYPE_RECORD;
import static no.sikt.graphitron.common.configuration.SchemaComponent.SPLIT_QUERY_WRAPPER;

@DisplayName("Fetch service resolvers - Resolvers that call custom services with records")
public class OutputRecordTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/kickstart/fetch/services";
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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema));
    }

    @Test
    @DisplayName("Root service returning Java records")
    void returningJavaRecord() {
        assertGeneratedContentContains(
                "operation/returningJavaRecord",
                "public CompletableFuture<DummyTypeRecord>",
                "resolverFetchService.queryJavaRecord(),",
                "recordTransform.dummyTypeRecordToGraphType(response, \"\")"
        );
    }

    @Test
    @DisplayName("Service returning Java records wrapped in a non-record graph type") // The naming of the methods may be a bit off here. This test just ensures the resolver uses the right one.
    void returningWrappedJavaRecord() {
        assertGeneratedContentContains(
                "operation/returningWrappedJavaRecord",
                "public CompletableFuture<Wrapper>",
                "resolverFetchService.queryJavaRecord(),",
                "recordTransform.wrapperRecordToGraphType(response, \"\")"
        );
    }

    @Test
    @DisplayName("Service returning Java records")
    void returningJavaRecordOnSplitQuery() {
        assertGeneratedContentContains(
                "splitquery/returningJavaRecord", Set.of(SPLIT_QUERY_WRAPPER),
                "public CompletableFuture<DummyTypeRecord>",
                "resolverFetchService.queryJavaRecord(ids)",
                "recordTransform.dummyTypeRecordToGraphType(response, \"\")"
        );
    }
}
