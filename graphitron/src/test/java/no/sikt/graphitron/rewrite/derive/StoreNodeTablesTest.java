package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.capture.SubjectConfig;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link StoreNodeTables} assembles out of the store: a node type's table reference, its ordered
 * key columns, and its wire type id, with no live catalog and no walked model in reach.
 *
 * <p>This is the tier that can make the claim at all. The relation-level cases below it pin the shape
 * of one row; the model module's suite pins what each view returns given seeded rows; neither says a
 * real captured store carries enough to build a {@code TableRef}, which is the thing that was not true
 * until the {@code Tables} class was captured. So the fixtures here are real SDL over the real sakila
 * catalog, and what they assert are the components an emitted decode actually spells: the record class
 * it instantiates, the constants class and field name its column references qualify through, and the
 * key order its positional load depends on.
 *
 * <p>The type id is asserted per tier rather than once, because the tiers are a precedence and a
 * precedence that only ever fires on its first arm is untested. Two of the three are reachable from
 * SDL alone; the metadata tier needs a generated class publishing the constant, which the fixture
 * catalog does not, and its absence is stated here rather than left as a silent gap.
 */
@PipelineTier
class StoreNodeTablesTest {

    @TempDir
    Path tmp;

    /**
     * The components a decode spells, from the store alone. The constants class is the one worth
     * reading twice: it is per schema and resolvable only off the codegen classpath, so before it was
     * captured this assertion could not have been written without a live catalog.
     */
    @Test
    void aNodeTypesTableIsAssembledWithItsGeneratedClasses() {
        var table = read("""
            type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
                id: ID!
            }
            type Query { inventory: Inventory }
            """).get("Inventory").orElseThrow();

        assertThat(table.table().tableName()).isEqualTo("inventory");
        assertThat(table.table().javaFieldName()).isEqualTo("INVENTORY");
        assertThat(CatalogRefs.tableClass(table.table()).simpleName()).isEqualTo("Inventory");
        assertThat(CatalogRefs.recordClass(table.table()).simpleName()).isEqualTo("InventoryRecord");
        assertThat(CatalogRefs.constantsClass(table.table()).simpleName())
            .as("the per-schema Tables class, read from sql_schema rather than concatenated")
            .isEqualTo("Tables");
        assertThat(table.table().allColumns())
            .as("the whole row, so the ref is not a partial one")
            .isNotEmpty();
        assertThat(table.table().primaryKeyColumns().stream().map(ColumnRef::sqlName))
            .containsExactly("inventory_id");
    }

    /**
     * A column comes out with both spellings the emission needs: the SQL name the store resolved it
     * under, and the generated field name a {@code Tables.X.COL} reference is written with.
     */
    @Test
    void columnsCarryBothTheSqlAndTheGeneratedName() {
        var key = read("""
            type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
                id: ID!
            }
            type Query { inventory: Inventory }
            """).get("Inventory").orElseThrow().keyColumns().getFirst();

        assertThat(key.sqlName()).isEqualTo("inventory_id");
        assertThat(key.javaName())
            .as("what the emitted column reference is qualified with")
            .isEqualTo("INVENTORY_ID");
    }

    /**
     * A composite key keeps the order the author pinned, which is the whole reason the list is ordered
     * rather than a set: the decode's {@code fromArray} load is positional, so a reordered list would
     * write each value into the wrong column silently.
     */
    @Test
    void aCompositeKeyKeepsThePinnedOrder() {
        var table = read("""
            type FilmActor implements Node @table(name: "film_actor") @node(keyColumns: ["film_id", "actor_id"]) {
                id: ID!
            }
            type Query { filmActor: FilmActor }
            """).get("FilmActor").orElseThrow();

        assertThat(table.keyColumns().stream().map(ColumnRef::sqlName))
            .as("the pinned order, not the catalog's")
            .containsExactly("film_id", "actor_id");
    }

    /** The type-id tier an author writes: {@code @node(typeId:)} wins outright wherever declared. */
    @Test
    void anAuthoredTypeIdWins() {
        assertThat(read("""
            type Inventory implements Node @table(name: "inventory")
                @node(typeId: "Beholdning", keyColumns: ["inventory_id"]) {
                id: ID!
            }
            type Query { inventory: Inventory }
            """).get("Inventory").orElseThrow().typeId())
            .isEqualTo("Beholdning");
    }

    /**
     * The default tier: a node type declaring no {@code typeId:} and backed by a table publishing no
     * node metadata takes its own name. Total by construction is what a consumer relies on, so the
     * lowest tier answering is asserted rather than assumed.
     */
    @Test
    void anUndeclaredTypeIdFallsBackToTheTypeName() {
        assertThat(read("""
            type Inventory implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
                id: ID!
            }
            type Query { inventory: Inventory }
            """).get("Inventory").orElseThrow().typeId())
            .isEqualTo("Inventory");
    }

    /**
     * A type that is not a node assembles nothing, which is what keeps the product a node-type
     * population rather than a table index.
     */
    @Test
    void aPlainTableTypeIsAbsent() {
        assertThat(read("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """).get("Film")).isEmpty();
    }

    /**
     * Captures {@code sdl} against the real fixture catalog and reads the assembly back.
     * {@code CapturedStore}'s own factories are not used, and the reason is the subject: they build
     * their context off a temp directory, so their {@code JooqCatalog} resolves no generated classes
     * and the whole {@code sql_} family comes out empty. Everything asserted here is a catalog fact,
     * so this drives {@link FactCapture#capture} itself over that handle's primitives plus a catalog
     * built from the test configuration, which is the arrangement the handle documents for a test
     * whose axis combination it does not name.
     */
    private StoreNodeTables.Tables read(String sdl) {
        var ctx = testContext();
        var registry = CapturedStore.registryOf(tmp, sdl);
        try (var store = FactStores.inMemory()) {
            FactCapture.capture(store.dsl(), CapturedStore.graph(tmp),
                SubjectConfig.none(), registry, CapturedStore.attributionOf(tmp),
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), List.of());
            return StoreNodeTables.read(store.dsl(), CapturedStore.GRAPH);
        }
    }
}
