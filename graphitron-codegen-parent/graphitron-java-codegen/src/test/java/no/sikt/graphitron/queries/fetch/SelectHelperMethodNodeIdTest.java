package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
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

@DisplayName("Helper methods containing nodeId")
public class SelectHelperMethodNodeIdTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/selectHelperMethodsNodeId";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(NODE);
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new SelectHelperDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Only ID for node query")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Split query with primary key")
    void splitQueryOnlyPrimaryKey() {
        assertGeneratedContentContains(
                "splitQueryOnlyPrimaryKey",
                "<Address> addressForCustomer_address",
                "(\"Address\", _a_customer_2168032777_address.fields(_a_customer_2168032777_address.getPrimaryKey().getFieldsArray()))).mapping(Functions.nullOnAllNull(Address:"
        );
    }
}
