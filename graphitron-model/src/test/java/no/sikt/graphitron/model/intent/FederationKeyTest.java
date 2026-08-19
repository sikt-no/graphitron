package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.INTENT_SYNTHESIZED_FEDERATION_KEY;
import static no.sikt.graphitron.model.test.SeededStore.seedFederationKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedLink;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_synthesized_federation_key} and {@code intent_federation_key} return:
 * federation's node-entity rule stated as rows, and the composition of authored keys with the ones
 * the rule produces. The generator makes the same decision live in its registry rewrite, so what
 * these cases pin is that the rule's three conditions and the reduction's grain come out the way that
 * rewrite would answer.
 *
 * <p>The third condition is where the cases concentrate, because it is the one with a shape rather
 * than a presence. Synthesis stands down on an authored key whose decode is exactly the single path
 * {@code id}, and on nothing else: a compound key, a key naming another field, a key naming a nested
 * path, and a malformed {@code fields:} argument that decoded to no field rows at all are each an
 * alternative or a misuse rather than the id contract, and each has to leave the rule firing. The
 * malformed case is the deliberate asymmetry: declining on it would suppress the entity declaration
 * on the strength of a parse failure and leave the misuse undetected.
 */
class FederationKeyTest {

    private static final String GRAPH = "g";

    /** The federation spec prefix as a url a real {@code @link} carries. */
    private static final String FEDERATION_URL = "https://specs.apollo.dev/federation/v2.10";

    // ===== The opt-in =====

    /** A node type in a federation-linked graph with no key of its own gets one. */
    @Test
    void aKeylessNodeInALinkedGraphGetsAKey() {
        withLinkedGraph(dsl -> {
            seedNode(dsl, GRAPH, "Film");

            assertThat(synthesized(dsl)).containsExactly("Film");
            assertThat(dsl.select(INTENT_SYNTHESIZED_FEDERATION_KEY.FIELDS_SDL,
                    INTENT_SYNTHESIZED_FEDERATION_KEY.RESOLVABLE)
                .from(INTENT_SYNTHESIZED_FEDERATION_KEY)
                .fetch()
                .map(r -> r.value1() + ":" + r.value2()))
                .as("the rule's constants are the relation's columns")
                .containsExactly("id:true");
        });
    }

    /** No {@code @link} at all, no rule: federation is opt-in and absence is the default. */
    @Test
    void anUnlinkedGraphSynthesizesNothing() {
        withSeededStore(GRAPH, dsl -> {
            seedNode(dsl, GRAPH, "Film");
            assertThat(synthesized(dsl)).isEmpty();
        });
    }

    /** A {@code @link} to another spec is not the opt-in, the condition being a prefix over the url. */
    @Test
    void aLinkToAnotherSpecIsNotTheOptIn() {
        withSeededStore(GRAPH, dsl -> {
            seedLink(dsl, GRAPH, 0, "https://specs.apollo.dev/tag/v0.3");
            seedNode(dsl, GRAPH, "Film");
            assertThat(synthesized(dsl)).isEmpty();
        });
    }

    /**
     * A {@code @link} whose url the author omitted is a null, and matches no prefix. This is the live
     * predicate's null guard falling out of the join rather than a condition spelled twice.
     */
    @Test
    void aLinkWithNoUrlIsNotTheOptIn() {
        withSeededStore(GRAPH, dsl -> {
            seedLink(dsl, GRAPH, 0, null);
            seedNode(dsl, GRAPH, "Film");
            assertThat(synthesized(dsl)).isEmpty();
        });
    }

    /**
     * One federation link among several {@code @link} applications is enough, and a type gets one row
     * rather than one per link. The opt-in is a property of the graph, so the condition is existence
     * and the membership key is the type.
     */
    @Test
    void oneFederationLinkAmongSeveralIsTheOptInAndYieldsOneRow() {
        withSeededStore(GRAPH, dsl -> {
            seedLink(dsl, GRAPH, 0, "https://specs.apollo.dev/tag/v0.3");
            seedLink(dsl, GRAPH, 1, FEDERATION_URL);
            seedLink(dsl, GRAPH, 2, "https://specs.apollo.dev/federation/v2.3");
            seedNode(dsl, GRAPH, "Film");

            assertThat(synthesized(dsl)).containsExactly("Film");
        });
    }

    /** A non-node type gets nothing, however federated the graph is. */
    @Test
    void aTypeThatIsNotANodeGetsNothing() {
        withLinkedGraph(dsl -> assertThat(synthesized(dsl)).isEmpty());
    }

    /** The graph partition holds on both conditions: a sibling graph's link is not this graph's. */
    @Test
    void aSiblingGraphsLinkDoesNotFederateThisGraph() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, "other");
            seedLink(dsl, "other", 0, FEDERATION_URL);
            seedNode(dsl, GRAPH, "Film");

            assertThat(synthesized(dsl)).isEmpty();
        });
    }

    // ===== The authored-id-key stand-down, and the four shapes that are not it =====

    /** The one shape that stands the rule down: exactly the single path {@code id}. */
    @Test
    void anAuthoredIdKeyStandsTheRuleDown() {
        withLinkedGraph(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            seedFederationKey(dsl, GRAPH, "Film", 0, "id", false, "id");

            assertThat(synthesized(dsl)).isEmpty();
            assertThat(composed(dsl))
                .as("the author's opt-out survives, which is what standing down protects")
                .containsExactly("0:id:false");
        });
    }

    /** A key naming another field is an alternative, so the rule still fires beside it. */
    @Test
    void anOtherFieldKeyIsAnAlternative() {
        withLinkedGraph(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            seedFederationKey(dsl, GRAPH, "Film", 0, "title", null, "title");

            assertThat(synthesized(dsl)).containsExactly("Film");
            assertThat(composed(dsl)).containsExactlyInAnyOrder("0:title:null", "null:id:true");
        });
    }

    /** A compound key names two paths, so it is not the single-path id contract. */
    @Test
    void aCompoundKeyIsAnAlternative() {
        withLinkedGraph(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            seedFederationKey(dsl, GRAPH, "Film", 0, "id title", null, "id", "title");

            assertThat(synthesized(dsl)).containsExactly("Film");
        });
    }

    /** A nested path is one selection of two segments, so it is not the id contract either. */
    @Test
    void aNestedPathIsAnAlternative() {
        withLinkedGraph(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            seedFederationKey(dsl, GRAPH, "Film", 0, "author { id }", null, "author.id");

            assertThat(synthesized(dsl)).containsExactly("Film");
        });
    }

    /**
     * A {@code fields:} argument the grammar could not read decodes to no field rows, and the rule
     * fires. That asymmetry is deliberate: declining here would suppress the entity declaration
     * because a parse failed, and the misuse would reach no detection.
     */
    @Test
    void aMalformedFieldSetDoesNotStandTheRuleDown() {
        withLinkedGraph(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            seedFederationKey(dsl, GRAPH, "Film", 0, "id {", null);

            assertThat(synthesized(dsl))
                .as("no field rows, so nothing states the id contract")
                .containsExactly("Film");
        });
    }

    /**
     * One id key among several applications stands the rule down: the condition is that no authored
     * key states the contract, not that the first one does.
     */
    @Test
    void anIdKeyAtALaterOrdinalStandsTheRuleDown() {
        withLinkedGraph(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            seedFederationKey(dsl, GRAPH, "Film", 0, "title", null, "title");
            seedFederationKey(dsl, GRAPH, "Film", 1, "id", null, "id");

            assertThat(synthesized(dsl)).isEmpty();
        });
    }

    // ===== The reduction's grain =====

    /**
     * Two authored id keys at distinct ordinals stay two rows. This is why the reduction is a
     * {@code UNION ALL} rather than a {@code UNION}: the authored relation's arity is what the
     * reduction owes its readers, and deduplicating would silently fold a repeated application away.
     */
    @Test
    void twoAuthoredIdKeysAtDistinctOrdinalsStayTwoRows() {
        withLinkedGraph(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            seedFederationKey(dsl, GRAPH, "Film", 0, "id", null, "id");
            seedFederationKey(dsl, GRAPH, "Film", 1, "id", null, "id");

            assertThat(synthesized(dsl)).isEmpty();
            assertThat(composed(dsl)).containsExactlyInAnyOrder("0:id:null", "1:id:null");
        });
    }

    /** A derived row carries no ordinal, a derivation having no position in a document. */
    @Test
    void aDerivedRowHasNoOrdinal() {
        withLinkedGraph(dsl -> {
            seedNode(dsl, GRAPH, "Film");
            assertThat(composed(dsl)).containsExactly("null:id:true");
        });
    }

    /** An unlinked graph's authored keys still compose; the reduction is not gated on federation. */
    @Test
    void anUnlinkedGraphsAuthoredKeysStillCompose() {
        withSeededStore(GRAPH, dsl -> {
            seedNode(dsl, GRAPH, "Film");
            seedFederationKey(dsl, GRAPH, "Film", 0, "title", true, "title");

            assertThat(composed(dsl)).containsExactly("0:title:true");
        });
    }

    // ===== Fixtures =====

    /** A graph carrying the federation {@code @link} and nothing else. */
    private static void withLinkedGraph(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedLink(dsl, GRAPH, 0, FEDERATION_URL);
            body.accept(dsl);
        });
    }

    // ===== Reads =====

    private static List<String> synthesized(DSLContext dsl) {
        return dsl.select(INTENT_SYNTHESIZED_FEDERATION_KEY.TYPE_NAME)
            .from(INTENT_SYNTHESIZED_FEDERATION_KEY)
            .where(INTENT_SYNTHESIZED_FEDERATION_KEY.GRAPH_NAME.eq(GRAPH))
            .orderBy(INTENT_SYNTHESIZED_FEDERATION_KEY.TYPE_NAME)
            .fetch(INTENT_SYNTHESIZED_FEDERATION_KEY.TYPE_NAME);
    }

    /** The composed keys as {@code ordinal:fields:resolvable}, nulls rendered. */
    private static List<String> composed(DSLContext dsl) {
        return dsl.select(INTENT_FEDERATION_KEY.ORDINAL, INTENT_FEDERATION_KEY.FIELDS_SDL,
                INTENT_FEDERATION_KEY.RESOLVABLE)
            .from(INTENT_FEDERATION_KEY)
            .where(INTENT_FEDERATION_KEY.GRAPH_NAME.eq(GRAPH))
            .fetch()
            .map(r -> r.value1() + ":" + r.value2() + ":" + r.value3());
    }
}
