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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for multi-table polymorphic children under an {@code Outcome} payload
 * (a payload carrying a {@code WrapperArm} errors field), the tier that would have caught the
 * {@code ClassCastException} every such child used to throw on every request: the fetchers read
 * the parent off {@code env.getSource()} while the source is the {@code Outcome} wrapper. Both
 * parent backings are covered on both arms:
 *
 * <ul>
 *   <li>{@code Query.occupantsWithErrors}: a Pojo payload with a typed hub accessor
 *       ({@code address()} returning the {@code AddressRecord}), holding a single-valued and a
 *       batched list polymorphic child ({@code AddressOccupant = Customer | Staff}).</li>
 *   <li>{@code Query.occupantsRecordWithErrors}: the payload backed by the {@code AddressRecord}
 *       itself (the {@code KeyLift.FkColumns} lift under the wrapper), holding a single-valued
 *       child and an {@code @asConnection} child.</li>
 * </ul>
 *
 * <p>The success arm resolves the children against the fixture rows (address_id 3 has staff 1,
 * Mike Hillyer, and customer 3, Linda Williams; the stage-1 union sorts by raw PK so the Staff
 * row comes first); the error arm (the reserved address_id 999 throws the mapped exception)
 * renders every child null while the sibling errors field carries the error list.
 */
@ExecutionTier
class OutcomePolymorphicChildExecutionTest {

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
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    // ===== Pojo payload with typed hub accessor =====

    @Test
    @SuppressWarnings("unchecked")
    void pojoPayload_successArm_resolvesBothPolymorphicChildren() {
        var data = execute("""
            { occupantsWithErrors(addressId: 3) {
                firstOccupant {
                  __typename
                  ... on Staff    { staffId firstName }
                  ... on Customer { customerId firstName }
                }
                occupants {
                  __typename
                  ... on Staff    { staffId firstName }
                  ... on Customer { customerId firstName }
                }
                errors { ... on OccupantLookupMissingAddress { message } }
              }
            }
            """);

        var payload = (Map<String, Object>) data.get("occupantsWithErrors");
        var first = (Map<String, Object>) payload.get("firstOccupant");
        assertThat(first)
            .as("single-valued child reads the hub off Outcome.Success.value(): address 3's "
                + "first occupant by PK sort is staff 1")
            .containsEntry("__typename", "Staff")
            .containsEntry("staffId", 1)
            .containsEntry("firstName", "Mike");
        var occupants = (List<Map<String, Object>>) payload.get("occupants");
        assertThat(occupants)
            .as("batched list child delivers both occupants of address 3 on the success arm")
            .hasSize(2);
        assertThat(occupants.get(0))
            .containsEntry("__typename", "Staff")
            .containsEntry("staffId", 1);
        assertThat(occupants.get(1))
            .containsEntry("__typename", "Customer")
            .containsEntry("customerId", 3)
            .containsEntry("firstName", "Linda");
        assertThat(payload.get("errors"))
            .as("no error on the success arm")
            .isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pojoPayload_errorArm_rendersChildrenNullAndErrorsPopulated() {
        var data = execute("""
            { occupantsWithErrors(addressId: 999) {
                firstOccupant { __typename }
                occupants { __typename }
                errors { ... on OccupantLookupMissingAddress { message } }
              }
            }
            """);

        var payload = (Map<String, Object>) data.get("occupantsWithErrors");
        assertThat(payload.get("firstOccupant"))
            .as("the single-valued child resolves null on the ErrorList arm")
            .isNull();
        assertThat(payload.get("occupants"))
            .as("the batched list child resolves null on the ErrorList arm")
            .isNull();
        var errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("message", "address 999 not found");
    }

    // ===== Payload backed by the jOOQ record itself =====

    @Test
    @SuppressWarnings("unchecked")
    void recordPayload_successArm_resolvesSingleChildAndConnection() {
        var data = execute("""
            { occupantsRecordWithErrors(addressId: 3) {
                occupant {
                  __typename
                  ... on Staff    { staffId firstName }
                  ... on Customer { customerId firstName }
                }
                occupantsConnection {
                  totalCount
                  nodes {
                    __typename
                    ... on Staff    { staffId }
                    ... on Customer { customerId }
                  }
                }
                errors { ... on OccupantLookupMissingAddress { message } }
              }
            }
            """);

        var payload = (Map<String, Object>) data.get("occupantsRecordWithErrors");
        var occupant = (Map<String, Object>) payload.get("occupant");
        assertThat(occupant)
            .as("single-valued child on the record-backed payload reads the FkColumns key off "
                + "Outcome.Success.value()")
            .containsEntry("__typename", "Staff")
            .containsEntry("staffId", 1)
            .containsEntry("firstName", "Mike");
        var conn = (Map<String, Object>) payload.get("occupantsConnection");
        assertThat(conn.get("totalCount")).isEqualTo(2);
        var nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0)).containsEntry("__typename", "Staff").containsEntry("staffId", 1);
        assertThat(nodes.get(1)).containsEntry("__typename", "Customer").containsEntry("customerId", 3);
        assertThat(payload.get("errors")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordPayload_errorArm_rendersChildrenNullAndErrorsPopulated() {
        var data = execute("""
            { occupantsRecordWithErrors(addressId: 999) {
                occupant { __typename }
                occupantsConnection { totalCount }
                errors { ... on OccupantLookupMissingAddress { message } }
              }
            }
            """);

        var payload = (Map<String, Object>) data.get("occupantsRecordWithErrors");
        assertThat(payload.get("occupant"))
            .as("the single-valued child resolves null on the ErrorList arm")
            .isNull();
        assertThat(payload.get("occupantsConnection"))
            .as("the connection child resolves null on the ErrorList arm")
            .isNull();
        var errors = (List<Map<String, Object>>) payload.get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).containsEntry("message", "address 999 not found");
    }
}
