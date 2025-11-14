package no.sikt.graphitron.datafetchers.services.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_RECORD;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.RESOLVER_FETCH_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Temporary test class for testing node strategy with services")
public class StrategyNodeServiceTest extends GeneratorTest {
    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Override
    protected String getSubpath() {
        return "datafetchers/fetch/services/nodestrategy";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(RESOLVER_FETCH_SERVICE, DUMMY_RECORD);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(DUMMY_TYPE_RECORD, CUSTOMER_NODE_INPUT_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new OperationClassGenerator(schema)
        );
    }

    @Test
    @DisplayName("Service resolver with node strategy enabled")
    void resolver() {
        assertGeneratedContentContains("default",
                "customerNodeRecordToGraphType(_iv_response, _iv_nodeIdStrategy, \"\")"
        );
    }

    @Test
    @DisplayName("Root service including input jOOQ records")
    void withInputJOOQRecord() {
        assertGeneratedContentContains(
                "withInputJOOQRecord",
                ".customerNodeInputTableToJOOQRecord(_mi_in, _iv_nodeIdStrategy, \"in\")",
                ".customerNodeRecordToGraphType(_iv_response, _iv_nodeIdStrategy, \"\")"
        );
    }
}
