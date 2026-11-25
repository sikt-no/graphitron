package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.emit.NodeIdHelperNames;
import no.sikt.graphitron.emit.RootQuery;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.NodeIdArgDispatch;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A root over several tables whose node-id argument its participants decode as different node types
 * guards the id before any branch reads, by the decoders the guard tries in turn: the store states the
 * argument, whether it takes a list, and each participant's decoder as the walk does, so the guard the
 * fetcher emits is the walk's.
 */
@PipelineTier
class MultiTableNodeIdDispatchParityTest {

    private static final String SCHEMA = """
        interface Node { id: ID! }
        type Customer implements Node @table(name: "customer") @node {
            id: ID! @nodeId
            lastName: String @field(name: "last_name")
        }
        type Staff implements Node @table(name: "staff") @node {
            id: ID! @nodeId
            lastName: String @field(name: "last_name")
        }
        type Address implements Node @table(name: "address") @node { id: ID! @nodeId }
        union Occupant = Customer | Staff
        type Query {
            occupant(id: ID! @nodeId): Occupant
            occupants(ids: [ID!] @nodeId): [Occupant!]!
            occupantsPage(ids: [ID!] @nodeId): [Occupant!]! @asConnection
            occupantsAt(addressId: [ID!] @nodeId(typeName: "Address")): [Occupant!]!
        }
        """;

    @ParameterizedTest
    @ValueSource(strings = {"occupant", "occupants", "occupantsPage", "occupantsAt"})
    void theStoreStatesTheDispatchTheWalksGuardReads(String fieldName) {
        var walk = ((QueryField.QueryUnionField) TestSchemaHelper.buildSchema(SCHEMA).field("Query", fieldName))
            .nodeIdArgDispatches().stream().map(MultiTableNodeIdDispatchParityTest::walkShape).toList();
        var store = FactGraphs.inline().read("node-id-dispatch-parity", SCHEMA, CatalogInput.of(testContext()),
                TestSchemaHelper.testClasspath(), graph -> {
            var result = RootQuery.read(graph.handle()).get("Query." + fieldName);
            assertThat(result).as("the store's verdict on Query." + fieldName).isInstanceOf(RootQuery.MultiTable.class);
            return ((RootQuery.MultiTable) result).dispatches().stream()
                .map(MultiTableNodeIdDispatchParityTest::storeShape).toList();
        });
        assertThat(store).isEqualTo(walk);
    }

    private static List<String> walkShape(NodeIdArgDispatch dispatch) {
        var out = new ArrayList<String>();
        out.add(dispatch.argName() + " list=" + dispatch.list());
        dispatch.decodeByParticipant().forEach((participant, decode) -> out.add(participant + " "
            + decode.methodName() + " " + decode.typeId() + " " + decode.table().tableName() + " "
            + decode.outputColumnShape().stream().map(ColumnRef::sqlName).toList()));
        return out;
    }

    private static List<String> storeShape(RootQuery.Dispatch dispatch) {
        var out = new ArrayList<String>();
        out.add(dispatch.argument() + " list=" + dispatch.list());
        dispatch.byParticipant().forEach((participant, decode) -> out.add(participant + " "
            + NodeIdHelperNames.decode(decode.nodeType()) + " " + decode.typeId() + " " + decode.table().tableName()
            + " " + decode.keyColumns().stream().map(ColumnRef::sqlName).toList()));
        return out;
    }
}
