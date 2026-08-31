package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mutation queries - Query and conditions for inserting data without the node ID strategy")
public class InsertQueryWithoutNodeIdStrategyTest extends MutationQueryTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "insert";
    }

    @BeforeAll
    static void setUpWithoutNodeIdStrategy() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @AfterAll
    static void tearDownWithoutNodeIdStrategy() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @Test
    @DisplayName("With ID field mapped to a column")
    void idInputWithoutNodeIdStrategy() {
        assertGeneratedContentContains("idInputWithoutNodeIdStrategy",
                "insertInto(CUSTOMER, CUSTOMER.CUSTOMER_ID)",
                ".values(DSL.val(_mi_inRecord.getCustomerId()))"
        );
    }
}
