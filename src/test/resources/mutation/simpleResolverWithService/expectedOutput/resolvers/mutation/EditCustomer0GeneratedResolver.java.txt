package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomer0MutationResolver;
import fake.graphql.example.package.model.EditResponse0;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import org.jooq.DSLContext;

public class EditCustomer0GeneratedResolver implements EditCustomer0MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse0> editCustomer0(String id, DataFetchingEnvironment env)
            throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var testCustomerService = new TestCustomerService(ctx);
        var editCustomer0Result = testCustomerService.editCustomer0(id);

        var editResponse0 = new EditResponse0();
        editResponse0.setId0(editCustomer0Result);

        return CompletableFuture.completedFuture(editResponse0);
    }
}