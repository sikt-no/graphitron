package no.sikt.graphitron.rewrite.generated;

import graphql.Scalars;
import graphql.execution.DataFetcherResult;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.childoutcome.Graphitron;
import no.sikt.graphitron.generated.childoutcome.fetchers.FilmFetchers;
import no.sikt.graphitron.generated.childoutcome.schema.Outcome;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.services.FilmVerdict;
import no.sikt.graphitron.rewrite.test.services.FilmVerdictService;
import no.sikt.graphitron.rewrite.test.services.VerdictRefusedException;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A child {@code @service} catching into its outcome, driven through the generated fetcher of
 * {@code src/test/schemas/child-outcome.graphqls} against the shared database: the parent is a film
 * row read from it, the fetcher batches through its DataLoader, and the service reads the film's
 * title back over the same database. The service picks its branch by film id
 * ({@link FilmVerdictService}).
 */
@ExecutionTier
class ChildOutcomeExecutionTest {

    @Test
    void whatTheServiceReturnsIsTheOutcomesSuccess() throws Exception {
        var film = film(1);

        var result = verdictOf(film);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getData()).isInstanceOf(Outcome.Success.class);
        assertThat(((Outcome.Success<?>) result.getData()).value())
            .isEqualTo(new FilmVerdict("Seen: " + film.getTitle()));
    }

    @Test
    void aThrowAHandlerClaimsIsTheOutcomesErrors() throws Exception {
        var result = verdictOf(film(FilmVerdictService.REFUSED));

        assertThat(result.getErrors()).as("a claimed throw is data, not a field error").isEmpty();
        assertThat(result.getData()).isInstanceOf(Outcome.ErrorList.class);
        var errors = ((Outcome.ErrorList<?>) result.getData()).errors();
        assertThat(errors).singleElement().isInstanceOf(VerdictRefusedException.class);
        assertThat(((Throwable) errors.get(0)).getMessage())
            .contains(String.valueOf(FilmVerdictService.REFUSED));
    }

    @Test
    void aThrowNoHandlerClaimsRedacts() throws Exception {
        var result = verdictOf(film(FilmVerdictService.BROKEN));

        assertThat(result.getData()).isNull();
        assertThat(result.getErrors()).singleElement().satisfies(error ->
            assertThat(error.getMessage())
                .startsWith("An error occurred. Reference: ")
                .doesNotContain("unreachable"));
    }

    @Test
    void aThrowBeforeTheLoaderTakesTheSamePath() throws Exception {
        // A source that is no row fails the key extraction ahead of the loader, in the fetcher's
        // own synchronous guard: still an outcome-shaped result rather than a thrown exception.
        var result = verdictOf("not a row");

        assertThat(result.getData()).isNull();
        assertThat(result.getErrors()).singleElement().satisfies(error ->
            assertThat(error.getMessage()).startsWith("An error occurred. Reference: "));
    }

    private static FilmRecord film(int filmId) {
        var film = TestDatabase.dsl().selectFrom(FILM).where(FILM.FILM_ID.eq(filmId)).fetchOne();
        assertThat(film).as("the seeded catalog holds film " + filmId).isNotNull();
        return film;
    }

    /** The generated fetcher's result for one parent, its DataLoader dispatched the way a request would. */
    private static DataFetcherResult<?> verdictOf(Object source) throws Exception {
        var input = Graphitron.newExecutionInput(TestDatabase.dsl()).query("{ films { verdict { verdict } } }").build();
        var registry = input.getDataLoaderRegistry();
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .source(source)
            .graphQLContext(input.getGraphQLContext())
            .dataLoaderRegistry(registry)
            .mergedField(MergedField.newMergedField(Field.newField("verdict").build()).build())
            .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment("films").segment(0).segment("verdict"))
                .build())
            .build();

        var pending = FilmFetchers.verdict(env);
        registry.dispatchAll();
        return pending.get(30, TimeUnit.SECONDS);
    }
}
