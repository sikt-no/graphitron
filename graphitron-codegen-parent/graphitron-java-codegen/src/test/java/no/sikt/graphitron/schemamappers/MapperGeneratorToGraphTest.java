package no.sikt.graphitron.schemamappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;

@DisplayName("Schema Mappers - Mapper content for mapping fields to graph types")
public class MapperGeneratorToGraphTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "schemamappers";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(MAPPER_ID_SERVICE, JAVA_RECORD_CUSTOMER, MAPPER_FETCH_SERVICE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, false));
    }

    @Test
    @DisplayName("Default case with simple fields")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Default case with listed fields")
    void listedFields() {
        assertGeneratedContentContains(
                "listedFields",
                "Response0 recordToGraphType(List<String> response0Record,",
                "Response1 recordToGraphType(List<String> response1Record,"
        );
    }

    @Test
    @DisplayName("Wrapper field containing listed Java record with splitQuery field")
    void splitQueryInNestedJavaRecord() {
        assertGeneratedContentContains(
                "splitQueryInNestedJavaRecord",
                " if (select.contains(pathHere + \"customerJavaRecords\")) {" +
                        "payload.setCustomerJavaRecords(transform.customerJavaRecordToGraphType(payloadRecord, pathHere + \"customerJavaRecords\"));}"
        );
    }

    @Test
    @DisplayName("Wrapper field containing listed jOOQ record with listed splitQuery field")
    void listedSplitQueryInNestedJavaRecord() {
        assertGeneratedContentContains(
                "listedSplitQueryInNestedJooqRecord",
                "customer.setAddressesKey(customerRecord.stream().map(internal_it_ -> DSL.row(internal_it_.getCustomerId())).toList());"
        );
    }
}
