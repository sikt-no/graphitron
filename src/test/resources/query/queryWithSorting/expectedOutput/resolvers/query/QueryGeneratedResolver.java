package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Inventory;
import fake.graphql.example.model.InventoryOrder;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
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
    public CompletableFuture<List<Inventory>> inventories(InventoryOrder order,
                                                          DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var selectionSet = ResolverHelpers.getSelectionSet(env);
        var dbResult = queryDBQueries.inventoriesForQuery(ctx, order, selectionSet);
        return CompletableFuture.completedFuture(dbResult);
    }
}