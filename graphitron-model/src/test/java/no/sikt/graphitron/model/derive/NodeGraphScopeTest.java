package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.TestRunContext;
import org.assertj.core.groups.Tuple;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEYCOLUMN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Nodes and their key columns are a graph's own, which is the one thing a fact document cannot
 * state, a document being one graph. Three graphs share a store here: two read the fixture
 * catalog and bind the same table, one inferring its key from the published metadata and one
 * pinning another column, and the third reads no catalog at all and binds a table the store
 * holds only for the others.
 */
class NodeGraphScopeTest {

    private static final String INFERRED = """
        interface Node { id: ID! }
        type Keyed implements Node @table(name: "published_elsewhere") { id: ID! }
        type Query { keyed: [Keyed!]! }
        """;

    private static final String PINNED = """
        interface Node { id: ID! }
        type Keyed implements Node @table(name: "published_elsewhere") @node(keyColumns: ["alt"]) { id: ID! }
        type Query { keyed: [Keyed!]! }
        """;

    @TempDir
    Path tmp;

    @Test
    void aSiblingGraphsPinnedListDoesNotAnswerHere() {
        try (var store = threeGraphs()) {
            assertThat(keys(store.dsl(), "inferred"))
                .as("this graph keeps its own tier rather than borrowing the sibling's pinned list")
                .containsExactly(tuple("code", "JOOQ_METADATA"));
            assertThat(keys(store.dsl(), "pinned")).containsExactly(tuple("alt", "SDL_PINNED"));
        }
    }

    @Test
    void aTableInACatalogThisGraphDidNotReadMakesNoNode() {
        try (var store = threeGraphs()) {
            assertThat(nodes(store.dsl(), "inferred")).containsExactly("Keyed");
            assertThat(nodes(store.dsl(), "uncatalogued"))
                .as("the store holds the table, for the other graphs; this one never read it")
                .isEmpty();
            assertThat(keys(store.dsl(), "uncatalogued")).isEmpty();
        }
    }

    private CapturedStore threeGraphs() {
        var jooq = CatalogInput.of(TestRunContext.of());
        return CapturedStore.ownStoreOfCatalog(tmp, "inferred", INFERRED, jooq)
            .andCatalogGraph("pinned", PINNED, jooq)
            .andGraph("uncatalogued", PINNED);
    }

    private static List<String> nodes(DSLContext dsl, String graph) {
        return dsl.select(GRAPHITRON_NODE.TYPE_NAME).from(GRAPHITRON_NODE)
            .where(GRAPHITRON_NODE.GRAPH_NAME.eq(graph))
            .orderBy(GRAPHITRON_NODE.TYPE_NAME)
            .fetch(GRAPHITRON_NODE.TYPE_NAME);
    }

    private static List<Tuple> keys(DSLContext dsl, String graph) {
        var k = GRAPHITRON_NODE_KEYCOLUMN;
        return dsl.select(k.COLUMN_NAME, k.COLUMN_ORIGIN).from(k)
            .where(k.GRAPH_NAME.eq(graph))
            .orderBy(k.TYPE_NAME, k.POSITION)
            .fetch(r -> tuple(r.value1(), r.value2()));
    }
}
