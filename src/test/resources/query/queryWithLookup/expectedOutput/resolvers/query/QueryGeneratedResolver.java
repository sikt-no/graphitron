package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.Country;
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
    public CompletableFuture<List<Customer>> customers(List<String> storeIds,
            DataFetchingEnvironment env) throws Exception {
        var keys = List.of(storeIds);
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> queryDBQueries.customersForQuery(ctx, storeIds, selectionSet));
    }

    @Override
    public CompletableFuture<List<Country>> countries(List<String> countryNames,
            DataFetchingEnvironment env) throws Exception {
        var keys = List.of(countryNames);
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> queryDBQueries.countriesForQuery(ctx, countryNames, selectionSet));
    }
}
