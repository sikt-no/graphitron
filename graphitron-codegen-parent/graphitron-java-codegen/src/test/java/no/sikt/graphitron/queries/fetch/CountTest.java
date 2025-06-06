package no.sikt.graphitron.queries.fetch;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.reducedgenerators.CountOnlyFetchDBClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_CONNECTION;
import static no.sikt.graphitron.common.configuration.SchemaComponent.SOMEUNION_CONNECTION;

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
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
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
        assertGeneratedContentContains("withOtherField", ", String email)", ".where(_customer.EMAIL.eq(email)");
    }

    @Test
    @DisplayName("Connection on interface with discriminator (in same table)")
    void singleTableInterface() {
        assertGeneratedContentMatches("singleTableInterface");
    }

    @Test
    @DisplayName("Connection on multitable interface")
    void multiTableInterface() {
        assertGeneratedContentMatches("multiTableInterface");
    }

    @Test
    @DisplayName("Connection on multitable interface with an extra field")
    void multiTableInterfaceWithOtherField() {
        assertGeneratedContentContains("multiTableInterfaceWithOtherField",
                ", String customerId){",
                ".from(_paymentp2007_01).where(_paymentp2007_01.CUSTOMER_ID.eq(customerId))"
        );
    }

    @Test
    @DisplayName("Connection on union")
    void unionCountTest() {
        assertGeneratedContentContains("unionConnection",
                Set.of(SOMEUNION_CONNECTION),
                ".from(_paymentp2007_01)",
                "from(_paymentp2007_02)");
    }
}
