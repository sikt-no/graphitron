package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import no.sikt.graphitron.codereferences.services.ResolverFetchService;
import no.sikt.graphql.helpers.resolvers.ServiceDataFetcher;

public class QueryGeneratedResolver implements QueryResolver {
    @Override
    public CompletableFuture<Customer> query(DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env);
        var resolverFetchService = new ResolverFetchService(transform.getCtx());
        return new ServiceDataFetcher<>(transform).load(
                () -> resolverFetchService.query(),
                (transform, response) -> transform.customerRecordToGraphType(response, ""));
    }
}
