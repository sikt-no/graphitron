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

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.RESOLVER_FETCH_SERVICE;
import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.*;

@DisplayName("Fetch service resolvers - Resolvers that call custom services")
public class ResolverTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/services";
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
        assertGeneratedContentMatches("operation/withInput");
    }

    @Test
    @DisplayName("Root service with pagination")
    void withPagination() {
        assertGeneratedContentMatches("operation/withPagination", CUSTOMER_CONNECTION);
    }

    @Test
    @DisplayName("Root service with pagination and a record input")
    void withPaginationAndRecord() {
        assertGeneratedContentMatches("operation/withPaginationAndRecord", CUSTOMER_CONNECTION, CUSTOMER_INPUT_TABLE);
    }

    @Test
    @DisplayName("Basic service with no extra parameters")
    void splitQuery() {
        assertGeneratedContentMatches("splitquery/default", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Basic service with a parameter")
    void splitQueryWithInput() {
        assertGeneratedContentMatches("splitquery/withInput", SPLIT_QUERY_WRAPPER);
    }

    @Test
    @DisplayName("Service with pagination")
    void splitQueryWithPagination() {
        assertGeneratedContentMatches("splitquery/withPagination", SPLIT_QUERY_WRAPPER, CUSTOMER_CONNECTION);
    }

    @Test
    @DisplayName("Service with pagination and a record input")
    void splitQueryWithPaginationAndRecord() {
        assertGeneratedContentMatches("splitquery/withPaginationAndRecord", SPLIT_QUERY_WRAPPER, CUSTOMER_CONNECTION, CUSTOMER_INPUT_TABLE);
    }
}
