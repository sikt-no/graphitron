package no.fellesstudentsystem.graphitron_newtestorder.resolvers.services;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.TestComponent;
import no.fellesstudentsystem.graphitron_newtestorder.dummygenerators.DummyTransformerClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.RESOLVER_FETCH_SERVICE;
import static no.fellesstudentsystem.graphitron_newtestorder.TestComponent.*;

@DisplayName("Fetch service resolvers - Resolvers that call custom services with records")
public class FetchServiceResolverRecordTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/fetch/services";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RESOLVER_FETCH_SERVICE);
    }

    @Override
    protected Set<TestComponent> getComponents() {
        return makeComponents(CUSTOMER);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchResolverClassGenerator(schema), new DummyTransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("Root service including input Java records")
    void withInputJavaRecord() {
        assertGeneratedContentMatches("operation/withInputJavaRecord", DUMMY_INPUT_RECORD);
    }

    @Test
    @DisplayName("Root service including input jOOQ records")
    void withInputJOOQRecord() {
        assertGeneratedContentMatches("operation/withInputJOOQRecord", CUSTOMER_INPUT_TABLE);
    }

    @Test
    @DisplayName("Service including input Java records")
    void withInputJavaRecordOnSplitQuery() {
        assertGeneratedContentMatches("splitquery/withInputJavaRecord", SPLIT_QUERY_WRAPPER, DUMMY_INPUT_RECORD);
    }

    @Test
    @DisplayName("Service including input jOOQ records")
    void withInputJOOQRecordOnSplitQuery() {
        assertGeneratedContentMatches("splitquery/withInputJOOQRecord", SPLIT_QUERY_WRAPPER, CUSTOMER_INPUT_TABLE);
    }
}
