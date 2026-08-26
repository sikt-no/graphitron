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

    @Test
    @DisplayName("Object fields under a same-table wrapper are called by the names they are defined with")
    void sameTableWrapper() {
        assertGeneratedContentContains("sameTableWrapper",
                // The wrapper is inlined into the entity's row, so its own path segment is not part of the
                // names below and the children sit at the entity's depth.
                "DSL.select(_1_customerFor_Entity_customer_address())",
                "_1_customerFor_Entity_customer_address() {",
                "DSL.select(_1_customerFor_Entity_customer_store())",
                "_1_customerFor_Entity_customer_store() {"
        );
    }

    @Test
    @DisplayName("An inlined same-table wrapper gets no helper method of its own")
    void sameTableWrapperGetsNoHelper() {
        resultDoesNotContain("sameTableWrapper", "_customerFor_Entity_customer_profile");
    }
}
