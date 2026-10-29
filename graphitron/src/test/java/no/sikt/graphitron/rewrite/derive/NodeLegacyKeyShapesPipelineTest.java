package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.derive.NodeLegacyKeyDefects;
import no.sikt.graphitron.model.derive.StoreLegacyKeyShapes;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Legacy node key shapes from real SDL over the real catalog: what {@link StoreLegacyKeyShapes}
 * assembles for the encoder to emit from, and the build errors {@link NodeLegacyKeyDefects} mints.
 * Which shapes anchor and which rule each broken one breaks is the node-legacy-key fact documents';
 * what stands here is that the read carries everything an emitted conversion spells, the table
 * constants it qualifies the columns through and the pick it makes, and that each defect fails the
 * build with a message naming its remedy.
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

    @Test
    void anUnresolvedSpellingIsAnUnknownNameOfferedTheTablesColumns() {
        var violations = detect(NODE + """
            type Unresolved implements Node @table(name: "film") @node(legacyKeyColumns: ["film_id", "nonesuch"]) {
              id: ID!
            }
            type Query { u: Unresolved }
            """);

        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
            assertThat(v.message()).contains("nonesuch").contains("legacyKeyColumns");
            assertThat(v.location().getLine()).isEqualTo(2);
        });
    }

    @Test
    void eachBrokenRuleIsABuildErrorNamingItsRemedy() {
        var violations = detect(NODE + """
            type Misses implements Node @table(name: "actor") @node(legacyKeyColumns: ["first_name", "last_name"]) { id: ID! }
            type Reorders implements Node @table(name: "film_category") @node(legacyKeyColumns: ["category_id", "film_id"]) { id: ID! }
            type Repeats implements Node @table(name: "city") @node(legacyKeyColumns: ["city_id", "country_id", "city_id"]) { id: ID! }
            type Disagrees implements Node @table(name: "node_legacy_keyed") @node(legacyKeyColumns: ["id", "era"]) { id: ID! @nodeId }
            type ForeignLegacy @table(name: "node_legacy_foreign") { id: Int }
            type Query { m: Misses, r: Reorders, p: Repeats, d: Disagrees, f: ForeignLegacy }
            """);

        assertThat(violations).extracting(ValidationError::message).satisfiesExactlyInAnyOrder(
            m -> assertThat(m).contains("Disagrees").contains("__NODE_LEGACY_KEY_COLUMNS").contains("era, id"),
            m -> assertThat(m).contains("node_legacy_foreign").contains("generator configuration"),
            m -> assertThat(m).contains("Misses").contains("actor_id").contains("every current key column"),
            m -> assertThat(m).contains("Reorders").contains("at least one more column"),
            m -> assertThat(m).contains("Repeats").contains("city_id").contains("twice"));
        assertThat(violations).allSatisfy(v -> assertThat(v.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR));
    }

    @Test
    void aSharedTypeIdOverTwoTablesIsABuildErrorAtBothTypes() {
        var violations = detect(NODE + """
            type CollideA @table(name: "collide_a") { name: String }
            type CollideB implements Node @table(name: "collide_b") @node(legacyKeyColumns: ["key_b", "name"]) { id: ID! }
            type Query { a: CollideA, b: CollideB }
            """);

        assertThat(violations).filteredOn(v -> v.message().contains("195"))
            .extracting(ValidationError::message)
            .satisfiesExactlyInAnyOrder(
                m -> assertThat(m).contains("CollideA").contains("own type ids"),
                m -> assertThat(m).contains("CollideB").contains("own type ids"));
    }

    private StoreLegacyKeyShapes.Shapes read(String sdl) {
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl, jooq())) {
            return StoreLegacyKeyShapes.read(store.dsl(), GRAPH);
        }
    }

    private List<ValidationError> detect(String sdl) {
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl, jooq())) {
            return NodeLegacyKeyDefects.detect(store.dsl(), GRAPH).violations();
        }
    }

    private static CatalogInput jooq() {
        return CatalogInput.of(testContext());
    }
}
