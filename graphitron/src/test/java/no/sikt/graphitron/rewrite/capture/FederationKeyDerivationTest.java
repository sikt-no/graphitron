package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.INTENT_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.INTENT_INFERRED_NODE_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_SYNTHESIZED_FEDERATION_KEY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Federation's node-entity rule, over a real capture: a node type without an authored
 * {@code @key(fields: "id")} gets one, and the rule now answers as a derivation instead of as a
 * write the capture walk performed.
 *
 * <p>What each case reads is the point. Nothing lands in {@code graphql_type_directive} or
 * {@code graphitron_federation_key} for a synthesized key any more, so those two relations are
 * asserted <em>empty</em> at the synthesized coordinate and the membership is read from
 * {@code intent_synthesized_federation_key}. That is the whole of the move: at this coordinate
 * stratum one is now pure transcription of the SDL, and a reader wanting every key the emitted
 * schema carries reads the composed reduction.
 *
 * <p>These are capture-driven cases rather than seeded ones, which is what the module boundary asks
 * for: the rule's own algebra is pinned row-in-verdict-out in {@code graphitron-model}, and what
 * these add is that a real {@code .graphqls} read by the real walk, against a real generated jOOQ
 * catalog, produces the rows that algebra needs.
 */
@UnitTier
class FederationKeyDerivationTest {

    private static final String DIRECTIVES = """
        directive @link(url: String!, import: [String]) repeatable on SCHEMA
        directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT
        """;

    private static final String LINK =
        "extend schema @link(url: \"https://specs.apollo.dev/federation/v2.10\", import: [\"@key\"])";

    private static final String FEDERATED = DIRECTIVES + LINK + """


        type Query { film: Film }

        interface Node { id: ID! }

        type Film implements Node @node {
          id: ID!
          title: String
        }
        """;

    /**
     * An {@code @table} type implementing {@code Node} with no {@code @node} of its own. Nodehood
     * here is a conjunction over two corpora, so the case only means anything with a real catalog
     * behind it; {@code film_actor} is the fixture table that publishes the node-identity constants.
     */
    private static final String INFERRED = DIRECTIVES + LINK + """


        type Query { pairing: Pairing }

        interface Node { id: ID! }

        type Pairing implements Node @table(name: "film_actor") {
          id: ID!
        }
        """;

    @Test
    @DisplayName("a node type without an id key gets one, and it is a derived row rather than a captured one")
    void federationKeyIsDerivedForNodes(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FEDERATED)) {
            var synthesized = store.dsl()
                .select(INTENT_SYNTHESIZED_FEDERATION_KEY.FIELDS_SDL,
                    INTENT_SYNTHESIZED_FEDERATION_KEY.RESOLVABLE)
                .from(INTENT_SYNTHESIZED_FEDERATION_KEY)
                .where(INTENT_SYNTHESIZED_FEDERATION_KEY.TYPE_NAME.eq("Film"))
                .fetchSingle();
            assertThat(synthesized.value1()).isEqualTo("id");
            assertThat(synthesized.value2()).isTrue();

            assertThat(keyApplicationsOf(store.dsl(), "Film"))
                .as("the SDL declares no @key, so the transcription holds none")
                .isEmpty();
            assertThat(store.dsl().fetchCount(GRAPHITRON_FEDERATION_KEY))
                .as("the decode relation holds authored applications alone")
                .isZero();

            assertThat(composedKeysOf(store.dsl(), "Film"))
                .as("the reduction is where a reader sees the key, with no ordinal on a derived row")
                .containsExactly("null:id");
        }
    }

    /**
     * An authored id key stands the rule down, and the explicit {@code resolvable: false} that keeps
     * the type out of {@code _Entity} survives into the reduction unaltered. That is the reason the
     * stand-down is a condition on the rule rather than a precedence in the reduction: a synthesized
     * row asserting {@code resolvable} true beside this one would put the opt-out back.
     */
    @Test
    @DisplayName("an authored id key stands the derivation down, opt-out intact")
    void anAuthoredIdKeyStandsTheDerivationDown(@TempDir Path tmp) {
        String sdl = FEDERATED.replace("type Film implements Node @node {",
            "type Film implements Node @node @key(fields: \"id\", resolvable: false) {");
        try (var store = CapturedStore.of(tmp, sdl)) {
            assertThat(store.dsl().fetchCount(INTENT_SYNTHESIZED_FEDERATION_KEY)).isZero();
            assertThat(store.dsl()
                .select(INTENT_FEDERATION_KEY.RESOLVABLE)
                .from(INTENT_FEDERATION_KEY)
                .where(INTENT_FEDERATION_KEY.TYPE_NAME.eq("Film"))
                .fetchSingle().value1()).isFalse();
        }
    }

    /**
     * The two-corpus arm, both ways round. With the catalog captured, {@code film_actor}'s published
     * metadata makes {@code Pairing} a node and the key is derived; with no catalog captured there
     * are no metadata rows to conjoin and nothing is. The second arm is what says the inference is
     * reading captured facts rather than a live probe: the SDL is byte-identical across the two.
     */
    @Test
    @DisplayName("an inferred node gets a key; with no catalog captured, it does not")
    void nodeInferenceDecidesWhetherAnInferredNodeGetsAKey(@TempDir Path tmp) {
        try (var store = CapturedStore.ofCatalog(tmp.resolve("inferred"), INFERRED,
                new JooqCatalog(TestConfiguration.DEFAULT_JOOQ_PACKAGE))) {
            assertThat(store.dsl().fetchCount(INTENT_SYNTHESIZED_FEDERATION_KEY,
                INTENT_SYNTHESIZED_FEDERATION_KEY.TYPE_NAME.eq("Pairing")))
                .as("film_actor publishes node metadata, so Pairing is a node without saying @node")
                .isOne();

            assertThat(store.dsl()
                .select(INTENT_INFERRED_NODE_TYPE.TABLE_SCHEMA, INTENT_INFERRED_NODE_TYPE.TABLE_NAME)
                .from(INTENT_INFERRED_NODE_TYPE)
                .where(INTENT_INFERRED_NODE_TYPE.TYPE_NAME.eq("Pairing"))
                .fetch()
                .map(r -> r.value1() + "." + r.value2()))
                .as("the witness columns name the table whose metadata answered")
                .containsExactly("public.film_actor");
        }
        try (var store = CapturedStore.of(tmp.resolve("bare"), INFERRED)) {
            assertThat(store.dsl().fetchCount(INTENT_SYNTHESIZED_FEDERATION_KEY))
                .as("no catalog facts to conjoin, so nothing infers nodehood")
                .isZero();
            assertThat(store.dsl().fetchCount(INTENT_INFERRED_NODE_TYPE)).isZero();
        }
    }

    /**
     * An other-field key is an additional alternative rather than the id contract, so the rule still
     * fires and the two coexist on one type. There is no ordinal interleaving left to preserve: the
     * authored row keeps its document position and the derived one has none, and a reader wanting a
     * total order appends the derived row after the authored ones in its own query.
     */
    @Test
    @DisplayName("an authored non-id key and a derived key coexist on one type")
    void anOtherFieldKeyLeavesTheDerivedKeyBesideIt(@TempDir Path tmp) {
        String sdl = FEDERATED.replace("type Film implements Node @node {",
            "type Film implements Node @node @key(fields: \"title\") {");
        try (var store = CapturedStore.of(tmp, sdl)) {
            assertThat(composedKeysOf(store.dsl(), "Film"))
                .containsExactlyInAnyOrder("0:title", "null:id");
            assertThat(keyApplicationsOf(store.dsl(), "Film"))
                .as("only the authored application is transcribed")
                .containsExactly(0);
        }
    }

    /**
     * A compound key names more than one field, so it is not the id contract either, and neither is
     * a single key naming a nested path. Both are shapes the decode produces rows for, which is what
     * makes them worth a case: the rule's condition is a count over those rows rather than a reading
     * of the {@code fields:} literal.
     */
    @Test
    @DisplayName("a compound key and a nested path are both alternatives, not the id contract")
    void neitherACompoundKeyNorANestedPathStandsTheDerivationDown(@TempDir Path tmp) {
        String compound = FEDERATED.replace("type Film implements Node @node {",
            "type Film implements Node @node @key(fields: \"id title\") {");
        try (var store = CapturedStore.of(tmp.resolve("compound"), compound)) {
            assertThat(store.dsl().fetchCount(INTENT_SYNTHESIZED_FEDERATION_KEY,
                INTENT_SYNTHESIZED_FEDERATION_KEY.TYPE_NAME.eq("Film"))).isOne();
        }
        String nested = FEDERATED.replace("title: String", "title: Title")
            + """

            type Title implements Node @node { id: ID! }
            """;
        nested = nested.replace("type Film implements Node @node {",
            "type Film implements Node @node @key(fields: \"title { id }\") {");
        try (var store = CapturedStore.of(tmp.resolve("nested"), nested)) {
            assertThat(store.dsl().fetchCount(INTENT_SYNTHESIZED_FEDERATION_KEY,
                INTENT_SYNTHESIZED_FEDERATION_KEY.TYPE_NAME.eq("Film"))).isOne();
        }
    }

    @Test
    @DisplayName("no federation link, no derived key")
    void theDerivationNeedsAFederationLink(@TempDir Path tmp) {
        String sdl = FEDERATED.replace(LINK, "");
        try (var store = CapturedStore.of(tmp, sdl)) {
            assertThat(store.dsl().fetchCount(INTENT_SYNTHESIZED_FEDERATION_KEY)).isZero();
            assertThat(store.dsl().fetchCount(INTENT_FEDERATION_KEY)).isZero();
        }
    }

    /**
     * A {@code @link} to something other than the federation spec is not the opt-in. The condition
     * is a prefix over the decoded url, so a link that shares the host and not the path answers no.
     */
    @Test
    @DisplayName("a non-federation link is not the opt-in")
    void aLinkToAnotherSpecIsNotTheOptIn(@TempDir Path tmp) {
        String sdl = FEDERATED.replace(LINK,
            "extend schema @link(url: \"https://specs.apollo.dev/tag/v0.3\", import: [\"@tag\"])");
        try (var store = CapturedStore.of(tmp, sdl)) {
            assertThat(store.dsl().fetchCount(INTENT_SYNTHESIZED_FEDERATION_KEY)).isZero();
        }
    }

    /** The ordinals of the {@code @key} applications transcribed for a type, in order. */
    private static List<Integer> keyApplicationsOf(DSLContext dsl, String typeName) {
        return dsl.select(GRAPHQL_TYPE_DIRECTIVE.ORDINAL)
            .from(GRAPHQL_TYPE_DIRECTIVE)
            .where(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME.eq(typeName))
            .and(GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME.eq("key"))
            .orderBy(GRAPHQL_TYPE_DIRECTIVE.ORDINAL)
            .fetch(GRAPHQL_TYPE_DIRECTIVE.ORDINAL);
    }

    /** A type's composed keys as {@code ordinal:fields}, the derived row rendering its null ordinal. */
    private static List<String> composedKeysOf(DSLContext dsl, String typeName) {
        return dsl.select(INTENT_FEDERATION_KEY.ORDINAL, INTENT_FEDERATION_KEY.FIELDS_SDL)
            .from(INTENT_FEDERATION_KEY)
            .where(INTENT_FEDERATION_KEY.TYPE_NAME.eq(typeName))
            .fetch()
            .map(r -> r.value1() + ":" + r.value2());
    }
}
