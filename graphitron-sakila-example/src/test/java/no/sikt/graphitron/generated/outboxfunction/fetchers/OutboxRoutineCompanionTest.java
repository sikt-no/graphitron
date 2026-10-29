package no.sikt.graphitron.generated.outboxfunction.fetchers;

import graphql.GraphQLContext;
import graphql.execution.MergedField;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.outboxfunction.schema.ErrorRouter;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
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
 * The companion method a {@code <function>} target generates, called directly: the generated call of
 * {@code outbox_emit} against a real PostgreSQL, with no GraphQL engine and no write fetcher in front
 * of it. Each case runs in a transaction it rolls back. In the generated fetchers' package because
 * the companion is package-private.
 */
@UnitTier
class OutboxRoutineCompanionTest {

    private static final String ROLLBACK = "rollback";

    static DSLContext dsl;

    @BeforeAll
    static void start() {
        dsl = SakilaDatabase.dsl();
    }

    private static DataFetchingEnvironment env(String operationName) {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext().build())
            .operationDefinition(OperationDefinition.newOperationDefinition()
                .operation(OperationDefinition.Operation.MUTATION).name(operationName).build())
            .mergedField(MergedField.newMergedField(Field.newField("createFilmRoutine").build()).build())
            .build();
    }

    private static void inRolledBackTransaction(Consumer<DSLContext> body) {
        assertThatThrownBy(() -> dsl.transaction(cfg -> {
            body.accept(DSL.using(cfg));
            throw new DataAccessException(ROLLBACK);
        })).hasMessageContaining(ROLLBACK);
    }

    private static Record insertedFilm(DSLContext tx, String title) {
        return tx.insertInto(Tables.FILM, Tables.FILM.TITLE, Tables.FILM.LANGUAGE_ID)
            .values(title, 1)
            .returningResult(Tables.FILM.FILM_ID)
            .fetchOne();
    }

    /** The messages this transaction wrote, which is what {@code created_at = now()} reads inside one. */
    private static List<Map<String, Object>> messages(DSLContext tx) {
        return tx.fetch("select aggregatetype, aggregateid, payload ->> 'operation' as operation,"
                + " payload ->> 'title' as title from outbox where type = 'FilmCreated'"
                + " and created_at = now()")
            .intoMaps();
    }

    @Test
    void callsTheRoutineWithTheEventTheTableAndTheEvaluatedPayload() {
        inRolledBackTransaction(tx -> {
            var written = insertedFilm(tx, "routine-companion");

            MutationFetchers.outboxCreateFilmRoutine(tx, env("Op"), written);

            var rows = messages(tx);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst())
                .containsEntry("aggregatetype", "film")
                .containsEntry("aggregateid", "via-routine")
                .containsEntry("operation", "Op")
                .containsEntry("title", "routine-companion");
        });
    }

    @Test
    void aWriteThatMatchedNothingCallsNothing() {
        inRolledBackTransaction(tx -> {
            MutationFetchers.outboxCreateFilmRoutine(tx, env("Op"), null);

            assertThat(messages(tx)).isEmpty();
        });
    }

    @Test
    void aFailingCallSurfacesAsTheOutboxException() {
        inRolledBackTransaction(tx -> {
            var written = insertedFilm(tx, "routine-fails");
            // An aborted transaction refuses every statement after it, the routine's call included.
            assertThatThrownBy(() -> tx.execute("select 1 / 0")).isInstanceOf(DataAccessException.class);

            assertThatThrownBy(() -> MutationFetchers.outboxCreateFilmRoutine(tx, env("Op"), written))
                .isInstanceOf(ErrorRouter.OutboxEmitException.class)
                .hasCauseInstanceOf(DataAccessException.class);
        });
    }
}
