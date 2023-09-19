package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.package.api.QueryResolver;
import fake.graphql.example.package.model.Film;
import fake.graphql.example.package.model.FilmInput;
import fake.graphql.example.package.model.Rating;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<List<Film>> paramCondition(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.paramConditionForQuery(ctx, rating, releaseYear, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<Film>> paramConditionOverride(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.paramConditionOverrideForQuery(ctx, rating, releaseYear, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<Film>> fieldCondition(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldConditionForQuery(ctx, rating, releaseYear, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<Film>> fieldConditionOverride(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldConditionOverrideForQuery(ctx, rating, releaseYear, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<Film>> fieldAndParamCondition(Rating rating, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldAndParamConditionForQuery(ctx, rating, releaseYear, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<Film>> fieldAndParamConditionOverride(Rating rating,
            String releaseYear, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldAndParamConditionOverrideForQuery(ctx, rating, releaseYear, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<Film>> fieldAndParamConditionOverrideBoth(Rating rating,
            String releaseYear, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldAndParamConditionOverrideBothForQuery(ctx, rating, releaseYear, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<Film>> fieldInputCondition(FilmInput ratingIn, String releaseYear,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldInputConditionForQuery(ctx, ratingIn, releaseYear, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }
}