package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.FetchTableRecordOnlyDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;

public class FetchTableRecordTest extends GeneratorTest {

    @Override
    protected String getSubpath() {
        return "queries/fetch/tableRecord";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new FetchTableRecordOnlyDBClassGenerator(schema));
    }

    @BeforeEach
    void setUp() {
        GeneratorConfig.setGenerateUpsertAsStore(true);
    }

    @AfterEach
    void tearDown() {
        GeneratorConfig.setGenerateUpsertAsStore(false);
    }

    @Test
    @DisplayName("Default case")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("When multiple UPSERT mutations exist, one record fetching method should be generated for each table")
    void multipleTables() {
        assertGeneratedContentContains("multipleTables",
                " fetchAddressRecords(",
                " fetchCustomerRecords("
        );
    }
}
