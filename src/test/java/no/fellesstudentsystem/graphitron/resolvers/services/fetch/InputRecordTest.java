package no.fellesstudentsystem.graphitron.resolvers.services.fetch;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.fetch.FetchResolverClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.common.configuration.ReferencedEntry.RESOLVER_FETCH_SERVICE;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch service resolvers - Resolvers that call custom services with records")
public class InputRecordTest extends GeneratorTest {
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
    @DisplayName("Root service including input Java records")
    void withInputJavaRecord() {
        assertGeneratedContentContains(
                "operation/withInputJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "query(DummyInputRecord in,",
                "inRecord = transform.dummyInputRecordToJavaRecord(in, \"in\")",
                "resolverFetchService.query(inRecord)"
        );
    }

    @Test
    @DisplayName("Root service including input jOOQ records")
    void withInputJOOQRecord() {
        assertGeneratedContentContains(
                "operation/withInputJOOQRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "query(CustomerInputTable in,",
                "inRecord = transform.customerInputTableToJOOQRecord(in, \"in\")",
                "resolverFetchService.query(inRecord)"
        );
    }

    @Test
    @DisplayName("Service including input Java records")
    void withInputJavaRecordOnSplitQuery() {
        assertGeneratedContentContains(
                "splitquery/withInputJavaRecord", Set.of(SPLIT_QUERY_WRAPPER, DUMMY_INPUT_RECORD),
                "query(Wrapper wrapper, DummyInputRecord in,",
                "inRecord = transform.dummyInputRecordToJavaRecord(in, \"in\")",
                "resolverFetchService.query(ids, inRecord)"
        );
    }

    @Test
    @DisplayName("Service including input jOOQ records")
    void withInputJOOQRecordOnSplitQuery() {
        assertGeneratedContentContains(
                "splitquery/withInputJOOQRecord", Set.of(SPLIT_QUERY_WRAPPER, CUSTOMER_INPUT_TABLE),
                "query(Wrapper wrapper, CustomerInputTable in,",
                "inRecord = transform.customerInputTableToJOOQRecord(in, \"in\")",
                "resolverFetchService.query(ids, inRecord)"
        );
    }
}
