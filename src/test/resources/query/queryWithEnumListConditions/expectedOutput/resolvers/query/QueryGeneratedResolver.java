package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Rating;
import fake.graphql.example.model.RatingFilterInput;
import fake.graphql.example.model.RatingReference;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;


    @Override
    public CompletableFuture<List<Film>> listArgumentNoCondition(List<Rating> ratings,
            String releaseYear, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load(
                (ctx, selectionSet) -> queryDBQueries.listArgumentNoConditionForQuery(ctx, ratings, releaseYear, selectionSet)
        );
    }

    @Override
    public CompletableFuture<List<Film>> listArgumentCondition(List<Rating> ratings,
            String releaseYear, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load(
                (ctx, selectionSet) -> queryDBQueries.listArgumentConditionForQuery(ctx, ratings, releaseYear, selectionSet)
        );
    }

    @Override
    public CompletableFuture<List<Film>> listArgumentWithOverrideCondition(List<Rating> ratings,
            String releaseYear, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load(
                (ctx, selectionSet) -> queryDBQueries.listArgumentWithOverrideConditionForQuery(ctx, ratings, releaseYear, selectionSet)
        );
    }

    @Override
    public CompletableFuture<List<Film>> listArgumentAndFieldCondition(List<Rating> ratings,
            String releaseYear, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load(
                (ctx, selectionSet) -> queryDBQueries.listArgumentAndFieldConditionForQuery(ctx, ratings, releaseYear, selectionSet)
        );
    }

    @Override
    public CompletableFuture<List<Film>> inputArgumentContainingListCondition(
            RatingFilterInput filter, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load(
                (ctx, selectionSet) -> queryDBQueries.inputArgumentContainingListConditionForQuery(ctx, filter, selectionSet)
        );
    }

    @Override
    public CompletableFuture<List<Film>> listArgumentWithEnumDirectiveNoCondition(
            List<RatingReference> ratings, String releaseYear, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).load(
                (ctx, selectionSet) -> queryDBQueries.listArgumentWithEnumDirectiveNoConditionForQuery(ctx, ratings, releaseYear, selectionSet)
        );
    }

    @Override
    public CompletableFuture<List<Film>> listArgumentWithEnumDirectiveCondition(
            List<RatingReference> ratings, String releaseYear, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).load(
                (ctx, selectionSet) -> queryDBQueries.listArgumentWithEnumDirectiveConditionForQuery(ctx, ratings, releaseYear, selectionSet)
        );
    }
}
