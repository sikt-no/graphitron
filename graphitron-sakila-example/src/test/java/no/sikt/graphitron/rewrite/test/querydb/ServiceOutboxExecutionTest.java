package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for an {@code @outbox} on a {@code @service} mutation, against PostgreSQL:
 * the service runs in one transaction with its messages, so its writes and its messages commit or
 * roll back together on a caller-supplied {@code DSLContext} too, and a message reads its
 * {@code $result} keys off what the service returned: the value itself, a member through the
 * methods its path calls, and a node id from the record a member hands back.
 */
@ExecutionTier
class ServiceOutboxExecutionTest {

    static DSLContext dsl;

    @BeforeAll
    static void start() {
        dsl = SakilaDatabase.dsl();
    }

    @AfterAll
    static void removeWhatWasWritten() {
        dsl.execute("delete from outbox where type in ('MarkerWritten', 'MarkerThrown', 'FilmBlurbRead',"
            + " 'FilmCardMade', 'FilmAssigned',"
            + " 'FilmActorAssigned', 'FilmActorsAssigned')");
        dsl.execute("delete from mutation_scope_marker where marker like 'service-outbox-%'");
    }

    private static String marker() {
        return "service-outbox-" + UUID.randomUUID();
    }

    private static ExecutionResult execute(String mutation) {
        return Graphitron.newGraphQL().build()
            .execute(Graphitron.newExecutionInput(dsl, "{}", "alice").query(mutation).build());
    }

    private static List<Map<String, Object>> payloads(String type, String key, String value) {
        return dsl.fetch("select payload::text as payload from outbox where type = ? and payload ->> ? = ?",
                type, key, value)
            .intoMaps();
    }

    private static long markers(String marker) {
        return dsl.fetchOne("select count(*) from mutation_scope_marker where marker = ?", marker).get(0, Long.class);
    }

    @Test
    void aServiceThatSucceeds_writesItsMessageWithItsReturnedValue() {
        var marker = marker();
        var result = execute("mutation { outboxScopeWriteOne(marker: \"" + marker + "\") }");

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        var written = dsl.fetch("select payload ->> 'value' as value, payload ->> 'at' as at from outbox"
            + " where type = 'MarkerWritten' and payload ->> 'marker' = ?", marker).intoMaps();
        assertThat(written).hasSize(1);
        assertThat(written.getFirst())
            .as("$result is the String the service returned")
            .containsEntry("value", marker)
            .containsEntry("at", "Mutation.outboxScopeWriteOne");
        assertThat(markers(marker)).isEqualTo(1L);
    }

    @Test
    void aServiceThatThrows_takesItsWritesAndItsMessagesWithIt() {
        var marker = marker();
        var result = execute("mutation { outboxScopeWriteTwiceThenThrow(marker: \"" + marker + "\") }");

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(payloads("MarkerThrown", "marker", marker)).isEmpty();
        assertThat(markers(marker))
            .as("the service's writes ran on the transaction it was handed, which rolled back")
            .isEqualTo(0L);
    }

    @Test
    void aResultMember_isReadThroughTheMethodsItsPathCalls() {
        var result = execute("mutation { outboxFilmBlurb(filmId: 1) { blurb { description } } }");

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        String description = dsl.fetchOne("select description from film where film_id = 1").get(0, String.class);
        var written = dsl.fetch("select payload ->> 'text' as text from outbox where type = 'FilmBlurbRead'"
            + " and payload ->> 'filmId' = '1'").intoMaps();
        assertThat(written).isNotEmpty();
        assertThat(written.getLast()).containsEntry("text", description);
    }

    @Test
    void aResultNodeId_isEncodedFromTheRecordAMemberHandsBack() {
        var result = execute("mutation { outboxFilmCard(filmId: 7) { film { id } } }");

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        assertThat(payloads("FilmCardMade", "film", NodeIdEncoder.encodeFilm(7))).isNotEmpty();
    }

    private static Map<String, Object> message(String type, String key, String value, String select) {
        var written = dsl.fetch("select " + select + " from outbox where type = ? and payload ->> ? = ?"
            + " order by 1 desc", type, key, value).intoMaps();
        assertThat(written).as(type + " written").isNotEmpty();
        return written.getFirst();
    }

    @Test
    void anArgumentNodeId_isCarriedEncoded_andANamedKeyColumnDecoded_oneColumnKey() {
        String film = NodeIdEncoder.encodeFilm(7);
        var result = execute("mutation { outboxAssignFilm(in: {film: \"" + film + "\"}) }");

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        var m = message("FilmAssigned", "film", film, "payload -> 'filmId' as film_id");
        assertThat(String.valueOf(m.get("film_id"))).as("the column named, decoded").isEqualTo("7");
    }

    @Test
    void anArgumentNodeId_isCarriedEncoded_andANamedKeyColumnDecoded_twoColumnKey() {
        String actor = NodeIdEncoder.encode("FilmActor", 2, 1);
        var result = execute("mutation { outboxAssignFilmActor(in: {filmActor: \"" + actor + "\"}) }");

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        var m = message("FilmActorAssigned", "actor", actor, "payload -> 'actorId' as actor_id");
        assertThat(String.valueOf(m.get("actor_id"))).as("the column named, out of a two-column key")
            .isEqualTo("2");
    }

    @Test
    void aListOfNodeIds_isAnArrayOfThem_andANamedKeyColumnAnArrayOfTheColumn() {
        String actor = NodeIdEncoder.encode("FilmActor", 2, 1);
        String other = NodeIdEncoder.encode("FilmActor", 3, 4);
        var result = execute("mutation { outboxAssignFilmActors(in: {filmActors: [\"" + actor + "\", \""
            + other + "\"]}) }");

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        var written = dsl.fetch("select payload -> 'actors' as actors, payload -> 'filmIds' as film_ids"
            + " from outbox where type = 'FilmActorsAssigned' and payload -> 'actors' ->> 0 = ?", actor).intoMaps();
        assertThat(written).isNotEmpty();
        assertThat(String.valueOf(written.getLast().get("actors")).replace(" ", ""))
            .as("a list of ids is an array of them, as the client sent them")
            .isEqualTo("[\"" + actor + "\",\"" + other + "\"]");
        assertThat(String.valueOf(written.getLast().get("film_ids")).replace(" ", ""))
            .as("a list of ids is an array of the column, one per id")
            .isEqualTo("[1,4]");
    }
}
