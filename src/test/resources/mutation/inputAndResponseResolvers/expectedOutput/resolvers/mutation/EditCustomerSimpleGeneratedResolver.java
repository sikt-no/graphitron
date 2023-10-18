package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomerSimpleMutationResolver;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import org.jooq.DSLContext;

public class EditCustomerSimpleGeneratedResolver implements EditCustomerSimpleMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<String> editCustomerSimple(String id, DataFetchingEnvironment env)
            throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var testCustomerService = new TestCustomerService(ctx);
        var editCustomerSimpleResult = testCustomerService.editCustomerSimple(id);

        return CompletableFuture.completedFuture(editCustomerSimpleResult);
    }
}