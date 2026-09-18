package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedImplements;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedStatedNodeMetadata;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code graphitron_node_keycolumn} returns: the ordered key columns a graph's type
 * encodes a node id from, resolved across the three populations that can answer. The generator makes
 * the same resolution with a live catalog in hand, so what these cases pin is that the relation
 * agrees with it tier for tier, and which tier answered rather than only that the names came out
 * right.
 *
 * <p>Two properties carry most of the weight. The pick is by type and never by the
 * {@code (type, position)} coordinate, so a case with several tiers populated asserts the winning
 * tier's <em>whole list in its own order</em>: a per-position pick would splice one tier's column
 * into another tier's order, which is the transposition that silently encodes ids nobody can decode.
 * And the two tiers that reach a table go silent on an ambiguous binding rather than picking one, so
 * several cases assert no row where a two-candidate binding is the only thing that changed.
 *
 * <p>Absence is an answer here rather than a gap. A type no tier resolves has no row, which is the
 * state the generator reports as an error; naming it is the detection stratum's job and not this
 * relation's, so a case pinning emptiness is pinning where the relation stops.
 */
class ResolvedNodeKeyColumnTest {

    private static final String GRAPH = "g";
    private static final String PKG = "no.example.jooq";
    private static final String PUBLIC = "public";

    // ===== The precedence among the three tiers =====

    /**
     * All three populations answer for one type and the pinned list wins. The generator prefers the
     * reconciled node type for the reason its own comment gives, SDL winning on key-column order,
     * and this is that preference stated as a precedence over rows.
     */
    @Test
    void aPinnedListOutranksBothTheMetadataAndThePrimaryKey() {
        withNodeOverInventory(dsl -> {
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "inventory_id");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "store_id");

            assertThat(tierOf(dsl, "Inventory")).isEqualTo("SDL_PINNED");
            assertThat(keyColumns(dsl, "Inventory"))
                .as("the metadata's film_id and the key's store_id are the losing tiers' answers")
                .containsExactly("inventory_id");
        });
    }

    /**
     * With no pinned list the metadata answers, ahead of the table's own primary key. This is the
     * middle arm the generator reaches by reading the table's node-identity constants, and reading
     * the key instead would answer differently for every table that publishes them.
     */
    @Test
    void theStatedMetadataOutranksThePrimaryKey() {
        withNodeOverInventory(dsl -> {
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "store_id");

            assertThat(tierOf(dsl, "Inventory")).isEqualTo("JOOQ_METADATA");
            assertThat(keyColumns(dsl, "Inventory")).containsExactly("film_id");
        });
    }

    /** The last tier: an {@code @node} over a table whose class publishes no metadata. */
    @Test
    void thePrimaryKeyAnswersWhenNeitherOfTheOthersDoes() {
        withNodeOverInventory(dsl -> {
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");

            assertThat(tierOf(dsl, "Inventory")).isEqualTo("CATALOG_PRIMARY_KEY");
            assertThat(keyColumns(dsl, "Inventory")).containsExactly("inventory_id");
        });
    }

    /**
     * The metadata tier does not require an {@code @node} and does require nodehood. Implementing
     * {@code Node} is what makes a type one, and the generated constants are looked for only then,
     * so a type bound by {@code @table} over a metadata-carrying table and implementing nothing
     * draws no row. The tier used to answer for it, which on a real capture meant key columns for
     * hundreds of types that are no node at all.
     */
    @Test
    void theMetadataTierNeedsNodehoodRatherThanAnAtNode() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(keyColumns(dsl, "Inventory"))
                .as("a table binding is not nodehood")
                .isEmpty();

            seedImplements(dsl, GRAPH, "Inventory", "Node");
            assertThat(tierOf(dsl, "Inventory")).isEqualTo("JOOQ_METADATA");
            assertThat(keyColumns(dsl, "Inventory")).containsExactly("inventory_id");
        });
    }

    /**
     * The primary-key tier does require nodehood, and a type that is a node by neither rule reaches
     * it by neither: no {@code @node} and nothing to infer one from leaves the tier silent.
     */
    @Test
    void thePrimaryKeyTierIsSilentForATypeThatIsNoNode() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");

            assertThat(keyColumns(dsl, "Inventory")).isEmpty();
        });
    }

    /**
     * An inferred node type resolves on the metadata tier and never on the primary-key one, which is
     * what makes the primary-key arm's read of {@code graphitron_node_type} rather than the authored
     * {@code @node} arm alone inert rather than a widening. Inference needs well-formed node
     * metadata, well-formedness needs a declared key-columns list, and that list is the higher
     * tier's own answer, so the two conditions cannot come apart. Pinned because the arm reads the
     * union deliberately: if inference ever loosens, this is the case that says so.
     */
    @Test
    void anInferredNodeTypeTakesTheMetadataTierAndNotThePrimaryKey() {
        withInventoryCatalog(dsl -> {
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "store_id");

            assertThat(tierOf(dsl, "Inventory"))
                .as("a node by inference, with no @node of its own")
                .isEqualTo("JOOQ_METADATA");
            assertThat(keyColumns(dsl, "Inventory"))
                .as("the primary key's store_id is the losing tier's answer")
                .containsExactly("inventory_id");
        });
    }

    // ===== One tier wins whole, and its order survives =====

    /**
     * A composite key comes out in the winning tier's own order, and the loser contributes no
     * position. The pick has to be by type for this: partitioning by {@code (type, position)} would
     * take the pinned list's first column and the primary key's second, which is a tuple neither
     * tier stated and which no decode helper returns values in.
     */
    @Test
    void aCompositeKeyKeepsOneTiersOrderRatherThanSplicingTwo() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "bar");
            seedColumn(dsl, PKG, PUBLIC, "bar", "bar_id", 0, "barId");
            seedColumn(dsl, PKG, PUBLIC, "bar", "foo_id", 1, "fooId");
            seedNode(dsl, GRAPH, "Bar");
            seedTableBinding(dsl, GRAPH, "Bar", "bar");
            seedNodeKeyColumnRef(dsl, GRAPH, "Bar", 0, "foo_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Bar", 1, "bar_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "bar", "bar_pkey", "bar_id", "foo_id");

            assertThat(tierOf(dsl, "Bar")).isEqualTo("SDL_PINNED");
            assertThat(keyColumns(dsl, "Bar"))
                .as("the pinned order is the author's, and the key's is the other way round")
                .containsExactly("foo_id", "bar_id");
        });
    }

    /**
     * The winning tier's list is taken whole even where it is longer than the loser's, which is the
     * other half of the by-type pick: a per-position pick would keep the losing tier's positions
     * past the end of the winner's.
     */
    @Test
    void aLongerLosingListContributesNoTrailingPosition() {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "bar");
            seedColumn(dsl, PKG, PUBLIC, "bar", "bar_id", 0, "barId");
            seedColumn(dsl, PKG, PUBLIC, "bar", "foo_id", 1, "fooId");
            seedNode(dsl, GRAPH, "Bar");
            seedTableBinding(dsl, GRAPH, "Bar", "bar");
            seedNodeKeyColumnRef(dsl, GRAPH, "Bar", 0, "bar_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "bar", "bar_pkey", "bar_id", "foo_id");

            assertThat(keyColumns(dsl, "Bar")).containsExactly("bar_id");
        });
    }

    // ===== An ambiguous binding resolves no key columns =====

    /**
     * A binding with two candidates silences both tiers that reach a table, and the pinned tier
     * answers at the same coordinate regardless. That is the distinction the two halves of this case
     * draw: no table to read is not the same state as no key columns, and only the tier that needs
     * no table can tell them apart.
     */
    @Test
    void anAmbiguousBindingSilencesEveryTierIncludingThePinnedOne() {
        withCollidingInventory(dsl -> {
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "store_id");

            assertThat(keyColumns(dsl, "Inventory"))
                .as("neither table-reaching tier answers with a table nobody named")
                .isEmpty();

            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "inventory_id");
            assertThat(keyColumns(dsl, "Inventory"))
                .as("and neither does the pinned one: a key column is a column of a table, so a"
                    + " name pinned against two candidate tables resolves against neither")
                .isEmpty();
        });
    }

    // ===== Where the relation stops =====

    /**
     * Malformed metadata is passed over rather than projected. The tier reads well-formed rows,
     * which is a metadata row with no defect rows and not the anti-join alone, so an entry naming a
     * column the table does not have falls through to the next tier instead of resolving to a name
     * the decode would then read off nothing.
     */
    @Test
    void metadataStatingAnUnresolvableColumnFallsThroughRatherThanProjectingIt() {
        withNodeOverInventory(dsl -> {
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "no_such_column");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");

            assertThat(tierOf(dsl, "Inventory")).isEqualTo("CATALOG_PRIMARY_KEY");
            assertThat(keyColumns(dsl, "Inventory")).containsExactly("inventory_id");
        });
    }

    /**
     * A stated name matching under either spelling resolves, which is the predicate the defect rule
     * decides well-formedness by. Sharing it is the point: a tier that resolved entries by the SQL
     * name alone would disagree with the arm that already called the row well-formed. What lands is
     * the catalog's own name, the stated spelling having been resolved rather than forwarded.
     */
    @Test
    void aMetadataEntrySpelledTheGeneratedWayIsWellFormed() {
        withNodeOverInventory(dsl -> {
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventoryId");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "store_id");

            assertThat(tierOf(dsl, "Inventory")).isEqualTo("JOOQ_METADATA");
            assertThat(keyColumns(dsl, "Inventory"))
                .as("the stated spelling is the generated one and the row carries the SQL name it"
                    + " resolved to, as the pinned tier's own case states for its side")
                .containsExactly("inventory_id");
        });
    }

    /** An {@code @node} over a table with no metadata and no primary key resolves nothing. */
    @Test
    void aNodeOverAKeylessTableHasNoRow() {
        withNodeOverInventory(dsl -> assertThat(keyColumns(dsl, "Inventory")).isEmpty());
    }

    /** A type nothing binds and nothing pins resolves nothing, whatever the catalog holds. */
    @Test
    void anUnboundTypeHasNoRow() {
        withInventoryCatalog(dsl -> {
            seedNode(dsl, GRAPH, "Inventory");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");

            assertThat(keyColumns(dsl, "Inventory")).isEmpty();
        });
    }

    /** The graph partition holds: a sibling graph's pinned list is not this graph's answer. */
    @Test
    void aSiblingGraphsPinnedListDoesNotAnswerHere() {
        withNodeOverInventory(dsl -> {
            seedGraph(dsl, "other");
            seedNode(dsl, "other", "Inventory");
            // The sibling reads the same catalog source and binds the table too. A @node with no
            // @table is not a node, and a table in a source a graph did not read binds nothing, so
            // without both of these the sibling answers nothing and the case passes for the wrong
            // reason.
            seedGraphSource(dsl, "other", PKG);
            seedTableBinding(dsl, "other", "Inventory", "inventory");
            seedNodeKeyColumnRef(dsl, "other", "Inventory", 0, "inventory_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "store_id");

            assertThat(keyColumns(dsl, GRAPH, "Inventory"))
                .as("this graph falls to its own last tier rather than borrowing the pinned list")
                .containsExactly("store_id");
            assertThat(keyColumns(dsl, "other", "Inventory")).containsExactly("inventory_id");
        });
    }

    // ===== Fixtures =====

    /** The source the graph resolves catalog names against, and the schema its tables sit in. */
    private static void catalog(DSLContext dsl) {
        seedSource(dsl, PKG, "JOOQ_SCHEMA");
        seedGraphSource(dsl, GRAPH, PKG);
    }

    /** An {@code inventory} table with the three columns these cases spell, and nothing on it. */
    private static void withInventoryCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "film_id", 1, "filmId");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "store_id", 2, "storeId");
            body.accept(dsl);
        });
    }

    /** {@link #withInventoryCatalog} with an {@code @node} type bound to the table unambiguously. */
    private static void withNodeOverInventory(Consumer<DSLContext> body) {
        withInventoryCatalog(dsl -> {
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            body.accept(dsl);
        });
    }

    /**
     * A catalog where {@code inventory} is declared in two schemas, so a {@code @table} naming it
     * reaches two tables and the binding carries two candidates. Which arm produced the second row
     * does not matter to this relation: the arity is recounted over the union of both binding
     * populations, and the guard the two table-reaching tiers apply is the count.
     */
    private static void withCollidingInventory(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            catalog(dsl);
            for (String schema : List.of(PUBLIC, "legacy")) {
                seedTable(dsl, PKG, schema, "inventory");
                seedColumn(dsl, PKG, schema, "inventory", "inventory_id", 0, "inventoryId");
                seedColumn(dsl, PKG, schema, "inventory", "film_id", 1, "filmId");
                seedColumn(dsl, PKG, schema, "inventory", "store_id", 2, "storeId");
            }
            body.accept(dsl);
        });
    }

    // ===== Reads =====

    /** The tier that answered for a type, the pick being one tier for the whole list. */
    private static String tierOf(DSLContext dsl, String typeName) {
        derive(dsl);
        var tiers = dsl.selectDistinct(GRAPHITRON_NODE_KEYCOLUMN.COLUMN_ORIGIN)
            .from(GRAPHITRON_NODE_KEYCOLUMN)
            .where(GRAPHITRON_NODE_KEYCOLUMN.GRAPH_NAME.eq(GRAPH))
            .and(GRAPHITRON_NODE_KEYCOLUMN.TYPE_NAME.eq(typeName))
            .fetch(GRAPHITRON_NODE_KEYCOLUMN.COLUMN_ORIGIN);
        assertThat(tiers)
            .as("one tier wins for a type and its whole list is taken")
            .hasSize(1);
        return tiers.getFirst();
    }

    /** A type's resolved key columns in position order, in the graph under assertion. */
    private static List<String> keyColumns(DSLContext dsl, String typeName) {
        return keyColumns(dsl, GRAPH, typeName);
    }

    /** A type's resolved key columns in position order, in a named graph. */
    private static List<String> keyColumns(DSLContext dsl, String graphName, String typeName) {
        derive(dsl);
        return dsl.select(GRAPHITRON_NODE_KEYCOLUMN.COLUMN_NAME)
            .from(GRAPHITRON_NODE_KEYCOLUMN)
            .where(GRAPHITRON_NODE_KEYCOLUMN.GRAPH_NAME.eq(graphName))
            .and(GRAPHITRON_NODE_KEYCOLUMN.TYPE_NAME.eq(typeName))
            .orderBy(GRAPHITRON_NODE_KEYCOLUMN.POSITION)
            .fetch(GRAPHITRON_NODE_KEYCOLUMN.COLUMN_NAME);
    }
}
