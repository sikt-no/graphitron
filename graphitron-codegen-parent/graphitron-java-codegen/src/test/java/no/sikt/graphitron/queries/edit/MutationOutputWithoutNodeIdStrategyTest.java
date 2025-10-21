package no.sikt.graphitron.queries.edit;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Mutation queries - Data returned from mutations")
public class MutationOutputWithoutNodeIdStrategyTest extends MutationQueryTest {
    @Override
    protected String getSubpath() {
        return super.getSubpath() + "returningResult";
    }

    @BeforeAll
    static void setUp() {
        GeneratorConfig.setNodeStrategy(false);
    }

    @AfterAll
    static void tearDown() {
        GeneratorConfig.setNodeStrategy(true);
    }

    @Test
    @DisplayName("Returning id field")
    void id() {
        assertGeneratedContentContains("id",
                "String mutationForMutation",
                ".returningResult(CUSTOMER.getId())"
        );
    }

    @Test
    @DisplayName("Returning wrapped id field")
    void wrappedId() {
        assertGeneratedContentContains("wrappedId",
                "String mutationForMutation",
                ".returningResult(CUSTOMER.getId())"
        );
    }
}
