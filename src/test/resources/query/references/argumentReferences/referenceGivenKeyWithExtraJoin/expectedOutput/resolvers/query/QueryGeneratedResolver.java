package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Address;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private QueryDBQueries queryDBQueries;

    @Override
    public CompletableFuture<List<Address>> address0(String cityID, String storeID,
                                                     DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var selectionSet = ResolverHelpers.getSelectionSet(env);
        return CompletableFuture.completedFuture(queryDBQueries.address0ForQuery(ctx, cityID, storeID, selectionSet));
    }

    @Override
    public CompletableFuture<List<Address>> address1(String cityID, String storeID,
                                                     DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var selectionSet = ResolverHelpers.getSelectionSet(env);
        return CompletableFuture.completedFuture(queryDBQueries.address1ForQuery(ctx, cityID, storeID, selectionSet));
    }
}