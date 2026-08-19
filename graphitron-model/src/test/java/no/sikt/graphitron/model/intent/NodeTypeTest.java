package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_INFERRED_NODE_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_TYPE;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedImplements;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeMetadata;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedStatedNodeMetadata;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_inferred_node_type} and {@code intent_node_type} return: which of a graph's
 * types are node types, from the authored population and the inferred one. The generator answers the
 * same question live in {@code NodeDeclaration.isNodeType}, so what these cases pin is that the
 * relations agree with that predicate arm for arm, over rows a real capture can produce and a few it
 * cannot.
 *
 * <p>The inferred arm is a conjunction of four things and every one of them is load-bearing, so most
 * cases here hold three fixed and drop the fourth. That is the shape the predicate has: an
 * {@code @table} binding, an {@code implements Node}, an unambiguous binding, and node metadata the
 * bound table publishes without defect. Dropping any one has to silence the inference, because each
 * absence is a different state a real schema reaches and none of them is a node.
 *
 * <p>The membership reduction adds two properties worth their own cases. It dedupes rather than
 * ranking, so a type both arms answer for is one row and precedence never arises; and it takes
 * {@code @node} at its word, a declared node with no {@code implements Node} still reading as a node
 * because that is what the live predicate answers and rejecting the shape is a detection's job.
 */
class NodeTypeTest {

    private static final String GRAPH = "g";
    private static final String PKG = "no.example.jooq";
    private static final String PUBLIC = "public";

    // ===== The inferred arm's conjunction, one absence at a time =====

    /**
     * All four conditions hold: the type binds a table, declares {@code implements Node}, binds it
     * unambiguously, and the table's generated class publishes well-formed metadata. This is the
     * population the live predicate's second arm exists for, a node nobody wrote {@code @node} on.
     */
    @Test
    void aTableBoundNodeImplementorOverWellFormedMetadataIsInferred() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(inferred(dsl)).containsExactly("Inventory");
            assertThat(nodeTypes(dsl)).containsExactly("Inventory");
        });
    }

    /**
     * The same rows without the {@code implements Node}. A table publishing node metadata does not
     * make every type bound to it a node: the SDL has to claim the interface, which is the half of
     * the conjunction that keeps the inference from spreading across a schema by catalog accident.
     */
    @Test
    void aTableBoundTypeThatImplementsNothingIsNotInferred() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(inferred(dsl)).isEmpty();
            assertThat(nodeTypes(dsl)).isEmpty();
        });
    }

    /**
     * The same rows without the {@code @table}. A type may implement {@code Node} for reasons that
     * have nothing to do with a table, and the live predicate reads the directive's presence before
     * it probes anything, so there is nothing to infer from here.
     */
    @Test
    void anUnboundNodeImplementorIsNotInferred() {
        withInventoryCatalog(dsl -> {
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(inferred(dsl)).isEmpty();
        });
    }

    /**
     * The same rows over a table whose class publishes nothing. This is the state that separates a
     * conjunction from an anti-join: a table with no metadata row has no defect rows either, so a
     * rule reading "no defects" alone would call every bound node implementor a node.
     */
    @Test
    void aBoundNodeImplementorOverATableWithNoMetadataIsNotInferred() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");

            assertThat(inferred(dsl)).isEmpty();
        });
    }

    /**
     * Metadata stating a column the table does not have is malformed, so the inference declines
     * rather than calling the type a node whose key columns resolve against nothing. The
     * well-formedness question is not asked here: it is the defect relation's, and reading it is
     * what keeps the two spellings of this conjunction agreeing.
     */
    @Test
    void malformedMetadataDoesNotInferNodehood() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "no_such_column");

            assertThat(inferred(dsl)).isEmpty();
        });
    }

    /** A class declaring the key-columns constant as null is a defect too, on the same terms. */
    @Test
    void metadataDeclaringHalfThePairDoesNotInferNodehood() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "STRING", "Inventory", null,
                "ABSENT", null);

            assertThat(inferred(dsl)).isEmpty();
        });
    }

    /**
     * An ambiguous binding infers nothing. Two candidate tables are two different sets of node
     * metadata, and picking one would call the type a node on the strength of a table the author
     * never named; naming the ambiguity is the detection stratum's job.
     */
    @Test
    void anAmbiguousBindingInfersNothing() {
        withCollidingInventory(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            for (String schema : List.of(PUBLIC, "legacy")) {
                seedStatedNodeMetadata(dsl, PKG, schema, "inventory", "Inventory");
                seedNodeKeyColumn(dsl, PKG, schema, "inventory", 0, "inventory_id");
            }

            assertThat(inferred(dsl))
                .as("two candidates are two key tuples, so neither answers")
                .isEmpty();
        });
    }

    /** The witness columns name the table whose metadata answered, and not merely that one did. */
    @Test
    void theInferenceCarriesTheTableItsMetadataCameFrom() {
        withInventoryCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(dsl.select(INTENT_INFERRED_NODE_TYPE.TABLE_SOURCE_NAME,
                    INTENT_INFERRED_NODE_TYPE.TABLE_SCHEMA, INTENT_INFERRED_NODE_TYPE.TABLE_NAME)
                .from(INTENT_INFERRED_NODE_TYPE)
                .where(INTENT_INFERRED_NODE_TYPE.GRAPH_NAME.eq(GRAPH))
                .fetch()
                .map(r -> r.value1() + "/" + r.value2() + "/" + r.value3()))
                .containsExactly(PKG + "/" + PUBLIC + "/inventory");
        });
    }

    // ===== The membership reduction =====

    /** An authored {@code @node} is a node with no catalog in the store at all. */
    @Test
    void anAuthoredNodeNeedsNoCatalog() {
        withSeededStore(GRAPH, dsl -> {
            seedNode(dsl, GRAPH, "Film");

            assertThat(nodeTypes(dsl)).containsExactly("Film");
            assertThat(inferred(dsl)).isEmpty();
        });
    }

    /**
     * A declared node that implements nothing still reads as a node, which is the live predicate's
     * own answer: its first arm returns on the directive's presence. Whether that shape is usable is
     * a question the classifier asks, and a membership relation that dropped the row would leave the
     * detection with nothing to detect.
     */
    @Test
    void anAuthoredNodeWithoutTheInterfaceIsStillANode() {
        withSeededStore(GRAPH, dsl -> {
            seedNode(dsl, GRAPH, "Film");
            assertThat(nodeTypes(dsl)).containsExactly("Film");
        });
    }

    /**
     * A type both arms answer for is one row. The reduction dedupes rather than ranking, which is
     * what dissolves the live predicate's declared-wins short-circuit: with no provenance column
     * there is no precedence to state, and a reader wanting to know which rule answered reads the
     * arm.
     */
    @Test
    void aTypeBothArmsAnswerForIsOneRow() {
        withInventoryCatalog(dsl -> {
            seedNode(dsl, GRAPH, "Inventory");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(inferred(dsl)).containsExactly("Inventory");
            assertThat(nodeTypes(dsl))
                .as("the union dedupes, so precedence never arises")
                .containsExactly("Inventory");
        });
    }

    /** The graph partition holds: a sibling graph's node is not this graph's. */
    @Test
    void aSiblingGraphsNodeIsNotThisGraphs() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, "other");
            seedNode(dsl, "other", "Film");

            assertThat(nodeTypes(dsl)).isEmpty();
            assertThat(nodeTypes(dsl, "other")).containsExactly("Film");
        });
    }

    /**
     * A sibling graph's source is not this graph's either, so the same catalog table infers nodehood
     * only for the graph whose run read the package. The scoping comes through
     * {@code store_graph_source}, which the binding this view stands on already applies.
     */
    @Test
    void aTableInASourceThisGraphDidNotReadInfersNothing() {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraph(dsl, "other");
            seedGraphSource(dsl, "other", PKG);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            seedTableBinding(dsl, GRAPH, "Inventory", "inventory");
            seedImplements(dsl, GRAPH, "Inventory", "Node");
            seedStatedNodeMetadata(dsl, PKG, PUBLIC, "inventory", "Inventory");
            seedNodeKeyColumn(dsl, PKG, PUBLIC, "inventory", 0, "inventory_id");

            assertThat(inferred(dsl)).isEmpty();
        });
    }

    // ===== Fixtures =====

    /** An {@code inventory} table with one column, under a source this graph read. */
    private static void withInventoryCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedTable(dsl, PKG, PUBLIC, "inventory");
            seedColumn(dsl, PKG, PUBLIC, "inventory", "inventory_id", 0, "inventoryId");
            body.accept(dsl);
        });
    }

    /** The same table declared in two schemas, so a spelling naming it binds two candidates. */
    private static void withCollidingInventory(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String schema : List.of(PUBLIC, "legacy")) {
                seedTable(dsl, PKG, schema, "inventory");
                seedColumn(dsl, PKG, schema, "inventory", "inventory_id", 0, "inventoryId");
            }
            body.accept(dsl);
        });
    }

    // ===== Reads =====

    private static List<String> inferred(DSLContext dsl) {
        return dsl.select(INTENT_INFERRED_NODE_TYPE.TYPE_NAME)
            .from(INTENT_INFERRED_NODE_TYPE)
            .where(INTENT_INFERRED_NODE_TYPE.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_INFERRED_NODE_TYPE.TYPE_NAME)
            .fetch(INTENT_INFERRED_NODE_TYPE.TYPE_NAME);
    }

    private static List<String> nodeTypes(DSLContext dsl) {
        return nodeTypes(dsl, GRAPH);
    }

    private static List<String> nodeTypes(DSLContext dsl, String graphName) {
        return dsl.select(INTENT_NODE_TYPE.TYPE_NAME)
            .from(INTENT_NODE_TYPE)
            .where(INTENT_NODE_TYPE.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_NODE_TYPE.TYPE_NAME)
            .fetch(INTENT_NODE_TYPE.TYPE_NAME);
    }
}
