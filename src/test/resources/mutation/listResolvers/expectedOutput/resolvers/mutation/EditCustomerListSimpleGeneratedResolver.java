package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomerListSimpleMutationResolver;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import org.jooq.DSLContext;

public class EditCustomerListSimpleGeneratedResolver implements EditCustomerListSimpleMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<String>> editCustomerListSimple(List<String> ids,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var testCustomerService = new TestCustomerService(ctx);
        var editCustomerListSimpleResult = testCustomerService.editCustomerListSimple(ids);

        return CompletableFuture.completedFuture(editCustomerListSimpleResult);
    }
}