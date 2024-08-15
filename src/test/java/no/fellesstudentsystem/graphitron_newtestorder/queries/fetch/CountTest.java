package no.fellesstudentsystem.graphitron_newtestorder.queries.fetch;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphitron_newtestorder.reducedgenerators.CountOnlyFetchDBClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent.CUSTOMER_CONNECTION;

@DisplayName("Query pagination - Count methods for paginated queries")
public class CountTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "queries/fetch/count";
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_CONNECTION);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new CountOnlyFetchDBClassGenerator(schema)); // Note that this reuses most of the code from normal queries.
    }

    @Test
    @DisplayName("Connection with no other fields") // TODO: No need to generate total count if the field does not exist in the connection.
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Connection with an extra field")
    void withOtherField() {
        assertGeneratedContentContains("withOtherField", ", String email)", ".where(CUSTOMER.EMAIL.eq(email)");
    }
}
