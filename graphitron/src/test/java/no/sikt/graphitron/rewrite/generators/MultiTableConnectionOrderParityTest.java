package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.emit.RootQuery;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.PolymorphicOrdering;
import no.sikt.graphitron.rewrite.model.PolymorphicOrderingField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connection over several tables pages by a cursor encoding the columns its order sorts by, so a
 * client holding a cursor the walk's fetcher handed out reads its next page from the store's only
 * where both state the one order: the same key slots and order slots, each the same column per
 * participant at the same Java type, and the same orders on them. Compared on what the cursor and
 * the seek read, the participants by type.
 */
@PipelineTier
class MultiTableConnectionOrderParityTest {

    private static final String SCHEMA = """
        type Customer @table(name: "customer") { lastName: String @field(name: "last_name") }
        type Staff @table(name: "staff") { lastName: String @field(name: "last_name") }
        union Occupant = Customer | Staff
        enum OccupantSort {
            OCCUPANT_ID @order(primaryKey: true)
            LAST_NAME @order(fields: [{name: "last_name"}])
            STORE_THEN_NAME_DESC @order(fields: [{name: "store_id"}, {name: "last_name", direction: DESC}])
        }
        input OccupantOrderBy { field: OccupantSort!  direction: SortDirection }
        type Query {
            occupants: [Occupant!]! @asConnection
            occupantsByName: [Occupant!]! @asConnection @defaultOrder(fields: [{name: "last_name"}])
            occupantsChosen(orderBy: OccupantOrderBy @orderBy): [Occupant!]! @asConnection
                @defaultOrder(fields: [{name: "last_name"}])
            occupantsChosenList(orderBy: [OccupantOrderBy!] @orderBy): [Occupant!]! @asConnection
        }
        """;

    @ParameterizedTest
    @ValueSource(strings = {"occupants", "occupantsByName", "occupantsChosen", "occupantsChosenList"})
    void theStoreStatesTheOrderTheWalksCursorEncodes(String fieldName) {
        var walk = ((PolymorphicOrderingField) TestSchemaHelper.buildSchema(SCHEMA).field("Query", fieldName))
            .ordering().orElseThrow();
        var store = FactGraphs.inline().read("connection-order-parity", SCHEMA, CatalogInput.of(testContext()),
                TestSchemaHelper.testClasspath(), graph -> {
            var result = RootQuery.read(graph.handle()).get("Query." + fieldName);
            assertThat(result).as("the store's verdict on Query." + fieldName).isInstanceOf(RootQuery.MultiTable.class);
            var root = (RootQuery.MultiTable) result;
            var byType = new LinkedHashMap<String, ParticipantRef.TableBound>();
            root.branches().forEach(branch -> byType.put(branch.typeName(),
                new ParticipantRef.TableBound(branch.typeName(), branch.table(), null, List.of())));
            return TypeFetcherGenerator.orderingOf(root.ordered(), byType);
        });
        assertThat(cursorShape(store)).isEqualTo(cursorShape(walk));
    }

    /** What a cursor and its seek read of an ordering, participants named by type. */
    private static List<String> cursorShape(PolymorphicOrdering ordering) {
        var out = new java.util.ArrayList<String>();
        out.add("surface " + ordering.surface());
        ordering.key().forEach(slot -> out.add("key " + slotShape(slot)));
        ordering.slots().forEach(slot -> out.add("slot " + slotShape(slot)));
        ordering.namedOrders().forEach(order -> out.add("named " + order));
        out.add("base " + ordering.base());
        return out;
    }

    private static String slotShape(PolymorphicOrdering.Slot slot) {
        return slot.ordinal() + " " + slot.slotClass() + " " + slot.nullable() + " "
            + slot.columnByParticipant().entrySet().stream()
                .map(e -> e.getKey().typeName() + "=" + e.getValue().sqlName() + "/" + e.getValue().javaName())
                .collect(Collectors.joining(",", "[", "]"));
    }
}
