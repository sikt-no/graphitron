package no.sikt.graphitron.render;

import no.sikt.graphitron.model.derive.StoreLegacyKeyShapes;
import no.sikt.graphitron.model.derive.StoreNodeTables;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogRefs} over table references a capture assembled, so the classes an emitted decode
 * names are the ones the fixture catalog generated rather than ones a test spelled.
 */
@PipelineTier
class CatalogRefsTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String DOCUMENT = "scenarios/node-legacy-key-shapes";

    @Test
    void aNodeTablesGeneratedClassesAreReferencedByTheirSimpleNames() {
        var table = FACTS.read(DOCUMENT, graph ->
            StoreNodeTables.read(graph.handle().dsl(), graph.handle().graphName())
                .get("InventoryItem").orElseThrow().table());

        assertThat(CatalogRefs.tableClass(table).simpleName()).isEqualTo("Inventory");
        assertThat(CatalogRefs.recordClass(table).simpleName()).isEqualTo("InventoryRecord");
        assertThat(CatalogRefs.constantsClass(table).simpleName()).isEqualTo("Tables");
    }

    @Test
    void aLegacyKeyShapesTableIsQualifiedThroughItsSchemasTablesClass() {
        var table = FACTS.read(DOCUMENT, graph ->
            StoreLegacyKeyShapes.read(graph.handle().dsl(), graph.handle().graphName())
                .shapes().stream().filter(shape -> shape.typeId().equals("InventoryItem"))
                .findFirst().orElseThrow().table());

        assertThat(CatalogRefs.constantsClass(table).simpleName()).isEqualTo("Tables");
    }
}
