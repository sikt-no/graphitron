package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmInput;
import fake.graphql.example.model.Rating;
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
    public CompletableFuture<List<Film>> paramCondition(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.paramConditionForQuery(ctx, rating, releaseYear, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> paramConditionOverride(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.paramConditionOverrideForQuery(ctx, rating, releaseYear, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> fieldCondition(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.fieldConditionForQuery(ctx, rating, releaseYear, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> fieldConditionOverride(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.fieldConditionOverrideForQuery(ctx, rating, releaseYear, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> fieldAndParamCondition(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.fieldAndParamConditionForQuery(ctx, rating, releaseYear, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> fieldAndParamConditionOverride(Rating rating,
            String releaseYear, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.fieldAndParamConditionOverrideForQuery(ctx, rating, releaseYear, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> fieldAndParamConditionOverrideBoth(Rating rating,
            String releaseYear, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.fieldAndParamConditionOverrideBothForQuery(ctx, rating, releaseYear, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> fieldInputCondition(FilmInput ratingIn, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> queryDBQueries.fieldInputConditionForQuery(ctx, ratingIn, releaseYear, selectionSet));
    }
}