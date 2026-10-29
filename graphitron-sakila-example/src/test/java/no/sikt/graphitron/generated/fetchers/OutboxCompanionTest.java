package no.sikt.graphitron.generated.fetchers;

import graphql.GraphQLContext;
import graphql.execution.MergedField;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.schema.ErrorRouter;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The companion method {@code @outbox} generates, called directly: the generated code against a
 * real PostgreSQL, with no GraphQL engine and no write fetcher in front of it. Each case runs in a
 * transaction it rolls back, so the table is left as found.
 *
 * <p>Lives in the generated fetchers' package because the companion is package-private; it is the
 * fetcher's own call and nothing else is meant to reach it.
 */
@UnitTier
class OutboxCompanionTest {

    private static final String ROLLBACK = "rollback";

    static DSLContext dsl;

    @BeforeAll
    static void start() {
        dsl = DSL.using(SakilaDatabase.url(), SakilaDatabase.user(), SakilaDatabase.password());
    }

    private static DataFetchingEnvironment env(String operationName, String alias, Map<String, Object> arguments) {
        var field = Field.newField("createFilmOutbox");
        if (alias != null) {
            field.alias(alias);
        }
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext().build())
            .operationDefinition(OperationDefinition.newOperationDefinition()
                .operation(OperationDefinition.Operation.MUTATION).name(operationName).build())
            .mergedField(MergedField.newMergedField(field.build()).build())
            .arguments(() -> graphql.collect.ImmutableMapWithNullValues.copyOf(arguments))
            .build();
    }

    /** Runs {@code body} on a transaction that rolls back afterwards. */
    private static void inRolledBackTransaction(Consumer<DSLContext> body) {
        assertThatThrownBy(() -> dsl.transaction(cfg -> {
            body.accept(DSL.using(cfg));
            throw new DataAccessException(ROLLBACK);
        })).hasMessageContaining(ROLLBACK);
    }

    private static Record insertedFilm(DSLContext tx, String title) {
        return tx.insertInto(no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM,
                no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM.TITLE,
                no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM.LANGUAGE_ID)
            .values(title, 1)
            .returningResult(no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM.FILM_ID)
            .fetchOne();
    }

    private static List<Map<String, Object>> messages(DSLContext tx, String type) {
        return tx.fetch("select aggregatetype, payload ->> 'operation' as operation,"
                + " payload ->> 'alias' as alias, payload ->> 'at' as at, payload ->> 'filmId' as film_id,"
                + " payload ->> 'title' as title, payload ->> 'requested' as requested"
                // created_at defaults to now(), the transaction's own start inside one, so this reads only
                // what this case wrote and none of the committed rows other tests left behind.
                + " from outbox where type = ? and created_at = now()", type)
            .intoMaps();
    }

    @Test
    void writesOneRowPerMessageFromTheWrittenRow() {
        inRolledBackTransaction(tx -> {
            var written = insertedFilm(tx, "companion-written");
            int id = written.get(no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM.FILM_ID);

            MutationFetchers.outboxCreateFilmOutbox(tx,
                env("Op", "mine", Map.of("in", Map.of("title", "companion-written"))), written);

            var created = messages(tx, "FilmCreated");
            assertThat(created).hasSize(1);
            assertThat(created.getFirst())
                .containsEntry("aggregatetype", "film")
                .containsEntry("operation", "Op")
                .containsEntry("film_id", NodeIdEncoder.encodeFilm(id))
                .containsEntry("title", "companion-written")
                .containsEntry("requested", "companion-written");
            var audit = messages(tx, "FilmAudit");
            assertThat(audit).hasSize(1);
            assertThat(audit.getFirst())
                .containsEntry("alias", "mine")
                .containsEntry("at", "Mutation.createFilmOutbox");
        });
    }

    @Test
    void anAbsentArgumentAndAnAbsentAliasLeaveTheirKeysOut() {
        inRolledBackTransaction(tx -> {
            var written = insertedFilm(tx, "companion-bare");

            MutationFetchers.outboxCreateFilmOutbox(tx, env(null, null, Map.of("in", Map.of())), written);

            assertThat(messages(tx, "FilmCreated").getFirst())
                .containsEntry("operation", null)
                .containsEntry("requested", null);
            assertThat(messages(tx, "FilmAudit").getFirst()).containsEntry("alias", null);
        });
    }

    @Test
    void aWriteThatMatchedNothingWritesNoMessage() {
        inRolledBackTransaction(tx -> {
            MutationFetchers.outboxCreateFilmOutbox(tx,
                env("Op", null, Map.of("in", Map.of("title", "none"))), null);

            assertThat(messages(tx, "FilmCreated")).isEmpty();
            assertThat(messages(tx, "FilmAudit")).isEmpty();
        });
    }

    @Test
    void aFailingStatementSurfacesAsTheOutboxException() {
        inRolledBackTransaction(tx -> {
            var written = insertedFilm(tx, "companion-fails");
            // An aborted transaction refuses every statement after it, the message's included.
            assertThatThrownBy(() -> tx.execute("select 1 / 0")).isInstanceOf(DataAccessException.class);

            assertThatThrownBy(() -> MutationFetchers.outboxCreateFilmOutbox(tx,
                env("Op", null, Map.of("in", Map.of("title", "x"))), written))
                .isInstanceOf(ErrorRouter.OutboxEmitException.class)
                .hasCauseInstanceOf(DataAccessException.class);
        });
    }
}
