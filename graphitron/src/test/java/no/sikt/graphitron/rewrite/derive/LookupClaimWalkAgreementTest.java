package no.sikt.graphitron.rewrite.derive;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.facts.GatheredFacts;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.SchemaReachability;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CLAIM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lookup trigger predicate has two homes, the walk's {@code LookupFacts.triggersFor} and the
 * stored lookupKey claims, and this binds them over a marked argument, an input-typed argument
 * without the directive, and an untouched control. It goes when the walk does.
 */
@PipelineTier
class LookupClaimWalkAgreementTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    void lookupClaimsAgreeWithTheWalkedTriggerPredicate() {
        var sdl = """
            type Language @table(name: "language") { name: String }
            input LookupInput { language_id: Int }
            type Query {
                direct(language_id: [Int] @lookupKey): [Language!]!
                byInput(in: LookupInput): [Language!]!
                untouched: [Language!]!
            }
            """;
        var bundle = TestSchemaHelper.walkBundle(sdl);
        var nodes = TestSchemaHelper.nodeDeclaration();
        var facts = GatheredFacts.gather(bundle.assembled(), (s, v) -> SchemaReachability.walk(s, nodes, v));
        var triggered = bundle.assembled().getQueryType().getFieldDefinitions().stream()
            .filter(f -> facts.lookup().triggersFor(f))
            .map(GraphQLFieldDefinition::getName)
            .collect(Collectors.toSet());
        assertThat(triggered).containsExactlyInAnyOrder("direct");

        var claimed = FACTS.readInline("lookup-claims", sdl, null, graph ->
            graph.handle().dsl().selectDistinct(GRAPHITRON_CLAIM.COORDINATE)
                .from(GRAPHITRON_CLAIM)
                .where(GRAPHITRON_CLAIM.GRAPH_NAME.eq(graph.handle().graphName()),
                    GRAPHITRON_CLAIM.COORDINATE.startsWith("Query."),
                    GRAPHITRON_CLAIM.KIND.eq("LOOKUP"))
                .fetchSet(r -> r.value1().substring("Query.".length())));
        assertThat(claimed).isEqualTo(triggered);
    }
}
