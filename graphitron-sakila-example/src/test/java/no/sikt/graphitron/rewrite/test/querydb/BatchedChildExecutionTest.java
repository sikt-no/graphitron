package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Batched children whose rows come back through a scatter: the batched pivot, the service table
 * lift, and the polymorphic child as a list, a connection, and a {@code loadMany} over an
 * accessor-many record parent. Each asserts its data per parent and the statement count that
 * shows the child ran once for the whole batch.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class BatchedChildExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger STATEMENT_COUNT = new AtomicInteger();

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    STATEMENT_COUNT.incrementAndGet();
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }


    @Test
    void pivotChild_eachParentGetsItsOwnRecord_rowLessParentGetsNullSlots() {
        // Film 1 has nno and nob titles; film 2 has no film_translation rows.
        STATEMENT_COUNT.set(0);
        var films = list(execute("{ filmById(film_id: [\"1\", \"2\"]) { titleTranslationsSplit { nn nb } } }"),
            "filmById");
        assertThat(STATEMENT_COUNT.get()).as("the parent lookup and one batched pivot statement").isEqualTo(2);
        assertThat(films).hasSize(2);
        assertThat((Map<String, Object>) films.get(0).get("titleTranslationsSplit"))
            .containsOnly(entry("nn", "AKADEMIET DINOSAUR (nynorsk)"), entry("nb", "AKADEMIET DINOSAUR"));
        assertThat((Map<String, Object>) films.get(1).get("titleTranslationsSplit"))
            .as("a record of null slots, not a null record")
            .isNotNull()
            .containsOnly(entry("nn", null), entry("nb", null));
    }

    @Test
    void serviceTableLift_keepsTheServicesOrder() {
        STATEMENT_COUNT.set(0);
        var films = list(execute("{ filmById(film_id: [\"1\"]) { castMembers { actorId } } }"), "filmById");
        assertThat(STATEMENT_COUNT.get())
            .as("the parent lookup, the service's own statement, and one re-projection statement")
            .isEqualTo(3);
        assertThat(films).singleElement()
            .extracting(f -> (List<Map<String, Object>>) f.get("castMembers"))
            .satisfies(cast -> assertThat(cast).extracting(c -> c.get("actorId")).containsExactly(1, 2));
    }

    @Test
    void polymorphicListChild_returnsTheAddresssCustomers_andSkipsTheEmptyStaffStage() {
        // Address 1 holds customers 1 and 4 and no staff.
        STATEMENT_COUNT.set(0);
        var data = execute("{ customerByPk(customerId: 1) { addressSplit { occupants { "
            + "__typename ... on Customer { customerId } ... on Staff { staffId } } } } }");
        assertThat(STATEMENT_COUNT.get())
            .as("customer, addressSplit batch, stage-1 union, Customer stage-2; a fifth would be "
                + "the Staff stage-2 running with no keys")
            .isEqualTo(4);
        var occupants = (List<Map<String, Object>>) addressSplit(data).get("occupants");
        assertThat(occupants).allSatisfy(o -> assertThat(o.get("__typename")).isEqualTo("Customer"));
        assertThat(occupants).extracting(o -> o.get("customerId")).containsExactlyInAnyOrder(1, 4);
    }

    @Test
    void polymorphicConnectionChild_pagesTheAddresssOccupantsInKeyOrder() {
        STATEMENT_COUNT.set(0);
        var data = execute("{ customerByPk(customerId: 1) { addressSplit { occupantsConnection(first: 2) { "
            + "nodes { __typename ... on Customer { customerId } ... on Staff { staffId } } } } } }");
        assertThat(STATEMENT_COUNT.get())
            .as("customer, addressSplit batch, ranked stage-1 page, Customer stage-2")
            .isEqualTo(4);
        var connection = (Map<String, Object>) addressSplit(data).get("occupantsConnection");
        assertThat((List<Map<String, Object>>) connection.get("nodes"))
            .extracting(n -> n.get("__typename"), n -> n.get("customerId"))
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("Customer", 1),
                org.assertj.core.groups.Tuple.tuple("Customer", 4));
    }

    @Test
    void polymorphicLoadManyChild_eachPayloadGetsItsAddressesOccupants() {
        // OccupantsBatchPayloadService builds two payloads in Java, over addresses [1, 2] and [3].
        // Address 1: customers 1, 4. Address 2: customers 2, 5 and staff 2. Address 3: customer 3
        // and staff 1.
        STATEMENT_COUNT.set(0);
        var payloads = list(execute("{ occupantsBatch { occupants { "
            + "__typename ... on Customer { customerId } ... on Staff { staffId } } } }"), "occupantsBatch");
        assertThat(STATEMENT_COUNT.get())
            .as("the service runs no SQL: one stage-1 union, then one stage-2 per typename")
            .isEqualTo(3);
        assertThat(payloads).hasSize(2);
        assertThat(occupantKeys(payloads.get(0)))
            .containsExactlyInAnyOrder("Customer:1", "Customer:4", "Customer:2", "Customer:5", "Staff:2");
        assertThat(occupantKeys(payloads.get(1)))
            .containsExactlyInAnyOrder("Customer:3", "Staff:1");
    }

    private static List<String> occupantKeys(Map<String, Object> payload) {
        return ((List<Map<String, Object>>) payload.get("occupants")).stream()
            .map(o -> o.get("__typename") + ":"
                + ("Customer".equals(o.get("__typename")) ? o.get("customerId") : o.get("staffId")))
            .toList();
    }

    private static Map<String, Object> addressSplit(Map<String, Object> data) {
        return (Map<String, Object>) ((Map<String, Object>) data.get("customerByPk")).get("addressSplit");
    }

    private static List<Map<String, Object>> list(Map<String, Object> data, String field) {
        return (List<Map<String, Object>>) data.get(field);
    }

    private static Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
