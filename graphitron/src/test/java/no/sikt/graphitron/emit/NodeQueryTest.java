package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NodeQuery}'s nodes over the {@code scenarios/node-served-by-id} fact document: each row carries
 * the node's name and the wire type id its ids answer to, in type name order. Which nodes draw a row
 * is {@code graphitron_node_served_by_id}'s population, claimed by the document itself, so this asserts
 * what a row holds.
 */
@PipelineTier
class NodeQueryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static List<NodeQuery.Node> nodes;

    @BeforeAll
    static void read() {
        nodes = FACTS.read("scenarios/node-served-by-id", graph -> NodeQuery.read(graph.handle()));
    }

    @Test
    void eachRowCarriesTheTypeIdItsNodesIdsAnswerTo() {
        assertThat(nodes).contains(new NodeQuery.Node("Customer", "Customer"), new NodeQuery.Node("Film", "F"));
    }

    @Test
    void theRowsAreInTypeNameOrder() {
        assertThat(nodes).extracting(NodeQuery.Node::typeName).isSortedAccordingTo(Comparator.naturalOrder());
    }
}
