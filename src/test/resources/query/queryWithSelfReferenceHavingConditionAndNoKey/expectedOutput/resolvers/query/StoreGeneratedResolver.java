package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.StoreDBQueries;
import fake.graphql.example.api.StoreResolver;
import fake.graphql.example.model.Store;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class StoreGeneratedResolver implements StoreResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Store> flagshipStore(Store store, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).load(
                "flagshipStoreForStore", store.getId(),
                (ctx, ids, selectionSet) -> StoreDBQueries.flagshipStoreForStore(ctx, ids, selectionSet));
    }

    @Override
    public CompletableFuture<List<Store>> popupStores(Store store, DataFetchingEnvironment env)
            throws Exception {
        return new DataFetcher(env, this.ctx).load(
                "popupStoresForStore", store.getId(),
                (ctx, ids, selectionSet) -> StoreDBQueries.popupStoresForStore(ctx, ids, selectionSet));
    }
}