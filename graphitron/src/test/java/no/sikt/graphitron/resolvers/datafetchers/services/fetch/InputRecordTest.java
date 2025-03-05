package no.sikt.graphitron.resolvers.datafetchers.services.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.FetchClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.RESOLVER_FETCH_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Fetch service resolvers - Resolvers that call custom services with records")
public class InputRecordTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "resolvers/datafetchers/fetch/services";
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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchClassGenerator(schema));
    }

    @Test
    @DisplayName("Root service including input Java records")
    void withInputJavaRecord() {
        assertGeneratedContentContains(
                "operation/withInputJavaRecord", Set.of(DUMMY_INPUT_RECORD),
                "inRecord = transform.dummyInputRecordToJavaRecord(in, \"in\")",
                "resolverFetchService.query(inRecord)"
        );
    }

    @Test
    @DisplayName("Root service including input jOOQ records")
    void withInputJOOQRecord() {
        assertGeneratedContentContains(
                "operation/withInputJOOQRecord", Set.of(CUSTOMER_INPUT_TABLE),
                "inRecord = transform.customerInputTableToJOOQRecord(in, \"in\")",
                "resolverFetchService.query(inRecord)"
        );
    }

    @Test
    @DisplayName("Service including input Java records")
    void withInputJavaRecordOnSplitQuery() {
        assertGeneratedContentContains(
                "splitquery/withInputJavaRecord", Set.of(SPLIT_QUERY_WRAPPER, DUMMY_INPUT_RECORD),
                "inRecord = transform.dummyInputRecordToJavaRecord(in, \"in\")",
                "resolverFetchService.query(ids, inRecord)"
        );
    }

    @Test
    @DisplayName("Service including input jOOQ records")
    void withInputJOOQRecordOnSplitQuery() {
        assertGeneratedContentContains(
                "splitquery/withInputJOOQRecord", Set.of(SPLIT_QUERY_WRAPPER, CUSTOMER_INPUT_TABLE),
                "inRecord = transform.customerInputTableToJOOQRecord(in, \"in\")",
                "resolverFetchService.query(ids, inRecord)"
        );
    }
}
