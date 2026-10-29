package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for {@code @outbox} against PostgreSQL: the messages a write leaves in the
 * outbox table, in the write's own transaction. Every assertion finds its rows by a marker the test
 * wrote (a unique title or the written id), so no test reads another's messages.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class OutboxExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;

    /**
     * The films these cases wrote are removed when they are done. A committed film changes what other
     * tests read: the ones that order films by a column several share leave ties to the plan, and
     * more rows move where a tie falls.
     */
    @AfterAll
    static void removeTheFilmsWritten() {
        dsl.execute("delete from film where title like 'outbox-%'");
    }

    @BeforeAll
    static void start() {
        dsl = DSL.using(SakilaDatabase.url(), SakilaDatabase.user(), SakilaDatabase.password());
        graphql = Graphitron.newGraphQL().build();
    }

    private graphql.ExecutionResult run(String query) {
        return graphql.execute(Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build());
    }

    private Map<String, Object> execute(String query) {
        var result = run(query);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private static String marker() {
        return "outbox-" + UUID.randomUUID();
    }

    private int filmId(String title) {
        return dsl.fetchValue("select film_id from film where title = ?", title) instanceof Number n
            ? n.intValue() : -1;
    }

    /** The messages of one type whose payload carries {@code marker} under {@code key}. */
    private List<Map<String, Object>> messages(String type, String key, String marker) {
        return dsl.fetch("select type, aggregatetype, aggregateid, payload::text as payload,"
                + " payload ->> 'operation' as operation, payload ->> 'at' as at,"
                + " payload ->> 'alias' as alias, payload ->> 'filmId' as film_id,"
                + " payload ->> 'title' as title, payload ->> 'id' as id"
                + " from outbox where type = ? and payload ->> ? = ?", type, key, marker)
            .intoMaps();
    }

    private List<Map<String, Object>> arrayMessages(String type, String containedId) {
        return dsl.fetch("select type, payload ->> 'operation' as operation,"
                + " jsonb_array_length(payload -> 'ids') as n"
                + " from outbox where type = ? and payload -> 'ids' @> to_jsonb(?::text)", type, containedId)
            .intoMaps();
    }

    @Test
    void insert_writesOneMessagePerDirectiveFromTheWrittenRow() {
        var title = marker();
        var data = execute("mutation Create { createFilmOutbox(in: {title: \"" + title + "\", languageId: 1}) { id title } }");
        var film = (Map<String, Object>) data.get("createFilmOutbox");

        var created = messages("FilmCreated", "title", title);
        assertThat(created).hasSize(1);
        var message = created.getFirst();
        assertThat(message.get("film_id")).as("the node id of the row the write produced").isEqualTo(film.get("id"));
        assertThat(message.get("operation")).as("the common payload entry").isEqualTo("Create");
        assertThat(message.get("aggregatetype")).as("the written table").isEqualTo("film");

        var audit = messages("FilmAudit", "requested", title);
        assertThat(audit).as("a second directive is a second message").hasSize(1);
        assertThat(audit.getFirst().get("at")).isEqualTo("Mutation.createFilmOutbox");
        assertThat(audit.getFirst().get("alias")).as("no alias, no entry").isNull();
    }

    @Test
    void insert_scopeAliasIsTheAliasTheClientWrote() {
        var title = marker();
        execute("mutation { made: createFilmOutbox(in: {title: \"" + title + "\", languageId: 1}) { id } }");

        var audit = messages("FilmAudit", "requested", title);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("alias")).isEqualTo("made");
        assertThat(audit.getFirst().get("operation")).as("an anonymous operation names nothing").isNull();
    }

    @Test
    void insert_aFailedWriteLeavesNoMessage() {
        var title = marker();
        var result = run("mutation { createFilmOutbox(in: {title: \"" + title + "\", languageId: 999999}) { id } }");

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(messages("FilmCreated", "title", title)).isEmpty();
        assertThat(messages("FilmAudit", "requested", title)).isEmpty();
    }

    @Test
    void bulkInsert_writesOneMessageCarryingEveryWrittenId() {
        var a = marker();
        var b = marker();
        var data = execute("mutation { createFilmsOutbox(in: ["
            + "{title: \"" + a + "\", languageId: 1}, {title: \"" + b + "\", languageId: 1}]) { id } }");
        var ids = ((List<Map<String, Object>>) data.get("createFilmsOutbox")).stream()
            .map(r -> (String) r.get("id")).toList();

        var first = arrayMessages("FilmsCreated", ids.get(0));
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().get("n")).isEqualTo(2);
        assertThat(arrayMessages("FilmsCreated", ids.get(1))).as("the same message").hasSize(1);
    }

    @Test
    void delete_writesTheDeletedNodeId() {
        var title = marker();
        execute("mutation { createFilmOutbox(in: {title: \"" + title + "\", languageId: 1}) { id } }");
        int id = filmId(title);

        var data = execute("mutation { deleteFilmOutbox(in: {filmId: " + id + "}) }");
        var deleted = (String) data.get("deleteFilmOutbox");

        var messages = messages("FilmDeleted", "id", deleted);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().get("operation")).isNull();
    }

    @Test
    void delete_aKeyThatMatchedNothingWritesNoMessage() {
        var data = execute("mutation { deleteFilmOutbox(in: {filmId: 2147483000}) }");
        var echoed = (String) data.get("deleteFilmOutbox");

        assertThat(messages("FilmDeleted", "id", echoed)).isEmpty();
    }

    @Test
    void bulkDelete_writesOneMessageCarryingEveryDeletedId() {
        var a = marker();
        var b = marker();
        execute("mutation { createFilmsOutbox(in: ["
            + "{title: \"" + a + "\", languageId: 1}, {title: \"" + b + "\", languageId: 1}]) { id } }");
        int idA = filmId(a);
        int idB = filmId(b);

        var data = execute("mutation { deleteFilmsOutbox(in: [{filmId: " + idA + "}, {filmId: " + idB + "}]) }");
        var deleted = (List<String>) data.get("deleteFilmsOutbox");

        var message = arrayMessages("FilmsDeleted", deleted.get(0));
        assertThat(message).hasSize(1);
        assertThat(message.getFirst().get("n")).isEqualTo(2);
        assertThat(arrayMessages("FilmsDeleted", deleted.get(1))).hasSize(1);
    }
}
