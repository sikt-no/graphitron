package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.db.SelectHelperDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE_QUERY;

@DisplayName("Helper method generation and naming")
public class SelectHelperMethodNodeSelectionTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/selectHelperMethods";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new SelectHelperDBClassGenerator(schema));
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setUseOptionalSelects(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setUseOptionalSelects(false);
    }

    @Test
    @DisplayName("Node queries should accept SelectionSet and wrap subqueries with ifRequested")
    void selection() {
        assertGeneratedContentContains(
                "selection", Set.of(NODE, NODE_QUERY),
                "customerForNode_customer(SelectionSet _iv_select)",
                "ifRequested(\"address\""
        );
    }
}
