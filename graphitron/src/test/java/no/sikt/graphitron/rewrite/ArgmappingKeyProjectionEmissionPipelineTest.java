package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.derive.ResolvedKeyProjections;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The plan's gate on a projected {@code argMapping} binding: a projection no wired emitter reads
 * stops the build rather than rendering as an ordinary nested read.
 *
 * <p>The store side is handed in, as in {@code KeyProjectionRelationTest}: what the view resolves is
 * the model module's to pin, and the end-to-end path from an authored {@code argMapping} through
 * capture to a running query is the execution tier's. The {@code @condition} site's emission is the
 * store's and is pinned there; the routine-write site is not asked here either:
 * {@code GraphQLQueryTest} runs each of its binding shapes against the database.
 */
@PipelineTier
class ArgmappingKeyProjectionEmissionPipelineTest {

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path tmp;

    /** The motivating shape: a {@code @nodeId} input field opened with the node type's key column. */
    private static final String SDL = """
        type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
            id: ID!
        }
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental, inventory: Inventory }
        input RentFilmInput {
            inventoryId: ID! @nodeId(typeName: "Inventory")
            customerId: Int!
        }
        type Mutation {
          rentFilm(input: RentFilmInput!): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId")
            @reference(path: [{table: "rental"}])
        }
        """;

    /**
     * A projection at a coordinate no wired emitter owns stops the build. The store-side deferral is
     * keyed on the directive and cannot see that one site's emitters are wired unevenly, so this is
     * the gate that keeps an unreached shape from rendering as an ordinary nested read: a base64 node
     * id handed to a routine parameter, which is the silence the family exists to close arriving
     * through the back door.
     */
    @Test
    void aProjectionNoEmitterOwnsStopsThePlan() {
        assertThatThrownBy(() -> TestSchemaHelper.storeBackedPlan(tmp, SDL,
            no.sikt.graphitron.common.configuration.TestConfiguration.testContext(),
            new ResolvedKeyProjections.Projections(List.of(
                inventoryProjection("Query", "rental", "input.inventoryId.inventory_id")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no emitter at that coordinate reads a projection")
            .hasMessageContaining("Query.rental");
    }

    // ===== The store rows these fixtures stand in for =====

    /*
     * Spelled by hand because TestSchemaHelper builds a model with no store behind it, and written to
     * be exactly what StoreNodeTables would have assembled from the fixture catalog: the same generated
     * class names, the same key order, the same type id (the fixtures declare no typeId:, so it is the
     * type's own name). That the real assembly agrees is StoreNodeTablesTest's claim against a captured
     * store; what these fixtures need is a row of the right shape so the plan below is the subject.
     */

    private static final ColumnRef INVENTORY_ID =
        new ColumnRef("inventory_id", "INVENTORY_ID", "java.lang.Long");

    /*
     * The trailing segment is the store's record of which resolution answered, and the fixtures
     * derive it the way the view does rather than taking it as a parameter: the authored arm's is the
     * path's own last segment, and the inferred arm has none. Deriving it here is what keeps a fixture
     * from spelling a combination the store cannot produce, an inferred row against a dotted-to-the-
     * column path being exactly the off-by-one these tests exist to catch.
     */
    private static ResolvedKeyProjections.Projection inventoryProjection(
            String typeName, String fieldName, String path) {
        return new ResolvedKeyProjections.Projection(typeName, fieldName, path,
            trailingSegmentOf(path, INVENTORY_ID), "Inventory", "Inventory",
            TestFixtures.tableRef("inventory", "INVENTORY", "Inventory", List.of(INVENTORY_ID)),
            List.of(INVENTORY_ID), INVENTORY_ID);
    }

    /** The path's last segment where it names {@code column}, and null where it does not. */
    private static String trailingSegmentOf(String path, ColumnRef column) {
        String last = path.substring(path.lastIndexOf('.') + 1);
        return last.equalsIgnoreCase(column.sqlName()) ? last : null;
    }
}
