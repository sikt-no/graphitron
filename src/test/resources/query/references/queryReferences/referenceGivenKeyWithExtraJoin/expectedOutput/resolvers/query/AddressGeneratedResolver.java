package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.AddressDBQueries;
import fake.graphql.example.api.AddressResolver;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.Store;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
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
    public CompletableFuture<List<Store>> stores0(Address address, DataFetchingEnvironment env)
            throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<Store>> loader = DataLoaders.getDataLoader(env, "stores0ForAddress", (ids, selectionSet) -> addressDBQueries.stores0ForAddress(ctx, ids, selectionSet));
        return DataLoaders.load(loader, address.getId(), env);
    }

    @Override
    public CompletableFuture<List<Store>> stores1(Address address, DataFetchingEnvironment env)
            throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<Store>> loader = DataLoaders.getDataLoader(env, "stores1ForAddress", (ids, selectionSet) -> addressDBQueries.stores1ForAddress(ctx, ids, selectionSet));
        return DataLoaders.loadNonNullable(loader, address.getId(), env);
    }
}
