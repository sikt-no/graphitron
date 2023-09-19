package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.package.api.QueryResolver;
import fake.graphql.example.package.model.City;
import fake.graphql.example.package.model.CityInput;
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
    public CompletableFuture<List<City>> paramCondition(String countryId, List<String> cityNames,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.paramConditionForQuery(ctx, countryId, cityNames, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<City>> paramConditionOverride(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.paramConditionOverrideForQuery(ctx, countryId, cityNames, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<City>> fieldCondition(String countryId, List<String> cityNames,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldConditionForQuery(ctx, countryId, cityNames, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<City>> fieldConditionOverride(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldConditionOverrideForQuery(ctx, countryId, cityNames, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<City>> fieldAndParamCondition(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldAndParamConditionForQuery(ctx, countryId, cityNames, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<City>> fieldAndParamConditionOverride(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldAndParamConditionOverrideForQuery(ctx, countryId, cityNames, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<City>> fieldAndParamConditionOverrideBoth(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldAndParamConditionOverrideBothForQuery(ctx, countryId, cityNames, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }

    @Override
    public CompletableFuture<List<City>> fieldInputCondition(String countryId, CityInput cityInput,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
        var dbResult = queryDBQueries.fieldInputConditionForQuery(ctx, countryId, cityInput, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }
}