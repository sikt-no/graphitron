package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerInputTable;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.services.ResolverFetchService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Customer> query(CustomerInputTable in, DataFetchingEnvironment env)
            throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inRecord = transform.customerInputTableToJOOQRecord(in, "in");

        var resolverFetchService = new ResolverFetchService(transform.getCtx());
        return new ServiceDataFetcher<>(transform).load(
                () -> resolverFetchService.query(inRecord),
                (transform, response) -> transform.customerRecordToGraphType(response, ""));
    }
}
