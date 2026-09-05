package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE_ARG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code @asConnection} expansion inside the capture walk, which is the whole of what runs
 * there. Its rows go through capture's own doors, so a row it contributes must be present and
 * indistinguishable from an authored one except through its provenance relation; these tests pin
 * both halves of that.
 *
 * <p>Federation's key synthesis used to run here too and no longer does, its rule spanning two
 * corpora and therefore belonging to a derivation over the captured facts of both;
 * {@link FederationKeyDerivationTest} is where that rule's cases live now.
 */
@UnitTier
class MacroCaptureTest {

    private static final String DIRECTIVES = """
        directive @audit(note: String) repeatable on OBJECT
        """;

    /**
     * A repeatable directive split across a base and an extension is the case that catches a
     * per-site ordinal counter: both applications would claim ordinal 0 and the second would
     * quarantine as a duplicate, so the counter has to be type-wide and outlive the site.
     */
    @Test
    @DisplayName("type-directive ordinals run across declaration sites, not within them")
    void repeatedApplicationsNumberAcrossSites(@TempDir Path tmp) {
        String sdl = DIRECTIVES + """
            type Query { ping: String }

            type Film @audit(note: "base") { title: String }

            extend type Film @audit(note: "extension")
            """;
        try (var store = CapturedStore.of(tmp, sdl)) {
            var notes = store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE_ARG.ORDINAL, GRAPHQL_TYPE_DIRECTIVE_ARG.VALUE_SDL)
                .from(GRAPHQL_TYPE_DIRECTIVE_ARG)
                .where(GRAPHQL_TYPE_DIRECTIVE_ARG.TYPE_NAME.eq("Film"))
                .and(GRAPHQL_TYPE_DIRECTIVE_ARG.DIRECTIVE_NAME.eq("audit"))
                .orderBy(GRAPHQL_TYPE_DIRECTIVE_ARG.ORDINAL)
                .fetch();
            assertThat(notes.map(r -> r.value1() + ":" + r.value2()))
                .containsExactly("0:\"base\"", "1:\"extension\"");
        }
    }

    private static final String CONNECTIONS = """
        type Query {
          films: [Film!]! @asConnection
          actors: [Actor] @asConnection(connectionName: "ActorConnection")
          plain: [Film!]!
        }

        type Film { title: String }
        type Actor { name: String }
        """;

    @Test
    @DisplayName("a carrier's field takes the minted Connection, and the written type expression survives")
    void theCarrierFieldIsRewritten(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, CONNECTIONS)) {
            var effective = store.dsl()
                .select(GRAPHITRON_FIELD.FIELD_NAME, GRAPHITRON_FIELD.TYPE_SDL)
                .from(GRAPHITRON_FIELD)
                .where(GRAPHITRON_FIELD.TYPE_NAME.eq("Query"))
                .fetch()
                .intoMap(r -> r.value1(), r -> r.value2());
            assertThat(effective).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "films", "QueryFilmsConnection",
                "actors", "ActorConnection",
                "plain", "[Film!]!"));

            // And the transcription still holds what the author wrote at the same coordinates,
            // which is the direction that used to need a provenance stash to recover.
            var authored = store.dsl()
                .select(GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.TYPE_SDL)
                .from(GRAPHQL_FIELD)
                .where(GRAPHQL_FIELD.TYPE_NAME.eq("Query"))
                .fetch();
            assertThat(authored.map(r -> r.value1() + "=" + r.value2()))
                .containsExactlyInAnyOrder("films=[Film!]!", "actors=[Actor]", "plain=[Film!]!");

            var rewritten = store.dsl()
                .select(GRAPHITRON_FIELD_SYNTHESIS.FIELD_NAME,
                    GRAPHITRON_FIELD_SYNTHESIS.TYPE_SDL,
                    GRAPHITRON_FIELD_SYNTHESIS.MACRO)
                .from(GRAPHITRON_FIELD_SYNTHESIS)
                .fetch();
            assertThat(rewritten.map(r -> r.value1() + "=" + r.value2() + ":" + r.value3()))
                .containsExactlyInAnyOrder("films=QueryFilmsConnection:CONNECTION",
                    "actors=ActorConnection:CONNECTION");
        }
    }

    @Test
    @DisplayName("the Relay machinery is minted with the shapes the assembled schema uses")
    void theMintedShapesMatchTheAssembledOnes(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, CONNECTIONS)) {
            assertThat(fieldsOf(store, "QueryFilmsConnection")).containsExactly(
                "edges=[QueryFilmsEdge!]!", "nodes=[Film!]!", "pageInfo=PageInfo!", "totalCount=Int");
            assertThat(fieldsOf(store, "QueryFilmsEdge")).containsExactly(
                "cursor=String!", "node=Film!");
            // The nullable-item carrier mirrors its element's nullability into both node slots.
            assertThat(fieldsOf(store, "ActorConnection")).contains("nodes=[Actor]!");
            assertThat(fieldsOf(store, "ActorEdge")).containsExactly("cursor=String!", "node=Actor");
            assertThat(fieldsOf(store, "PageInfo")).containsExactly(
                "hasNextPage=Boolean!", "hasPreviousPage=Boolean!",
                "startCursor=String", "endCursor=String");
        }
    }

    @Test
    @DisplayName("every minted type is provenance-marked, and nothing authored is")
    void mintedTypesCarryProvenance(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, CONNECTIONS)) {
            var marked = store.dsl()
                .select(GRAPHITRON_MINTED_TYPE_SITE.TYPE_NAME,
                    GRAPHITRON_MINTED_TYPE_SITE.CARRIER_TYPE_NAME,
                    GRAPHITRON_MINTED_TYPE_SITE.CARRIER_FIELD_NAME)
                .from(GRAPHITRON_MINTED_TYPE_SITE)
                .fetch();
            assertThat(marked.map(r -> r.value1() + "<-" + r.value2() + "." + r.value3()))
                .containsExactlyInAnyOrder(
                    "QueryFilmsConnection<-Query.films", "QueryFilmsEdge<-Query.films",
                    "PageInfo<-Query.films",
                    "ActorConnection<-Query.actors", "ActorEdge<-Query.actors",
                    "PageInfo<-Query.actors");
        }
    }

    /**
     * PageInfo is shared, so its site count is the carrier multiplicity: one definition site and an
     * empty extension site per later carrier. That is what lets a refresh refcount the shared type
     * rather than guess when it is orphaned.
     */
    @Test
    @DisplayName("shared machinery gets one site per carrier")
    void pageInfoSitesCountCarriers(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, CONNECTIONS)) {
            var sites = store.dsl()
                .select(GRAPHITRON_MINTED_TYPE_SITE.MERGE_ORDINAL,
                    GRAPHITRON_MINTED_TYPE_SITE.IS_EXTENSION)
                .from(GRAPHITRON_MINTED_TYPE_SITE)
                .where(GRAPHITRON_MINTED_TYPE_SITE.TYPE_NAME.eq("PageInfo"))
                .orderBy(GRAPHITRON_MINTED_TYPE_SITE.MERGE_ORDINAL)
                .fetch();
            assertThat(sites.map(r -> r.value1() + ":" + r.value2()))
                .containsExactly("0:false", "1:true");
        }
    }

    /**
     * An author who declares the name a macro would mint keeps their declaration: capture is
     * first-wins, and the collision is theirs to resolve rather than a constraint violation.
     */
    @Test
    @DisplayName("an authored declaration wins a name collision with the mint")
    void anAuthoredNameWinsTheCollision(@TempDir Path tmp) {
        String sdl = """
            type Query { films: [Film!]! @asConnection }
            type Film { title: String }
            type QueryFilmsConnection { mine: String }
            """;
        try (var store = CapturedStore.of(tmp, sdl)) {
            assertThat(fieldsOf(store, "QueryFilmsConnection")).containsExactly("mine=String");
            assertThat(store.dsl().fetchCount(GRAPHITRON_MINTED_TYPE_SITE,
                GRAPHITRON_MINTED_TYPE_SITE.TYPE_NAME.eq("QueryFilmsConnection"))).isZero();
        }
    }

    private static java.util.List<String> fieldsOf(CapturedStore store, String typeName) {
        return store.dsl()
            .select(GRAPHITRON_FIELD.FIELD_NAME, GRAPHITRON_FIELD.TYPE_SDL)
            .from(GRAPHITRON_FIELD)
            .where(GRAPHITRON_FIELD.TYPE_NAME.eq(typeName))
            .orderBy(GRAPHITRON_FIELD.ORDINAL)
            .fetch()
            .map(r -> r.value1() + "=" + r.value2());
    }
}
