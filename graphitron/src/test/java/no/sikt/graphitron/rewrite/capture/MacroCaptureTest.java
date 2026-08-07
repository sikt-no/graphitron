package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_DECLARATION_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE_ARG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Macro expansion inside the capture walk. The store's picture is the effective schema, so a row a
 * macro contributes must be present and indistinguishable from an authored one except through its
 * provenance relation; these tests pin both halves of that.
 */
@UnitTier
class MacroCaptureTest {

    private static final String DIRECTIVES = """
        directive @link(url: String!, import: [String]) repeatable on SCHEMA
        directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT
        directive @audit(note: String) repeatable on OBJECT
        """;

    private static final String FEDERATED = DIRECTIVES + """
        extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])

        type Query { film: Film }

        interface Node { id: ID! }

        type Film implements Node @node {
          id: ID!
          title: String
        }
        """;

    @Test
    @DisplayName("a node type without an id key gets one, transcribed and decoded like an authored key")
    void federationKeyIsSynthesizedOnNodes(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FEDERATED)) {
            var arguments = store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE_ARG.DIRECTIVE_ARGUMENT_NAME,
                    GRAPHQL_TYPE_DIRECTIVE_ARG.VALUE_SDL)
                .from(GRAPHQL_TYPE_DIRECTIVE_ARG)
                .where(GRAPHQL_TYPE_DIRECTIVE_ARG.TYPE_NAME.eq("Film"))
                .and(GRAPHQL_TYPE_DIRECTIVE_ARG.DIRECTIVE_NAME.eq("key"))
                .fetch();
            assertThat(arguments.intoMap(
                GRAPHQL_TYPE_DIRECTIVE_ARG.DIRECTIVE_ARGUMENT_NAME, GRAPHQL_TYPE_DIRECTIVE_ARG.VALUE_SDL))
                .containsExactlyInAnyOrderEntriesOf(
                    java.util.Map.of("fields", "\"id\"", "resolvable", "true"));

            var decoded = store.dsl()
                .select(GRAPHITRON_FEDERATION_KEY.FIELDS_SDL, GRAPHITRON_FEDERATION_KEY.RESOLVABLE)
                .from(GRAPHITRON_FEDERATION_KEY)
                .where(GRAPHITRON_FEDERATION_KEY.TYPE_NAME.eq("Film"))
                .fetchSingle();
            assertThat(decoded.value1()).isEqualTo("id");
            assertThat(decoded.value2()).isTrue();

            assertThat(store.dsl().fetchCount(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS,
                GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS.TYPE_NAME.eq("Film")
                    .and(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS.MACRO.eq("FEDERATION_KEY"))))
                .isOne();
        }
    }

    @Test
    @DisplayName("a synthesized application inherits the position an author can edit")
    void synthesizedKeyInheritsTheDeclarationPosition(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, FEDERATED)) {
            var application = store.dsl()
                .select(GRAPHQL_TYPE_DIRECTIVE.SOURCE_LINE, GRAPHQL_TYPE_DIRECTIVE.SOURCE_COLUMN,
                    GRAPHQL_TYPE_DIRECTIVE.DECLARATION_LINE, GRAPHQL_TYPE_DIRECTIVE.DECLARATION_COLUMN)
                .from(GRAPHQL_TYPE_DIRECTIVE)
                .where(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME.eq("Film"))
                .and(GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME.eq("key"))
                .fetchSingle();
            assertThat(application.value1()).isEqualTo(application.value3());
            assertThat(application.value2()).isEqualTo(application.value4());

            var declaration = store.dsl()
                .select(GRAPHQL_TYPE_DECLARATION.SOURCE_LINE, GRAPHQL_TYPE_DECLARATION.SOURCE_COLUMN)
                .from(GRAPHQL_TYPE_DECLARATION)
                .where(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq("Film"))
                .and(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL.eq(0))
                .fetchSingle();
            assertThat(application.value1()).isEqualTo(declaration.value1());
            assertThat(application.value2()).isEqualTo(declaration.value2());
        }
    }

    @Test
    @DisplayName("an authored id key stands synthesis down")
    void anAuthoredIdKeySuppressesSynthesis(@TempDir Path tmp) {
        String sdl = FEDERATED.replace("type Film implements Node @node {",
            "type Film implements Node @node @key(fields: \"id\", resolvable: false) {");
        try (var store = CapturedStore.of(tmp, sdl)) {
            assertThat(store.dsl().fetchCount(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS)).isZero();
            assertThat(store.dsl()
                .select(GRAPHITRON_FEDERATION_KEY.RESOLVABLE)
                .from(GRAPHITRON_FEDERATION_KEY)
                .where(GRAPHITRON_FEDERATION_KEY.TYPE_NAME.eq("Film"))
                .fetchSingle().value1()).isFalse();
        }
    }

    @Test
    @DisplayName("an other-field key is an alternative, so the id key is still synthesized after it")
    void anOtherFieldKeyLeavesSynthesisToNumberAfterIt(@TempDir Path tmp) {
        String sdl = FEDERATED.replace("type Film implements Node @node {",
            "type Film implements Node @node @key(fields: \"title\") {");
        try (var store = CapturedStore.of(tmp, sdl)) {
            var keys = store.dsl()
                .select(GRAPHITRON_FEDERATION_KEY.ORDINAL, GRAPHITRON_FEDERATION_KEY.FIELDS_SDL)
                .from(GRAPHITRON_FEDERATION_KEY)
                .where(GRAPHITRON_FEDERATION_KEY.TYPE_NAME.eq("Film"))
                .orderBy(GRAPHITRON_FEDERATION_KEY.ORDINAL)
                .fetch();
            assertThat(keys.map(r -> r.value1() + ":" + r.value2())).containsExactly("0:title", "1:id");

            var provenance = store.dsl()
                .select(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS.ORDINAL)
                .from(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS)
                .fetch(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS.ORDINAL);
            assertThat(provenance).as("only the synthesized key carries provenance").containsExactly(1);
        }
    }

    @Test
    @DisplayName("no federation link, no synthesis")
    void synthesisNeedsAFederationLink(@TempDir Path tmp) {
        String sdl = FEDERATED.replace(
            "extend schema @link(url: \"https://specs.apollo.dev/federation/v2.10\", import: [\"@key\"])",
            "");
        try (var store = CapturedStore.of(tmp, sdl)) {
            assertThat(store.dsl().fetchCount(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS)).isZero();
            assertThat(store.dsl().fetchCount(GRAPHITRON_FEDERATION_KEY)).isZero();
        }
    }

    /**
     * The ordinal a synthesized application numbers after is type-wide, so it has to survive the
     * site boundary. A repeatable directive split across a base and an extension is the case that
     * catches a per-site counter: both applications would claim ordinal 0 and the second would
     * quarantine as a duplicate.
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
    @DisplayName("a carrier's field takes the minted Connection, and the authored type survives")
    void theCarrierFieldIsRewritten(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, CONNECTIONS)) {
            var effective = store.dsl()
                .select(GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.TYPE_SDL)
                .from(GRAPHQL_FIELD)
                .where(GRAPHQL_FIELD.TYPE_NAME.eq("Query"))
                .fetch()
                .intoMap(r -> r.value1(), r -> r.value2());
            assertThat(effective).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "films", "QueryFilmsConnection",
                "actors", "ActorConnection",
                "plain", "[Film!]!"));

            var authored = store.dsl()
                .select(GRAPHITRON_FIELD_SYNTHESIS.FIELD_NAME,
                    GRAPHITRON_FIELD_SYNTHESIS.AUTHORED_TYPE_SDL,
                    GRAPHITRON_FIELD_SYNTHESIS.MACRO)
                .from(GRAPHITRON_FIELD_SYNTHESIS)
                .fetch();
            assertThat(authored.map(r -> r.value1() + "=" + r.value2() + ":" + r.value3()))
                .containsExactlyInAnyOrder("films=[Film!]!:CONNECTION", "actors=[Actor]:CONNECTION");
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
                .select(GRAPHITRON_TYPE_DECLARATION_SYNTHESIS.TYPE_NAME,
                    GRAPHITRON_TYPE_DECLARATION_SYNTHESIS.CARRIER_TYPE_NAME,
                    GRAPHITRON_TYPE_DECLARATION_SYNTHESIS.CARRIER_FIELD_NAME)
                .from(GRAPHITRON_TYPE_DECLARATION_SYNTHESIS)
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
                .select(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL, GRAPHQL_TYPE_DECLARATION.IS_EXTENSION)
                .from(GRAPHQL_TYPE_DECLARATION)
                .where(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq("PageInfo"))
                .orderBy(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL)
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
            assertThat(store.dsl().fetchCount(GRAPHITRON_TYPE_DECLARATION_SYNTHESIS,
                GRAPHITRON_TYPE_DECLARATION_SYNTHESIS.TYPE_NAME.eq("QueryFilmsConnection"))).isZero();
        }
    }

    private static java.util.List<String> fieldsOf(CapturedStore store, String typeName) {
        return store.dsl()
            .select(GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.TYPE_SDL)
            .from(GRAPHQL_FIELD)
            .where(GRAPHQL_FIELD.TYPE_NAME.eq(typeName))
            .orderBy(GRAPHQL_FIELD.ORDINAL)
            .fetch()
            .map(r -> r.value1() + "=" + r.value2());
    }
}
