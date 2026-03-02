package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.EntityOnlyHelperDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION_QUERY;

@DisplayName("Helper method generation and naming for entity queries")
public class SelectHelperMethodEntityTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/selectHelperMethodsEntity";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new EntityOnlyHelperDBClassGenerator(schema));
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(FEDERATION_QUERY);
    }


    @Test
    @DisplayName("Entity type that has nested reference and thus generates multiple helper methods")
    void defaultCase() {
        assertGeneratedContentContains("default",
                "customerFor_Entity_customer() {",
                "DSL.multiset(DSL.select(_1_customerFor_Entity_customer_addresses())",
                "_1_customerFor_Entity_customer_addresses() {"
        );
    }
}
