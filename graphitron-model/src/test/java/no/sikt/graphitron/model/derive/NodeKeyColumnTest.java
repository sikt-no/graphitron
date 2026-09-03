package no.sikt.graphitron.model.derive;

import org.assertj.core.groups.Tuple;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedStatedNodeMetadata;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What {@link NodeKeyColumns} writes: which catalog columns carry a node's identity, in key order.
 *
 * <p>Three tiers answer and the cases come in pairs, each removing the tier above so a precedence
 * is only claimed where the loser was present to lose. What the tiers share is the resolution: the
 * two authored tiers hand over spellings and the row carries a column, so every case that pins a
 * value also pins that the value is the catalog's own.
 *
 * <p>The cases that matter most are the ones where a tier declines. A pinned column the table does
 * not have yields nothing at all rather than falling through to the primary key, because falling
 * through would publish a wire format the author never asked for and would do it without saying so.
 * Two of those assert against {@code intent_resolved_node_key_column} as well, which does fall
 * through, so the difference is the assertion rather than an incidental property of the fixture.
 */
class NodeKeyColumnTest {

    private static final String GRAPH = "g";
    private static final String PKG = "no.example.jooq";
    private static final String PUBLIC = "public";

    // ===== The catalog's own tier, which is the default =====

    /** Nothing pinned and nothing published: the bound table's primary key, in key order. */
    @Test
    void aNodeOverAKeyedTableTakesItsPrimaryKey() {
        withNodeOverInventory(dsl -> {
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");

            assertThat(keys(dsl)).containsExactly(tuple("inventory_id", "CATALOG_PRIMARY_KEY"));
        });
    }

    /** A composite key keeps the constraint's own column order, which is the order ids are built in. */
    @Test
    void aCompositePrimaryKeyKeepsItsOrder() {
        withNodeOverInventory(dsl -> {
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "film_id", "store_id");

            assertThat(keys(dsl)).containsExactly(
                tuple("film_id", "CATALOG_PRIMARY_KEY"),
                tuple("store_id", "CATALOG_PRIMARY_KEY"));
        });
    }

    /** A table with no primary key leaves the node with no key columns rather than a partial list. */
    @Test
    void aNodeOverAnUnkeyedTableResolvesNothing() {
        withNodeOverInventory(dsl -> assertThat(keys(dsl)).isEmpty());
    }

    // ===== The published tier, over the catalog's =====

    /**
     * A bound table whose class publishes key columns beats the primary key, and the published
     * spelling resolves to the catalog's column rather than being forwarded.
     */
    @Test
    void publishedKeyColumnsBeatThePrimaryKey() {
        withNodeOverInventory(dsl -> {
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "FILM_ID");

            assertThat(keys(dsl))
                .as("the constant shouts and the catalog does not, so the row carries the catalog's")
                .containsExactly(tuple("film_id", "JOOQ_METADATA"));
        });
    }

    /**
     * The defect case. A published name the table does not have is malformed metadata, and a
     * malformed constant is not an answer, so this tier declines and the primary key answers.
     */
    @Test
    void malformedPublishedMetadataFallsThroughToThePrimaryKey() {
        withNodeOverInventory(dsl -> {
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "no_such_column");

            assertThat(keys(dsl)).containsExactly(tuple("inventory_id", "CATALOG_PRIMARY_KEY"));
        });
    }

    // ===== The author's tier, over both =====

    /** What the author pinned wins outright, and resolves to the catalog's spelling. */
    @Test
    void pinnedKeyColumnsBeatEverything() {
        withNodeOverInventory(dsl -> {
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "store_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "film_id");

            assertThat(keys(dsl)).containsExactly(tuple("film_id", "SDL_PINNED"));
        });
    }

    /** A pinned generated field name resolves too, both spellings being ones a consumer sees. */
    @Test
    void aPinnedGeneratedFieldNameResolves() {
        withNodeOverInventory(dsl -> {
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "filmId");

            assertThat(keys(dsl))
                .as("filmId is the generated name of film_id, and the row carries the SQL one")
                .containsExactly(tuple("film_id", "SDL_PINNED"));
        });
    }

    /**
     * The rule this relation exists for. A pinned column the table does not have resolves nothing
     * and does not fall through, where the relation it replaces forwards the spelling untouched and
     * calls it resolved. Both are asserted, so the case states a difference rather than an outcome.
     */
    @Test
    void aPinnedColumnTheTableLacksResolvesNothingAndDoesNotFallThrough() {
        withNodeOverInventory(dsl -> {
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "no_such_column");

            assertThat(forwarded(dsl))
                .as("the view hands back the spelling it was given, which is what makes this a"
                    + " difference rather than a fixture that pinned nothing")
                .containsExactly("no_such_column");
            assertThat(keys(dsl))
                .as("no column, so no row, and the primary key does not step in for an author who"
                    + " asked for something else")
                .isEmpty();
        });
    }

    /**
     * All or nothing within the tier. One position resolving and one not is a key list with a hole,
     * which would decode values into the wrong positions, so the whole tuple goes unwritten rather
     * than the good half surviving.
     */
    @Test
    void oneUnresolvedPositionDropsTheWholePinnedTuple() {
        withNodeOverInventory(dsl -> {
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "film_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 1, "no_such_column");

            assertThat(keys(dsl)).isEmpty();
        });
    }

    /**
     * A spelling two columns of the table answer to, one by its SQL name and one by its generated
     * name. Two columns is not a resolution, so the tier declines and nothing is written; the key
     * would forbid the alternative anyway, one position holding two columns.
     */
    @Test
    void aSpellingTwoColumnsAnswerToResolvesNothing() {
        withInventoryCatalog(dsl -> {
            seedColumn(dsl, PKG, PUBLIC, "inventory", "filmid", 3, "somethingElse");
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "filmId");

            assertThat(keys(dsl))
                .as("filmId is film_id's generated name and filmid's own, so it names neither")
                .isEmpty();
        });
    }

    /**
     * A published entry two columns answer to. Not a malformed constant, so the defect relation
     * says nothing and this tier is reached; not a resolution either, so the tier declines whole
     * rather than dropping the one position and shipping the rest, which would be a key tuple with
     * a hole. The primary key answers, as it does for metadata that is malformed.
     */
    @Test
    void anAmbiguousPublishedEntryDeclinesTheWholeTierRatherThanLeavingAHole() {
        withInventoryCatalog(dsl -> {
            seedColumn(dsl, PKG, PUBLIC, "inventory", "filmid", 3, "somethingElse");
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedPrimaryKey(dsl, PKG, PUBLIC, "inventory", "inventory_pkey", "inventory_id");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "store_id");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 1, "filmId");

            assertThat(keys(dsl))
                .as("store_id resolves and filmId does not, and half a key is not half an answer")
                .containsExactly(tuple("inventory_id", "CATALOG_PRIMARY_KEY"));
        });
    }

    // ===== The precondition the whole relation stands on =====

    /**
     * Key columns pinned on a type that is not a node. The entry holds the rows, since an author may
     * write them anywhere, and nothing resolves, since nodehood is what this relation keys into.
     * The view it replaces resolves them, which is the defect that made this case worth writing.
     */
    @Test
    void keyColumnsPinnedOnANonNodeResolveNothing() {
        withInventoryCatalog(dsl -> {
            seedNode(dsl, GRAPH, "Inventory");
            seedNodeKeyColumnRef(dsl, GRAPH, "Inventory", 0, "film_id");

            assertThat(forwarded(dsl))
                .as("the view resolves key columns for a @node with no @table")
                .containsExactly("film_id");
            assertThat(keys(dsl))
                .as("no @table, so no nodehood, so no key columns")
                .isEmpty();
        });
    }

    // ===== Fixtures =====

    private static void withInventoryCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "film_id", 1, "filmId");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "store_id", 2, "storeId");
            body.accept(dsl);
        });
    }

    /** The catalog with a {@code @node} bound to the table, which is what makes the type a node. */
    private static void withNodeOverInventory(Consumer<DSLContext> body) {
        withInventoryCatalog(dsl -> {
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            body.accept(dsl);
        });
    }

    // ===== Reads =====

    /** The resolved columns in key order, with the tier that answered. */
    private static List<Tuple> keys(DSLContext dsl) {
        derive(dsl);
        return dsl.select(GRAPHITRON_NODE_KEYCOLUMN.COLUMN_NAME,
                GRAPHITRON_NODE_KEYCOLUMN.COLUMN_ORIGIN)
            .from(GRAPHITRON_NODE_KEYCOLUMN)
            .where(GRAPHITRON_NODE_KEYCOLUMN.GRAPH_NAME.eq(GRAPH))
            .orderBy(GRAPHITRON_NODE_KEYCOLUMN.TYPE_NAME, GRAPHITRON_NODE_KEYCOLUMN.POSITION)
            .fetch(r -> tuple(r.value1(), r.value2()));
    }

    /** What the relation this replaces answers, for the cases that state a difference. */
    private static List<String> forwarded(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_RESOLVED_NODE_KEY_COLUMN.COLUMN_NAME)
            .from(INTENT_RESOLVED_NODE_KEY_COLUMN)
            .where(INTENT_RESOLVED_NODE_KEY_COLUMN.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_RESOLVED_NODE_KEY_COLUMN.POSITION)
            .fetch(INTENT_RESOLVED_NODE_KEY_COLUMN.COLUMN_NAME);
    }
}
