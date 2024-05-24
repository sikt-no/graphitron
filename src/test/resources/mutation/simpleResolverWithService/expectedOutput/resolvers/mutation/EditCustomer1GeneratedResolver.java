package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomer1MutationResolver;
import fake.graphql.example.model.EditResponse1;
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
        var transform = new RecordTransformer(env, this.ctx);

        var editCustomer1 = testCustomerService.editCustomerIDList(id);

        var editResponse1 = new EditResponse1();

        if (editCustomer1 != null && transform.getSelect().contains("id1")) {
            editResponse1.setId1(editCustomer1);
        }

        return CompletableFuture.completedFuture(editResponse1);
    }
}
