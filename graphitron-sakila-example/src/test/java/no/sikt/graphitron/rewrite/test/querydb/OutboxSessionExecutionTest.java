package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import no.sikt.graphitron.sakila.example.app.SakilaSessions;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier coverage for {@code $session.<member>} in an {@code @outbox} message, against
 * PostgreSQL: the members of the handle the owned acquisition mounted reach the message, read on the
 * write's own transaction, and a caller-supplied {@code DSLContext}, which carries no handle, fails
 * the field and rolls the write back.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class OutboxSessionExecutionTest {

    static DSLContext dsl;

    @BeforeAll
    static void start() {
        dsl = SakilaDatabase.dsl();
    }

    @AfterAll
    static void removeTheFilmsWritten() {
        dsl.execute("delete from film where title like 'outbox-session-%'");
    }

    private static String marker() {
        return "outbox-session-" + UUID.randomUUID();
    }

    /** An owned engine over a plain connection, which may write, unlike the session tests' probe role. */
    private static GraphQL ownedEngine() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(SakilaDatabase.url());
        dataSource.setUser(SakilaDatabase.user());
        dataSource.setPassword(SakilaDatabase.password());
        return Graphitron.runtime(dataSource, SQLDialect.POSTGRES)
            .newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    private List<Map<String, Object>> messages(String title) {
        return dsl.fetch("select payload ->> 'by' as by, payload ->> 'session' as session,"
                + " payload ->> 'title' as title from outbox where type = 'FilmCreatedBy'"
                + " and payload ->> 'title' = ?", title)
            .intoMaps();
    }

    @Test
    void ownedPath_writesTheMountedPrincipalAndSessionNumberIntoTheMessage() {
        var title = marker();
        var claims = "{\"sub\":\"alice\"}";
        var result = ownedEngine().execute(Graphitron
            .newOwnedExecutionInput(SakilaSessions.of(claims), claims, "alice")
            .query("mutation { createFilmSessionOutbox(in: {title: \"" + title + "\", languageId: 1}) { id } }")
            .build());

        assertThat(result.getErrors()).as("errors: " + result.getErrors()).isEmpty();
        var written = messages(title);
        assertThat(written).hasSize(1);
        assertThat(written.getFirst()).containsEntry("by", "alice").containsEntry("title", title);
        assertThat(Integer.parseInt((String) written.getFirst().get("session")))
            .as("the session number the mount drew from its sequence")
            .isPositive();
    }

    @Test
    void escapeHatch_failsTheFieldAndRollsTheWriteBack() {
        var title = marker();
        var result = Graphitron.newExecutionInput(dsl, "{}", "alice").query(
            "mutation { createFilmSessionOutbox(in: {title: \"" + title + "\", languageId: 1}) { id } }");
        var executed = graphitronEngine().execute(result.build());

        assertThat(executed.getErrors())
            .as("a caller-supplied DSLContext carries no mounted handle")
            .isNotEmpty();
        assertThat(messages(title)).isEmpty();
        assertThat(dsl.fetchValue("select count(*) from film where title = ?", title))
            .as("the failed message takes its write with it")
            .isEqualTo(0L);
    }

    private static GraphQL graphitronEngine() {
        return Graphitron.newGraphQL().build();
    }
}
