package fake.code.generated.resolvers.query;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.OuterWrapper;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestFetchCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.helpers.resolvers.ServiceDataFetcher;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<OuterWrapper> customersQuery(DataFetchingEnvironment env) throws
            Exception {
        var testFetchCustomerService = new TestFetchCustomerService(ResolverHelpers.selectContext(env, this.ctx));

        return new ServiceDataFetcher<>(new RecordTransformer(env, this.ctx)).load(
                () -> testFetchCustomerService.customersQuery2(),
                (transform, response) -> transform.outerWrapperRecordToGraphType(response, "")
        );
    }
}
