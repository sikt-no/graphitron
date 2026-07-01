package no.sikt.graphitron.rewrite.test.internal;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R367 execution-tier coverage for the single-cardinality multi-table polymorphic child fetcher on
 * a record-backed (Pojo) parent. {@code Query.addressOccupantCarrier} returns a plain Java record
 * (a {@code PojoResultType}) holding an {@code AddressRecord} hub; its single-cardinality
 * {@code firstOccupant: AddressOccupant} child resolves the first {@code Customer|Staff} whose
 * {@code address_id} matches the hub.
 *
 * <p>This exercises the R367 record-backed-parent arm of
 * {@code MultiTablePolymorphicEmitter.buildScalarPerParentFetcher}: the fetcher binds
 * {@code parentRecord} to the carrier's {@code address()} accessor return (the hub
 * {@code AddressRecord}) rather than casting {@code env.getSource()} to a jOOQ {@code Record} (which
 * would {@code ClassCastException} on the Pojo carrier), then reads {@code address_id} off it to
 * correlate the stage-1 {@code Customer}/{@code Staff} branches. Single cardinality returns the
 * stage-1 union's first row by sort order (raw PK ascending).
 *
 * <p>Sakila fixture data: address_id 3 has staff 1 (Mike Hillyer, staff_id 1) and customer 3 (Linda
 * Williams, customer_id 3); the union sorts by raw PK, so the first occupant is the Staff row.
 * address_id 4 has the hub row but no occupants, so the child resolves to null.
 */
@ExecutionTier
class AddressOccupantCarrierSingleCardinalityTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @SuppressWarnings("unchecked")
    @Test
    void firstOccupant_recordBackedParent_resolvesFirstPolymorphicRowBySortOrder() {
        Map<String, Object> data = execute("""
            { addressOccupantCarrier(addressId: 3) {
                firstOccupant {
                  __typename
                  ... on Staff    { staffId firstName lastName }
                  ... on Customer { customerId }
                }
              }
            }
            """);

        var carrier = (Map<String, Object>) data.get("addressOccupantCarrier");
        var occupant = (Map<String, Object>) carrier.get("firstOccupant");
        assertThat(occupant)
            .as("address_id 3's first occupant by PK sort is staff_id 1 (Mike Hillyer), ahead of "
                + "customer_id 3")
            .containsEntry("__typename", "Staff")
            .containsEntry("staffId", 1)
            .containsEntry("firstName", "Mike")
            .containsEntry("lastName", "Hillyer");
    }

    @SuppressWarnings("unchecked")
    @Test
    void firstOccupant_recordBackedParentWithNoOccupants_resolvesNull() {
        Map<String, Object> data = execute("""
            { addressOccupantCarrier(addressId: 4) {
                firstOccupant { __typename }
              }
            }
            """);

        // address_id 4 is seeded occupant-free (init.sql), so byId(4) returns a NON-NULL carrier
        // (the hub exists). This is the case that matters: it drives the empty-stage-1
        // null-payload arm (result.length == 0 ? null in buildScalarPerParentFetcher), not the
        // null-carrier short-circuit. The carrier must be present and firstOccupant null.
        var carrier = (Map<String, Object>) data.get("addressOccupantCarrier");
        assertThat(carrier)
            .as("address_id 4 is a non-null hub with no Customer/Staff occupants")
            .isNotNull()
            .containsKey("firstOccupant");
        assertThat(carrier.get("firstOccupant"))
            .as("empty stage-1 over the occupant-free hub resolves the child to null")
            .isNull();
    }
}
