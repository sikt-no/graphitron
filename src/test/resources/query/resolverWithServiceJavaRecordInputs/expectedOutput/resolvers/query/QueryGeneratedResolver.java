package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestFetchCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<Customer>> customersQuery(List<String> ids,
                                                            DataFetchingEnvironment env) throws Exception {
        var testFetchCustomerService = new TestFetchCustomerService(ResolverHelpers.selectContext(env, this.ctx));

        var transform = new RecordTransformer(env, this.ctx);
        var customersQuery = testFetchCustomerService.customersQuery(ids);
        var customerList = transform.customerRecordToGraphType(customersQuery, "");

        return CompletableFuture.completedFuture(customerList);
    }
}
