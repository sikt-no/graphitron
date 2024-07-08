package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.services.JavaRecordInputFetchService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Customer> customers(String id, CustomerInput in,
                                                 DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inRecord = transform.customerInputToJavaRecord(in, "in");

        var javaRecordInputFetchService = new JavaRecordInputFetchService(transform.getCtx());
        return new ServiceDataFetcher<>(transform).load(
                () -> javaRecordInputFetchService.customers(id, inRecord),
                (transform, response) -> transform.customerRecordToGraphType(response, ""));
    }

    @Override
    public CompletableFuture<List<Customer>> customersListed(List<CustomerInput> in,
                                                             DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inRecordList = transform.customerInputToJavaRecord(in, "in");

        var javaRecordInputFetchService = new JavaRecordInputFetchService(transform.getCtx());
        return new ServiceDataFetcher<>(transform).load(
                () -> javaRecordInputFetchService.customersListed(inRecordList),
                (transform, response) -> transform.customerRecordToGraphType(response, ""));
    }
}
