package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.City;
import fake.graphql.example.model.CityInput;
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

    @Override
    public CompletableFuture<List<City>> paramCondition(String countryId, List<String> cityNames,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.paramConditionForQuery(ctx, countryId, cityNames, selectionSet));
    }

    @Override
    public CompletableFuture<List<City>> paramConditionOverride(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.paramConditionOverrideForQuery(ctx, countryId, cityNames, selectionSet));
    }

    @Override
    public CompletableFuture<List<City>> fieldCondition(String countryId, List<String> cityNames,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.fieldConditionForQuery(ctx, countryId, cityNames, selectionSet));
    }

    @Override
    public CompletableFuture<List<City>> fieldConditionOverride(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.fieldConditionOverrideForQuery(ctx, countryId, cityNames, selectionSet));
    }

    @Override
    public CompletableFuture<List<City>> fieldAndParamCondition(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.fieldAndParamConditionForQuery(ctx, countryId, cityNames, selectionSet));
    }

    @Override
    public CompletableFuture<List<City>> fieldAndParamConditionOverride(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.fieldAndParamConditionOverrideForQuery(ctx, countryId, cityNames, selectionSet));
    }

    @Override
    public CompletableFuture<List<City>> fieldAndParamConditionOverrideBoth(String countryId,
            List<String> cityNames, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.fieldAndParamConditionOverrideBothForQuery(ctx, countryId, cityNames, selectionSet));
    }

    @Override
    public CompletableFuture<List<City>> fieldInputCondition(String countryId, CityInput cityInput,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load((ctx, selectionSet) -> QueryDBQueries.fieldInputConditionForQuery(ctx, countryId, cityInput, selectionSet));
    }
}
