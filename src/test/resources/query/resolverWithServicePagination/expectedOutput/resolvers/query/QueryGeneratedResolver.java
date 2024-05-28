package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestFetchCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.relay.ExtendedConnection;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<ExtendedConnection<Customer>> customersQueryConnection(
            List<String> ids, Integer first, String after, DataFetchingEnvironment env) throws
            Exception {
        int pageSize = ResolverHelpers.getPageSize(first, 1000, 100);
        var testFetchCustomerService = new TestFetchCustomerService(ResolverHelpers.selectContext(env, this.ctx));

        return new ServiceDataFetcher<>(new RecordTransformer(env, this.ctx)).loadPaginated(
                pageSize, 1000,
                () -> testFetchCustomerService.customersQuery0(ids, pageSize, after),
                (ids) -> testFetchCustomerService.countCustomersQuery0(ids),
                (it) -> it.getId(),
                (transform, response) -> transform.customerRecordToGraphType(response, "")
        );
    }

    @Override
    public CompletableFuture<Customer> customersQueryDirect(String id, DataFetchingEnvironment env)
            throws Exception {
        var testFetchCustomerService = new TestFetchCustomerService(ResolverHelpers.selectContext(env, this.ctx));

        return new ServiceDataFetcher<>(new RecordTransformer(env, this.ctx)).load(
                () -> testFetchCustomerService.customersQuery1(id),
                (transform, response) -> transform.customerRecordToGraphType(response, "")
        );
    }
}
