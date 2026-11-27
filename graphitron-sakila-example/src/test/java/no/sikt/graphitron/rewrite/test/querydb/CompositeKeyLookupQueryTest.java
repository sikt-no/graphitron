package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

/**
 * Execution-tier coverage for composite-key {@code @lookupKey} via {@code @table} input type.
 * Behavioural assertions on {@code filmActorsByKey([FilmActorKey!]!)} live alongside the rest
 * of the lookup-field coverage in {@code GraphQLQueryTest}; this class pins that both key
 * columns take part in the match: a key resolves only when {@code film_id} and
 * {@code actor_id} both match a row; and that a node id inside an input key decodes per field,
 * a null one answering null at its position and a bad one failing the field.
 */
@ExecutionTier
class CompositeKeyLookupQueryTest {

    @AutoClose
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() throws Exception {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private graphql.ExecutionResult executeRaw(String query) {
        return graphql.execute(Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build());
    }

    private static String customer(int id) {
        return NodeIdEncoder.encode("Customer", id);
    }

    private static String address(int id) {
        return NodeIdEncoder.encode("Address", id);
    }

    @Test
    void compositeKeyLookup_returnsEachRequestedPair() {
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 1, actorId: 2}, {filmId: 2, actorId: 3}]) { filmId actorId } }");

        assertThat(data).extractingByKey("filmActorsByKey", as(list(Map.class)))
            .hasSize(2)
            .extracting(r -> r.get("filmId") + ":" + r.get("actorId"))
            .containsExactly("1:2", "2:3");
    }

    @Test
    void compositeKeyLookup_subset_returnsOnlyMatchingPair() {
        // (film 2, actor 2) is NOT a real film_actor row; (film 1, actor 1) is. Both keys keep
        // their slot: the unmatched composite holds null at index 0 and the matched one lands at
        // index 1, which is the slot-per-key lookup contract on composite keys.
        //
        // The unmatched pair has to be one no other class writes. Test classes in this module run
        // concurrently against one database, and DmlBulkMutationsExecutionTest seeds real
        // film_actor rows for (1, 4), (2, 3), (3, 3) and (3, 4), removing them afterwards. Any of
        // those four read as matched for as long as that class holds them, which fails this test on
        // a row it never wrote. film_actor's seed is (1,1), (2,1), (1,2), (3,2), (1,3), (2,4),
        // (3,5), so (film 2, actor 2) is absent from the fixture and outside every seeded pair.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 2, actorId: 2}, {filmId: 1, actorId: 1}]) { filmId actorId } }");

        assertThat(data).extractingByKey("filmActorsByKey", as(LIST))
            .hasSize(2)
            .satisfies(rows -> assertThat(rows.get(0)).as("the unmatched composite keeps its slot").isNull())
            .element(1, as(MAP))
            .containsEntry("filmId", 1)
            .containsEntry("actorId", 1);
    }

    @Test
    void nodeIdKey_answersEachIdAtItsPosition() {
        // Reverse of key order, with an id that decodes but matches no customer in between.
        Map<String, Object> data = execute("{ customersByIdKey(key: [{id: \"" + customer(3) + "\"}, {id: \""
            + customer(999999) + "\"}, {id: \"" + customer(1) + "\"}]) { customerId } }");

        assertThat(data).extractingByKey("customersByIdKey", as(list(Map.class)))
            .extracting(r -> r == null ? null : r.get("customerId"))
            .containsExactly(3, null, 1);
    }

    @Test
    void nodeIdKey_singleKeyArgument_answersOneCustomer() {
        Map<String, Object> data = execute(
            "{ customerByIdKey(key: {id: \"" + customer(2) + "\"}) { customerId } }");

        assertThat(data).extractingByKey("customerByIdKey", as(MAP)).containsEntry("customerId", 2);
    }

    @Test
    void carriedNodeIdKey_matchesThroughTheForeignKeyColumn() {
        // Address 3 is Linda's (customer 3) alone; address 4 has no customer.
        Map<String, Object> data = execute("{ customersByAddressKey(key: [{address: \"" + address(3)
            + "\"}, {address: \"" + address(4) + "\"}]) { customerId } }");

        assertThat(data).extractingByKey("customersByAddressKey", as(list(Map.class)))
            .extracting(r -> r == null ? null : r.get("customerId"))
            .containsExactly(3, null);
    }

    @Test
    void carriedNodeIdKey_nullOrAbsentIdAnswersNullAtItsPosition() {
        Map<String, Object> data = execute("{ customersByAddressKey(key: [{address: null}, {address: \""
            + address(3) + "\"}, {}]) { customerId } }");

        assertThat(data).extractingByKey("customersByAddressKey", as(list(Map.class)))
            .as("a null and an absent id match nothing and keep their slots; the other key still matches")
            .extracting(r -> r == null ? null : r.get("customerId"))
            .containsExactly(null, 3, null);
    }

    @Test
    void mixedKey_matchesOnTheColumnAndTheCarriedId() {
        // Mary (customer 1) lives at address 1, not 2; Linda's key has no address.
        Map<String, Object> data = execute("{ customersByEmailAndAddress(key: ["
            + "{email: \"mary.smith@example.com\", address: \"" + address(1) + "\"}, "
            + "{email: \"mary.smith@example.com\", address: \"" + address(2) + "\"}, "
            + "{email: \"linda.williams@example.com\", address: null}]) { customerId } }");

        assertThat(data).extractingByKey("customersByEmailAndAddress", as(list(Map.class)))
            .extracting(r -> r == null ? null : r.get("customerId"))
            .containsExactly(1, null, null);
    }

    @Test
    void nodeIdKey_malformedId_failsWithTheDecodeClientError() {
        graphql.ExecutionResult result = executeRaw(
            "{ customersByIdKey(key: [{id: \"" + customer(1) + "\"}, {id: \"not-a-node-id\"}]) { customerId } }");

        assertThat(result.getErrors())
            .singleElement()
            .satisfies(e -> assertThat(e.getMessage())
                .contains("Invalid node id \"not-a-node-id\"")
                .contains("not a valid Customer id")
                .doesNotContain("Reference:"));
    }

    @Test
    void carriedNodeIdKey_idOfAnotherType_failsWithTheDecodeClientError() {
        String film = NodeIdEncoder.encode("Film", 1);
        graphql.ExecutionResult result = executeRaw(
            "{ customersByAddressKey(key: [{address: \"" + film + "\"}]) { customerId } }");

        assertThat(result.getErrors())
            .singleElement()
            .satisfies(e -> assertThat(e.getMessage())
                .contains("Invalid node id \"" + film + "\"")
                .contains("decodes to type \"Film\", expected a Address id")
                .doesNotContain("Reference:"));
    }

    @Test
    void compositeNodeIdKey_decodesBothKeyColumnsFromOneId() {
        // (actor 2, film 2) is no film_actor row and outside every pair DmlBulkMutationsExecutionTest
        // seeds; see compositeKeyLookup_subset_returnsOnlyMatchingPair.
        Map<String, Object> data = execute("{ filmActorsByIdKey(key: ["
            + "{id: \"" + NodeIdEncoder.encode("FilmActor", 3, 5) + "\"}, "
            + "{id: \"" + NodeIdEncoder.encode("FilmActor", 2, 2) + "\"}, "
            + "{id: \"" + NodeIdEncoder.encode("FilmActor", 2, 1) + "\"}]) { filmId actorId } }");

        assertThat(data).extractingByKey("filmActorsByIdKey", as(list(Map.class)))
            .extracting(r -> r == null ? null : r.get("actorId") + ":" + r.get("filmId"))
            .containsExactly("3:5", null, "2:1");
    }
}
