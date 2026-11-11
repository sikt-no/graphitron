package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NodeIdEncoderQuery}'s nodes over the {@code scenarios/synthesized-node-type-id} fact document:
 * every node of the graph draws a row with the type id it answers to and its key columns in key order
 * on its own table, a federation stub's included, since a stub's id encodes and decodes like any
 * node's. These rows are the whole of what {@code NodeIdEncoder}'s per-node helpers are made of.
 */
@PipelineTier
class NodeIdEncoderQueryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static List<NodeIdEncoderQuery.Node> nodes;

    @BeforeAll
    static void read() {
        nodes = FACTS.read("scenarios/synthesized-node-type-id", graph -> NodeIdEncoderQuery.read(graph.handle()));
    }

    private static Map<String, NodeIdEncoderQuery.Node> byType() {
        return nodes.stream().collect(Collectors.toMap(NodeIdEncoderQuery.Node::typeName, Function.identity()));
    }

    private static List<String> keyOf(String typeName) {
        return byType().get(typeName).keyColumns().stream().map(ColumnRef::sqlName).toList();
    }

    @Test
    void everyNodeDrawsARowInTypeNameOrderAStubIncluded() {
        assertThat(nodes).extracting(NodeIdEncoderQuery.Node::typeName)
            .containsExactly("Customer", "Film", "FilmActor", "Level");
    }

    @Test
    void eachRowCarriesTheTypeIdTheNodeAnswersTo() {
        assertThat(nodes).extracting(NodeIdEncoderQuery.Node::typeId)
            .containsExactly("Customer", "F", "FilmActor", "StudyLevel");
    }

    @Test
    void theKeyColumnsAreTheNodesOwnInKeyOrder() {
        assertThat(keyOf("Film")).containsExactly("film_id");
        assertThat(keyOf("FilmActor")).containsExactly("actor_id", "film_id");
        assertThat(keyOf("Level")).containsExactly("code");
        assertThat(byType().get("Film").keyColumns()).singleElement()
            .satisfies(column -> {
                assertThat(column.javaName()).isEqualTo("FILM_ID");
                assertThat(column.columnClass()).isEqualTo(Integer.class.getName());
            });
    }

    @Test
    void theTableIsReachedThroughItsSchemasTablesClass() {
        var film = byType().get("Film");
        assertThat(film.tablesClass()).endsWith(".Tables");
        assertThat(film.tableField()).isEqualTo("FILM");
        assertThat(byType().get("Level").tableField()).isEqualTo("STUDY_LEVEL");
    }
}
