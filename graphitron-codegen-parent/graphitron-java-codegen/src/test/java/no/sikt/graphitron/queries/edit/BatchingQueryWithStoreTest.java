package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.UpdateOnlyDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_INPUT_TABLE;

@DisplayName("Mutation queries - Queries for updating data with JDBC batching")
public class BatchingQueryWithStoreTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit/withBatching";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new UpdateOnlyDBClassGenerator(schema));
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
    @DisplayName("Upsert as store")
    void upsertAsStore() {
        assertGeneratedContentContains("upsert", ".batchStore(_mi_inRecord)");
    }
}
