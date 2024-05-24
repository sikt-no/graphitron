package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomer0MutationResolver;
import fake.graphql.example.model.EditResponse0;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomer0GeneratedResolver implements EditCustomer0MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse0> editCustomer0(String id, DataFetchingEnvironment env)
            throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var transform = new RecordTransformer(env, this.ctx);

        var editCustomer0 = testCustomerService.editCustomerID(id);

        var editResponse0 = new EditResponse0();

        if (editCustomer0 != null && transform.getSelect().contains("id0")) {
            editResponse0.setId0(editCustomer0);
        }

        return CompletableFuture.completedFuture(editResponse0);
    }
}
