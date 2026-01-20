package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.UnionOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Query outputs - Union types")
public class UnionTest extends GeneratorTest {

    // Disable validation until GGG-104
    @Override
    protected boolean validateSchema() {
        return false;
    }

    @Override
    protected String getSubpath() {
        return "queries/fetch/union";
    }


    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new UnionOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Multitable union query")
    void multitableUnion() {
        assertGeneratedContentContains("multitableUnion",  Set.of(PAGE_INFO, SOMEUNION_CONNECTION),
                "languageSortFieldsForPaginatedUnionQuery"
        );
    }

    @Test
    @DisplayName("Multitable union with one type")
    void multitableUnionOneType() {
        assertGeneratedContentContains("multitableUnionOneType", Set.of(PAGE_INFO, SOMEUNION_CONNECTION),
                " languageForPaginatedUnionQuery()"
        );
    }

    @Test
    @DisplayName("Multitable union in splitQuery field")
    void multitableUnionSplitQuery() {
        assertGeneratedContentContains("multitableUnionSplitQuery",  Set.of(PAGE_INFO, SOMEUNION_CONNECTION),
                "staffAndCustomersForPayment"
        );
    }
}
