package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.derive.StoreLegacyKeyShapes;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Legacy node key shapes from real SDL over the real catalog: what {@link StoreLegacyKeyShapes}
 * assembles for the encoder to emit from. Which shapes anchor and which rule each broken one breaks
 * is the node-legacy-key fact documents'; what stands here is that the read carries everything an
 * emitted conversion spells, the table constants it qualifies the columns through and the pick it
 * makes.
 */
@PipelineTier
class NodeLegacyKeyShapesPipelineTest {

    private static final String GRAPH = CapturedStore.GRAPH;

    @TempDir
    Path tmp;

    private static final String NODE = "interface Node { id: ID! }\n";

    @Test
    void aSchemaStatedShapeIsReadWithItsColumnsTableAndPick() {
        var shapes = read(NODE + """
            type InventoryItem implements Node @table(name: "inventory")
                @node(keyColumns: ["inventory_id"], legacyKeyColumns: ["store_id", "inventory_id", "last_update"]) {
              id: ID!
            }
            type Query { items: [InventoryItem!]! }
            """);

        assertThat(shapes.shapes()).singleElement().satisfies(shape -> {
            assertThat(shape.typeId()).isEqualTo("InventoryItem");
            assertThat(shape.columns()).extracting(ColumnRef::sqlName)
                .containsExactly("store_id", "inventory_id", "last_update");
            assertThat(shape.columns()).extracting(ColumnRef::javaName)
                .containsExactly("STORE_ID", "INVENTORY_ID", "LAST_UPDATE");
            assertThat(shape.keptAt()).as("the current key's one value sits at legacy position 1")
                .containsExactly(1);
            assertThat(shape.table().javaFieldName()).isEqualTo("INVENTORY");
            assertThat(CatalogRefs.constantsClass(shape.table()).simpleName()).isEqualTo("Tables");
        });
    }

    @Test
    void aCatalogStatedShapeIsReadUnderTheTablesTypeId() {
        var shapes = read(NODE + """
            type LegacyKeyed implements Node @table(name: "node_legacy_keyed") { id: ID! @nodeId }
            type Query { keyed: [LegacyKeyed!]! }
            """);

        assertThat(shapes.shapes()).singleElement().satisfies(shape -> {
            assertThat(shape.typeId()).isEqualTo("NodeLegacyKeyed");
            assertThat(shape.columns()).extracting(ColumnRef::sqlName).containsExactly("era", "id");
            assertThat(shape.keptAt()).containsExactly(1);
        });
    }

    @Test
    void aTypeIdWithNoShapeReadsNothing() {
        assertThat(read(NODE + """
            type Film implements Node @table(name: "film") @node { id: ID! }
            type Query { films: [Film!]! }
            """).shapes()).isEmpty();
    }

    private StoreLegacyKeyShapes.Shapes read(String sdl) {
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl, jooq())) {
            return StoreLegacyKeyShapes.read(store.dsl(), GRAPH);
        }
    }

    private static CatalogInput jooq() {
        return CatalogInput.of(testContext());
    }
}
