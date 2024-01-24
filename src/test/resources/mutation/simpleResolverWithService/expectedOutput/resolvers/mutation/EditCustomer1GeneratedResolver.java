package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomer1MutationResolver;
import fake.graphql.example.package.model.EditResponse1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomer1GeneratedResolver implements EditCustomer1MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse1> editCustomer1(List<String> id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var editCustomer1Result = testCustomerService.editCustomer1(id);

        var editResponse1 = new EditResponse1();
        editResponse1.setId1(editCustomer1Result);

        return CompletableFuture.completedFuture(editResponse1);
    }
}