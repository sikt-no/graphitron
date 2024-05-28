package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.WrapperResolver;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.Wrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestFetchCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class WrapperGeneratedResolver implements WrapperResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<Address>> historicalAddresses(Wrapper wrapper,
                                                                DataFetchingEnvironment env) throws Exception {
        var testFetchCustomerService = new TestFetchCustomerService(ResolverHelpers.selectContext(env, this.ctx));

        return new ServiceDataFetcher<>(new RecordTransformer(env, this.ctx)).loadNonNullable(
                "historicalAddressesForWrapper", wrapper.getId(),
                (ids) -> testFetchCustomerService.historicalAddresses(ids),
                (transform, response) -> transform.addressRecordToGraphType(response, "")
        );
    }
}

