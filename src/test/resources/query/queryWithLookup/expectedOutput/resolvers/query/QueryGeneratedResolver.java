package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.package.api.QueryResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.Country;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<List<Customer>> customers(List<String> storeIds,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        return DataLoaders.loadDataAsLookup(env, storeIds, (ids, selectionSet) -> queryDBQueries.customersForQuery(ctx, storeIds, selectionSet));
    }

    @Override
    public CompletableFuture<List<Country>> countries(List<String> countryNames,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        return DataLoaders.loadDataAsLookup(env, countryNames, (ids, selectionSet) -> queryDBQueries.countriesForQuery(ctx, countryNames, selectionSet));
    }
}
