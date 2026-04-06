package no.sikt.graphitron.rewrite.test;

import no.sikt.graphitron.rewrite.test.generated.rewrite.resolvers.CustomerConditions;
import no.sikt.graphitron.rewrite.test.jooq.Tables;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 4 integration tests for the generated {@code CustomerConditions} class.
 *
 * <p>Starts a real PostgreSQL container, applies the test database schema, and verifies that the
 * generated condition produces the correct result set when used in a jOOQ query.
 */
class CustomerConditionsTest {

    static final PostgreSQLContainer<?> DB =
        new PostgreSQLContainer<>("postgres:18")
            .withInitScript("init.sql");

    static DSLContext ctx;

    @BeforeAll
    static void setup() {
        DB.start();
        ctx = DSL.using(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    }

    @AfterAll
    static void teardown() {
        DB.stop();
    }

    @Test
    void activeTrue_returnsOnlyActiveCustomers() {
        var condition = CustomerConditions.conditions(true);
        var result = ctx.select(Tables.CUSTOMER.CUSTOMER_ID)
            .from(Tables.CUSTOMER)
            .where(condition)
            .fetch();

        assertThat(result).hasSize(3);
    }

    @Test
    void activeFalse_returnsOnlyInactiveCustomers() {
        var condition = CustomerConditions.conditions(false);
        var result = ctx.select(Tables.CUSTOMER.CUSTOMER_ID)
            .from(Tables.CUSTOMER)
            .where(condition)
            .fetch();

        assertThat(result).hasSize(2);
    }

    @Test
    void activeNull_returnsAllCustomers() {
        var condition = CustomerConditions.conditions(null);
        var result = ctx.select(Tables.CUSTOMER.CUSTOMER_ID)
            .from(Tables.CUSTOMER)
            .where(condition)
            .fetch();

        assertThat(result).hasSize(5);
    }
}
