package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.MapOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;

@DisplayName("Mutation inputs - Input handling for mutation fetch queries")
public class MutationInputTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/edit";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new MapOnlyFetchDBClassGenerator(schema));
    }

    @Test
    @DisplayName("Mutation has multiple inputs but uses only the first ID")
    void multipleInputs() {
        assertGeneratedContentContains(
                "multipleInputs",
                "from(_customer)" +
                        ".where(_customer.hasIds(inRecordList.stream().map(it -> it.getId()).collect(Collectors.toSet())))" +
                        ".orderBy(orderFields)"
        );
    }

    @Test
    @DisplayName("Mutation has multiple ids but uses only the first ID")
    void multipleIDs() {
        assertGeneratedContentContains(
                "multipleIDs",
                "from(_customer)" +
                        ".where(_customer.hasIds(inRecordList.stream().map(it -> it.getId()).collect(Collectors.toSet())))" +
                        ".orderBy"
        );
    }
}
