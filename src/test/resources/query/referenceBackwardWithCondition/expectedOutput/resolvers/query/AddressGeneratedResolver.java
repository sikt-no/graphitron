package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.AddressDBQueries;
import fake.graphql.example.package.api.AddressResolver;
import fake.graphql.example.package.model.Address;
import fake.graphql.example.package.model.Store;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class AddressGeneratedResolver implements AddressResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private AddressDBQueries addressDBQueries;

    @Override
    public CompletableFuture<Store> store(Address address, String id, DataFetchingEnvironment env)
            throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, Store> loader = DataLoaders.getDataLoader(env, "storeForAddress", (ids, selectionSet) -> addressDBQueries.storeForAddress(ctx, ids, id, selectionSet));
        return DataLoaders.load(loader, address.getId(), env);
    }
}
