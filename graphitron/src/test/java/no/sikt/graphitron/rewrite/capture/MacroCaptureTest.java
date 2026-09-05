package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
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

            // A rewrite is a minted field whose coining coordinate is its own, replacing rather
            // than yielding, and stating the whole row: the ordinal it did not change comes across
            // so that taking the winner needs no coalesce.
            var rewritten = store.dsl()
                .select(GRAPHITRON_MINTED_FIELD.FIELD_NAME, GRAPHITRON_MINTED_FIELD.TYPE_SDL,
                    GRAPHITRON_MINTED_FIELD.PRECEDENCE, GRAPHITRON_MINTED_FIELD.DIRECTIVE_NAME,
                    GRAPHITRON_MINTED_FIELD.ORDINAL)
                .from(GRAPHITRON_MINTED_FIELD)
                .where(GRAPHITRON_MINTED_FIELD.SOURCE_COORDINATE
                    .eq(GRAPHITRON_MINTED_FIELD.TYPE_NAME.concat(".")
                        .concat(GRAPHITRON_MINTED_FIELD.FIELD_NAME)))
                .fetch();
            assertThat(rewritten.map(r -> r.value1() + "=" + r.value2() + ":" + r.value3()
                    + ":" + r.value4() + "@" + r.value5()))
                .containsExactlyInAnyOrder(
                    "films=QueryFilmsConnection:REPLACE:asConnection@0",
                    "actors=ActorConnection:REPLACE:asConnection@1");
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
                .select(GRAPHITRON_MINTED_TYPE.TYPE_NAME,
                    GRAPHITRON_MINTED_TYPE.SOURCE_COORDINATE)
                .from(GRAPHITRON_MINTED_TYPE)
                .fetch();
            assertThat(marked.map(r -> r.value1() + "<-" + r.value2()))
                .containsExactlyInAnyOrder(
                    "QueryFilmsConnection<-Query.films", "QueryFilmsEdge<-Query.films",
                    "PageInfo<-Query.films",
                    "ActorConnection<-Query.actors", "ActorEdge<-Query.actors",
                    "PageInfo<-Query.actors");
        }
    }

    /**
     * PageInfo is shared machinery, and every carrier states the whole of it. One row per carrier
     * here and one row in the emitted anchor, the primary key being the only dedupe there is; that
     * multiplicity is also the refcount an incremental refresh needs, which the cascade from the
     * coining coordinate is what spends.
     *
     * <p>This used to be a merge, the first carrier defining the type and every later one adding an
     * empty extension site, which needed a counter the expansion held across carriers and therefore
     * made it a function of more than one carrier's own declaration.
     */
    @Test
    @DisplayName("shared machinery is stated whole by every carrier and lands once")
    void sharedMachineryIsStatedByEveryCarrier(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, CONNECTIONS)) {
            assertThat(store.dsl()
                .select(GRAPHITRON_MINTED_TYPE.SOURCE_COORDINATE)
                .from(GRAPHITRON_MINTED_TYPE)
                .where(GRAPHITRON_MINTED_TYPE.TYPE_NAME.eq("PageInfo"))
                .fetch(0, String.class))
                .as("one row per carrier, however many carriers there are")
                .containsExactlyInAnyOrder("Query.films", "Query.actors");
            assertThat(store.dsl().fetchCount(GRAPHITRON_TYPE,
                GRAPHITRON_TYPE.TYPE_NAME.eq("PageInfo")))
                .as("and one type in the population the generator emits")
                .isEqualTo(1);
            assertThat(store.dsl()
                .select(GRAPHITRON_MINTED_FIELD.SOURCE_COORDINATE)
                .from(GRAPHITRON_MINTED_FIELD)
                .where(GRAPHITRON_MINTED_FIELD.TYPE_NAME.eq("PageInfo"))
                .and(GRAPHITRON_MINTED_FIELD.FIELD_NAME.eq("hasNextPage"))
                .fetch(0, String.class))
                .as("the fields too, which is what makes the sharing case not a case")
                .containsExactlyInAnyOrder("Query.films", "Query.actors");
        }
    }

    /**
     * An author who declares the name a macro would mint keeps their declaration, and the mint is
     * recorded anyway. The row saying which application stood down where is what a diagnostic about
     * a shadowed connection would read, and it used to be silence.
     *
     * <p>The machinery fields are the half a precedence column cannot decide. They collide with
     * nothing, so the field-grain rule alone would land {@code edges} and {@code pageInfo} on the
     * author's type and fuse two types nobody asked to merge; they are excluded because their own
     * coining coordinate also minted the type that lost.
     */
    @Test
    @DisplayName("an authored declaration wins the collision, and takes the machinery with it")
    void anAuthoredNameWinsTheCollision(@TempDir Path tmp) {
        String sdl = """
            type Query { films: [Film!]! @asConnection }
            type Film { title: String }
            type QueryFilmsConnection { mine: String }
            """;
        try (var store = CapturedStore.of(tmp, sdl)) {
            assertThat(fieldsOf(store, "QueryFilmsConnection"))
                .as("the author's type, whole, with none of the machinery grafted onto it")
                .containsExactly("mine=String");
            assertThat(store.dsl()
                .select(GRAPHITRON_MINTED_TYPE.PRECEDENCE, GRAPHITRON_MINTED_TYPE.SOURCE_COORDINATE)
                .from(GRAPHITRON_MINTED_TYPE)
                .where(GRAPHITRON_MINTED_TYPE.TYPE_NAME.eq("QueryFilmsConnection"))
                .fetch(r -> r.value1() + "<-" + r.value2()))
                .as("the mint is recorded standing down rather than not happening")
                .containsExactly("YIELD<-Query.films");
            assertThat(store.dsl()
                .select(GRAPHITRON_MINTED_FIELD.FIELD_NAME)
                .from(GRAPHITRON_MINTED_FIELD)
                .where(GRAPHITRON_MINTED_FIELD.TYPE_NAME.eq("QueryFilmsConnection"))
                .fetch(0, String.class))
                .as("its fields are recorded too, and none of them reaches the anchor")
                .containsExactlyInAnyOrder("edges", "nodes", "pageInfo", "totalCount");
            // The carrier is still rewritten to the name, which is the author's type now.
            assertThat(fieldsOf(store, "Query")).containsExactly("films=QueryFilmsConnection");
        }
    }

    /**
     * The two pagination arguments, which the store did not record at all until this relation
     * existed: the expansion has two halves in two modules, one writing facts and one building
     * schema objects, and only the second knew they were there.
     *
     * <p>The condition is not a per-name collision, which is why it stays in capture rather than
     * becoming a precedence column: an author who writes any pagination argument keeps their
     * pagination whole, so a carrier carrying only {@code last} gets neither {@code first} nor
     * {@code after} though neither name is taken.
     */
    @Test
    @DisplayName("the pagination arguments are minted, and an authored one stands the pair down")
    void paginationArgumentsAreMinted(@TempDir Path tmp) {
        String sdl = """
            type Query {
              films: [Film!]! @asConnection
              actors(last: Int): [Actor!]! @asConnection
              rated(genre: String): [Film!]! @asConnection(defaultFirstValue: 25)
            }
            type Film { title: String }
            type Actor { name: String }
            """;
        try (var store = CapturedStore.of(tmp, sdl)) {
            assertThat(store.dsl()
                .select(GRAPHITRON_MINTED_ARGUMENT.FIELD_NAME,
                    GRAPHITRON_MINTED_ARGUMENT.ARGUMENT_NAME,
                    GRAPHITRON_MINTED_ARGUMENT.TYPE_SDL,
                    GRAPHITRON_MINTED_ARGUMENT.DEFAULT_VALUE_SDL,
                    GRAPHITRON_MINTED_ARGUMENT.ORDINAL)
                .from(GRAPHITRON_MINTED_ARGUMENT)
                .fetch(r -> r.value1() + "." + r.value2() + ":" + r.value3()
                    + "=" + r.value4() + "@" + r.value5()))
                .as("both arguments on the carriers with no pagination of their own, the page size"
                    + " the author's where they declared one, and neither on the carrier that"
                    + " wrote last")
                .containsExactlyInAnyOrder(
                    "films.first:Int=100@0", "films.after:String=null@1",
                    "rated.first:Int=25@1", "rated.after:String=null@2");
            assertThat(store.dsl()
                .select(GRAPHITRON_ARGUMENT.ARGUMENT_NAME)
                .from(GRAPHITRON_ARGUMENT)
                .where(GRAPHITRON_ARGUMENT.TYPE_NAME.eq("Query"))
                .and(GRAPHITRON_ARGUMENT.FIELD_NAME.eq("rated"))
                .orderBy(GRAPHITRON_ARGUMENT.ORDINAL)
                .fetch(0, String.class))
                .as("and the emitted argument list is the author's with the mint appended")
                .containsExactly("genre", "first", "after");
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
