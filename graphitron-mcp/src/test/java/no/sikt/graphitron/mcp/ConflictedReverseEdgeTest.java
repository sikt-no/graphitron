package no.sikt.graphitron.mcp;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The motivating query behind the edge-bearing {@code Conflicted} arm, asserted rather than
 * narrated: "which delete mutations target table X" must include the broken population. A
 * conflicted mutation whose {@code Mutation} claim carries a table produces one TARGETS edge,
 * and the reverse index answers the table's node with the broken field; a claim without a table
 * slot, and the method-pair claims, deliberately produce none (the item's closed edge-placement
 * decision: table edges only). This is {@code Conflicted}'s live instrument in
 * {@link EdgeProducer#EDGE_BEARING_FIELDS}; without it the partition entry would pin an arm
 * nothing exercises.
 */
class ConflictedReverseEdgeTest {

    @Test
    void conflictedDeleteAppearsUnderItsTargetTable() {
        var conflicted = new FieldClassification.Conflicted(List.of(
            new FieldClassification.Claim.Service("com.example.FilmService", "delete", "service", true, null),
            new FieldClassification.Claim.Mutation("DELETE", "film", "mutation", true, null)),
            "@service, @mutation are mutually exclusive");
        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(),
            Map.of("Mutation.deleteFilm", conflicted), Map.of());

        var index = ReverseEdgeIndex.build(snapshot,
            new EdgeProducer.Context(CatalogFacts.empty(), List.of()));

        // The empty catalog resolves nothing, so the edge degrades to the unqualified table
        // node, the same best-effort node every other arm's TARGETS edge lands on.
        var atFilm = index.reverseEdges(new NodeRef.TableNode("", "film").wireId());
        assertThat(atFilm)
            .as("the broken DELETE is included in the population targeting its table")
            .anySatisfy(edge -> {
                assertThat(edge.kind()).isEqualTo(EdgeKind.TARGETS);
                assertThat(edge.target()).isEqualTo(new NodeRef.FieldNode("Mutation.deleteFilm"));
            });
    }

    @Test
    void claimsWithoutATableSlotProduceNoEdge() {
        var conflicted = new FieldClassification.Conflicted(List.of(
            new FieldClassification.Claim.Service("com.example.FilmService", "delete", "service", true, null),
            new FieldClassification.Claim.Mutation(null, null, "mutation", false, null)),
            "@service, @mutation are mutually exclusive");

        var edges = EdgeProducer.fieldEdges(conflicted,
            new EdgeProducer.Context(CatalogFacts.empty(), List.of()));

        assertThat(edges)
            .as("an undecoded mutation claim has no table slot, and the method pair never edges")
            .isEmpty();
    }
}
