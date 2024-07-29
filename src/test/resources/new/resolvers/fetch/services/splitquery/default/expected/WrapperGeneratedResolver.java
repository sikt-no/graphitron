package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.services.ResolverFetchService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;
import org.jooq.DSLContext;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Customer> query(Wrapper wrapper, DataFetchingEnvironment env) throws
            Exception {
        var transform = new RecordTransformer(env, this.ctx);
        var resolverFetchService = new ResolverFetchService(transform.getCtx());
        return new ServiceDataFetcher<>(transform).load(
                "queryForWrapper", wrapper.getId(),
                (ids) -> resolverFetchService.query(ids),
                (transform, response) -> transform.customerRecordToGraphType(response, ""));
    }
}
