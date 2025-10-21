package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphitron.reducedgenerators.UpdateOnlyDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Mutation queries - Queries for updating data")
public class MutationQueryTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit/";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_NODE, CUSTOMER_NODE_INPUT_TABLE, CUSTOMER_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new UpdateOnlyDBClassGenerator(schema),
                new MapOnlyFetchDBClassGenerator(schema) // Temporarily included to make sure we don't generate two methods for mutations without JDBC batching
        );
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setUseJdbcBatchingForDeletes(false);
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setUseJdbcBatchingForDeletes(true);
        GeneratorConfig.setNodeStrategy(false);
    }
}
