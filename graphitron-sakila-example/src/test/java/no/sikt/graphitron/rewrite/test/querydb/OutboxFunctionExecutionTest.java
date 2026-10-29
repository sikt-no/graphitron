package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.outboxfunction.Graphitron;
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
 * Execution-tier coverage for {@code @outbox} through the {@code <function>} target, against
 * PostgreSQL: the messages reach the outbox through the stored routine {@code outbox_emit} rather
 * than an insert of their own. The routine stamps the aggregate id it is not given with
 * {@code via-routine}, which is how a row it wrote is told from one written directly.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class OutboxFunctionExecutionTest {

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
        dsl = SakilaDatabase.dsl();
        graphql = Graphitron.newGraphQL().build();
    }

    private graphql.ExecutionResult run(String query) {
        return graphql.execute(Graphitron.newExecutionInput(dsl).query(query).build());
    }

    private Map<String, Object> execute(String query) {
        var result = run(query);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    private static String marker() {
        return "outbox-fn-" + UUID.randomUUID();
    }

    private List<Map<String, Object>> messages(String type, String key, String marker) {
        return dsl.fetch("select aggregatetype, aggregateid, payload ->> 'operation' as operation,"
                + " payload ->> 'filmId' as film_id, payload ->> 'title' as title, payload ->> 'id' as id"
                + " from outbox where type = ? and payload ->> ? = ?", type, key, marker)
            .intoMaps();
    }

    @Test
    void insert_writesItsMessageThroughTheRoutine() {
        var title = marker();
        var data = execute("mutation Make { createFilmRoutine(in: {title: \"" + title + "\", languageId: 1}) { id } }");
        var film = (Map<String, Object>) data.get("createFilmRoutine");

        var written = messages("FilmCreated", "title", title);
        assertThat(written).hasSize(1);
        assertThat(written.getFirst())
            .containsEntry("aggregateid", "via-routine")
            .containsEntry("aggregatetype", "film")
            .containsEntry("operation", "Make")
            .containsEntry("film_id", film.get("id"))
            .containsEntry("title", title);
    }

    @Test
    void insert_aFailedWriteLeavesNoMessage() {
        var title = marker();
        var result = run("mutation { createFilmRoutine(in: {title: \"" + title + "\", languageId: 999999}) { id } }");

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(messages("FilmCreated", "title", title)).isEmpty();
    }

    @Test
    void delete_writesTheDeletedNodeIdThroughTheRoutine() {
        var title = marker();
        execute("mutation { createFilmRoutine(in: {title: \"" + title + "\", languageId: 1}) { id } }");
        int id = dsl.fetchValue("select film_id from film where title = ?", title) instanceof Number n
            ? n.intValue() : -1;

        var data = execute("mutation { deleteFilmRoutine(in: {filmId: " + id + "}) }");
        var deleted = (String) data.get("deleteFilmRoutine");

        var written = messages("FilmDeleted", "id", deleted);
        assertThat(written).hasSize(1);
        assertThat(written.getFirst()).containsEntry("aggregateid", "via-routine");
    }
}
