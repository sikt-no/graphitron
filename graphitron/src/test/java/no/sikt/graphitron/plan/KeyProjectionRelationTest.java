package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.KeyProjection;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.derive.ResolvedKeyProjections;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The join {@link KeyProjectionCommands} performs: the store says which coordinate projects which
 * column of which node type, the walked model says what decoding that node type costs, and a relation
 * row is the two put together.
 *
 * <p>The store side is handed in rather than captured, which keeps the cases about the join. What the
 * view resolves is pinned by the model module's own suite, and that the whole path from an authored
 * {@code argMapping} to an emitted read holds is pinned by the emission tier; here the subject is the
 * step between them, including the two ways it can find the store and the model disagreeing. Those two
 * throws are the reason this producer is worth a test of its own: they are the only places in the
 * family where one resolution is checked against another, and a silent fallback in either would emit
 * the raw wire value the item exists to stop.
 */
@PipelineTier
class KeyProjectionRelationTest {

    private static final String SDL = """
        type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
            id: ID!
        }
        type FilmActor implements Node @table(name: "film_actor") @node(keyColumns: ["actor_id", "film_id"]) {
            id: ID!
        }
        type Film @table(name: "film") { title: String }
        type Query { inventory: Inventory, filmActor: FilmActor, film: Film }
        """;

    private static GraphitronSchema model;

    @BeforeAll
    static void buildModel() {
        model = TestSchemaHelper.buildBundle(SDL).model();
    }

    /**
     * The ordinary row. What the emitter reads off it is the decode helper, the table whose record
     * that helper materialises, and the projected column as a {@code ColumnRef} rather than a name,
     * which is what lets the emitted read name the column instead of indexing a tuple.
     */
    @Test
    void aResolvedProjectionCarriesTheDecodeItsTableAndTheNamedColumn() {
        var row = only(projection("Mutation", "rentFilm", "input.inventoryId.inventory_id",
            "Inventory", "inventory_id"));

        assertThat(row.coordinate().getTypeName()).isEqualTo("Mutation");
        assertThat(row.coordinate().getFieldName()).isEqualTo("rentFilm");
        assertThat(row.argumentPath()).isEqualTo("input.inventoryId.inventory_id");
        assertThat(row.nodeTypeName()).isEqualTo("Inventory");
        assertThat(row.decode().methodName())
            .as("the per-type decode helper the node-id encoder generator emits, read off the model"
                + " rather than reconstructed from the type name")
            .isEqualTo("decodeInventory");
        assertThat(row.nodeTable().recordClass().simpleName()).isEqualTo("InventoryRecord");
        assertThat(row.column().sqlName()).isEqualTo("inventory_id");
        assertThat(row.decode().outputColumnShape())
            .as("the key list the decode returns values against, which the projected column is one of")
            .containsExactly(row.column());
    }

    /**
     * A composite key projected by name, at a position that is not the first. Two parameters bound
     * from one node id are two rows naming two columns of one key, and neither carries the position:
     * naming the column is what makes a transposed projection unconstructable, so the row that
     * projects the second column looks exactly like the row that projects the first.
     */
    @Test
    void aCompositeKeyIsProjectedByNameAtAnyPosition() {
        var relation = KeyProjectionCommands.produce(new ResolvedKeyProjections.Projections(List.of(
            new ResolvedKeyProjections.Projection("Mutation", "pair", "in.pairId.actor_id",
                "FilmActor", "actor_id"),
            new ResolvedKeyProjections.Projection("Mutation", "pair", "in.pairId.film_id",
                "FilmActor", "film_id"))), model);

        assertThat(relation.rows()).hasSize(2);
        assertThat(relation.projectionFor("Mutation", "pair", "in.pairId.film_id").orElseThrow()
            .column().sqlName()).isEqualTo("film_id");
        assertThat(relation.rows()).allSatisfy(r -> assertThat(r.decode().outputColumnShape())
            .as("both rows carry the same whole key list, in the order the decode returns values")
            .extracting(c -> c.sqlName()).containsExactly("actor_id", "film_id"));
    }

    /**
     * The lookup folds case, which it has to: the store hands out whichever of its three tiers won,
     * and those spell a column as an author wrote it in {@code @node(keyColumns:)}, as a generated
     * class stated it, or as the catalog holds it. Matching under the same case-insensitive rule the
     * catalog resolution already uses is what makes those one answer here rather than three.
     */
    @Test
    void theColumnResolvesWhicheverCaseTheWinningTierSpelledIt() {
        assertThat(only(projection("Mutation", "rentFilm", "input.inventoryId.INVENTORY_ID",
            "Inventory", "INVENTORY_ID")).column().sqlName())
            .isEqualTo("inventory_id");
    }

    /**
     * An unprojected path is absent rather than a row saying so, which is what lets an emitter render
     * the ordinary nested read on row absence instead of testing a flag.
     */
    @Test
    void anUnprojectedPathHasNoRow() {
        assertThat(KeyProjectionCommands.produce(
                new ResolvedKeyProjections.Projections(List.of()), model)
            .projectionFor("Mutation", "rentFilm", "input.customerId"))
            .isEmpty();
    }

    /**
     * The store resolved a key column for a type the model does not classify as a node. Nodehood is
     * required on every one of the store's three key-column tiers, so this is the two sides
     * disagreeing about it, and there is no emission that could be right under both.
     */
    @Test
    void aTypeTheModelDoesNotCallANodeIsReportedRatherThanSkipped() {
        assertThatThrownBy(() -> projection("Mutation", "rentFilm", "input.filmId.film_id",
            "Film", "film_id"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("classifies 'Film'")
            .hasMessageContaining("rather than a node type");
    }

    /**
     * The store resolved a column the model's own key list for that type does not carry. Skipping the
     * row would emit the base64 wire value verbatim and guessing a column would encode ids against a
     * key the author never named, so it reports, naming both sides.
     */
    @Test
    void aColumnTheModelsKeyListLacksIsReportedRatherThanSkipped() {
        assertThatThrownBy(() -> projection("Mutation", "rentFilm", "input.inventoryId.store_id",
            "Inventory", "store_id"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("resolved 'store_id' as a key column of node type 'Inventory'")
            .hasMessageContaining("[inventory_id]");
    }

    /**
     * The relation's key is the coordinate plus the written path, and it holds the claim that no two
     * sites can disagree about a leaf they share: within one coordinate a path reaches one leaf and
     * one node type whatever directive spelled it, so a second answer for one key is a contradiction
     * rather than a precedence question.
     */
    @Test
    void onePathAtOneCoordinateCannotResolveTwoColumns() {
        assertThatThrownBy(() -> KeyProjectionCommands.produce(
            new ResolvedKeyProjections.Projections(List.of(
                new ResolvedKeyProjections.Projection("Mutation", "pair", "in.pairId.actor_id",
                    "FilmActor", "actor_id"),
                new ResolvedKeyProjections.Projection("Mutation", "pair", "in.pairId.actor_id",
                    "FilmActor", "film_id"))), model))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("keyed by coordinate and written path");
    }

    private static KeyProjectionRelation projection(String typeName, String fieldName, String path,
                                                    String nodeTypeName, String columnName) {
        return KeyProjectionCommands.produce(new ResolvedKeyProjections.Projections(List.of(
            new ResolvedKeyProjections.Projection(typeName, fieldName, path, nodeTypeName,
                columnName))), model);
    }

    private static KeyProjection only(KeyProjectionRelation relation) {
        assertThat(relation.rows()).hasSize(1);
        return relation.rows().getFirst();
    }
}
