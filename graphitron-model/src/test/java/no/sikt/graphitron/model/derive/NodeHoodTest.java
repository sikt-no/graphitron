package no.sikt.graphitron.model.derive;

import org.assertj.core.groups.Tuple;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODEHOOD;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_TYPE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedImplements;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeWithTypeId;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedStatedNodeMetadata;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What {@link NodeHood} writes into {@code graphitron_nodehood}: which of a graph's types are nodes
 * and the wire type id each answers to. The relation states what resolved, so both halves are
 * pinned here, the population and the id, and they are pinned separately because they come from
 * different rules over different populations.
 *
 * <p>The population differs from {@code intent_node_type} in one deliberate way and the case that
 * covers it asserts the difference rather than the new answer alone. A {@code @node} on a type with
 * no {@code @table} is a row in the membership view, which takes the directive at its word; it is
 * not a row here, because the directive only takes effect on a type that also carries
 * {@code @table} and this relation states what took effect. Asserting both sides in one case is
 * what keeps it from passing for the wrong reason.
 *
 * <p>The id is a three-tier pick and each tier gets a case that removes the tier above it, since a
 * precedence only holds if the loser was present to lose.
 */
class NodeHoodTest {

    private static final String GRAPH = "g";
    private static final String PKG = "no.example.jooq";
    private static final String PUBLIC = "public";

    // ===== The population =====

    /** The authored arm: {@code @node} over a type the {@code @table} decode settled on a table. */
    @Test
    void aNodeOnATableBoundTypeIsANode() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedNode(dsl, GRAPH, "Inventory");

            assertThat(nodes(dsl)).containsExactly("Inventory");
        });
    }

    /**
     * The precondition, and the one place this relation disagrees with the membership view on
     * purpose. Both sides are asserted: the view answers, so the case cannot pass by seeding a
     * fixture that produces no node at all, and the anchor declines, which is the claim.
     */
    @Test
    void aNodeOnATypeWithNoTableIsNotANode() {
        withInventoryCatalog(dsl -> {
            seedNode(dsl, GRAPH, "Inventory");

            assertThat(membership(dsl))
                .as("the membership view takes @node at its word, which is what makes this a"
                    + " difference rather than an empty fixture")
                .containsExactly("Inventory");
            assertThat(nodes(dsl))
                .as("@node only takes effect over @table, and this relation states what took effect")
                .isEmpty();
        });
    }

    /**
     * The published arm: no {@code @node} anywhere, an {@code implements Node} over a bound table
     * whose generated class publishes well-formed identity metadata.
     */
    @Test
    void aBoundNodeImplementorOverPublishedMetadataIsANode() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(nodes(dsl)).containsExactly("Inventory");
        });
    }

    /**
     * A type both arms answer for is one row. The relation is keyed by the type, so this is the
     * primary key holding rather than a preference being applied, and it is worth a case because
     * the two arms are a union and a union all would fail here.
     */
    @Test
    void aTypeBothArmsAnswerForIsOneRow() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedNode(dsl, GRAPH, "Inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(nodes(dsl)).containsExactly("Inventory");
        });
    }

    // ===== The type id, one tier at a time =====

    /** Nothing declared and nothing published: the type's own name, which always answers. */
    @Test
    void aNodeWithNothingStatedAnywhereAnswersToItsOwnName() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedNode(dsl, GRAPH, "Inventory");

            assertThat(ids(dsl)).containsExactly(tuple("Inventory", "TYPE_NAME"));
        });
    }

    /**
     * The metadata tier, with the tier above it absent: no {@code typeId:} on the directive, so the
     * id the generated class states is the one that wins over the type's own name.
     */
    @Test
    void aPublishedTypeIdBeatsTheTypeName() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedNode(dsl, GRAPH, "Inventory");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "195");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(ids(dsl)).containsExactly(tuple("195", "JOOQ_METADATA"));
        });
    }

    /**
     * The author's tier over both. The published id differs from the declared one and from the type
     * name, so the case distinguishes all three rather than two of them: a wire format the author
     * pinned is a published contract, and whatever the generator emits does not move it.
     */
    @Test
    void aDeclaredTypeIdBeatsThePublishedOne() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedNodeWithTypeId(dsl, GRAPH, "Inventory", "inv");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "195");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(ids(dsl)).containsExactly(tuple("inv", "SDL_DECLARED"));
        });
    }

    /**
     * Metadata naming a column the table does not have is malformed, and a malformed constant is
     * not an answer. The tier declines rather than the type falling out, so the pick drops to the
     * type's own name and the node stays a node.
     */
    @Test
    void malformedMetadataYieldsNoIdAndTheNameAnswersInstead() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedNode(dsl, GRAPH, "Inventory");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "195");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "no_such_column");

            assertThat(ids(dsl)).containsExactly(tuple("Inventory", "TYPE_NAME"));
        });
    }

    // ===== The catalog every case stands on =====

    private static void withInventoryCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            body.accept(dsl);
        });
    }

    // ===== Reads =====

    private static List<String> nodes(DSLContext dsl) {
        derive(dsl);
        return dsl.select(GRAPHITRON_NODEHOOD.TYPE_NAME)
            .from(GRAPHITRON_NODEHOOD)
            .where(GRAPHITRON_NODEHOOD.GRAPH_NAME.eq(GRAPH))
            .orderBy(GRAPHITRON_NODEHOOD.TYPE_NAME)
            .fetch(GRAPHITRON_NODEHOOD.TYPE_NAME);
    }

    private static List<String> membership(DSLContext dsl) {
        derive(dsl);
        return dsl.select(INTENT_NODE_TYPE.TYPE_NAME)
            .from(INTENT_NODE_TYPE)
            .where(INTENT_NODE_TYPE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_NODE_TYPE.TYPE_NAME)
            .fetch(INTENT_NODE_TYPE.TYPE_NAME);
    }

    /** The id and the tier that answered, as a pair, since neither is worth asserting alone. */
    private static List<Tuple> ids(DSLContext dsl) {
        derive(dsl);
        return dsl.select(GRAPHITRON_NODEHOOD.TYPE_ID, GRAPHITRON_NODEHOOD.TYPE_ID_ORIGIN)
            .from(GRAPHITRON_NODEHOOD)
            .where(GRAPHITRON_NODEHOOD.GRAPH_NAME.eq(GRAPH))
            .orderBy(GRAPHITRON_NODEHOOD.TYPE_NAME)
            .fetch(r -> tuple(r.value1(), r.value2()));
    }
}
