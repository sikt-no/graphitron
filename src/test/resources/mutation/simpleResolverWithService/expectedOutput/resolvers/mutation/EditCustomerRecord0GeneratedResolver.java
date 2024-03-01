package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerRecord0MutationResolver;
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

public class EditCustomerRecord0GeneratedResolver implements EditCustomerRecord0MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse0> editCustomerRecord0(String id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var transform = new RecordTransformer(env, ctx);

        var editCustomerRecord0 = testCustomerService.editCustomerRecord0(id);

        var editResponse0 = new EditResponse0();

        if (editCustomerRecord0 != null && transform.getArguments().contains("id0")) {
            editResponse0.setId0(editCustomerRecord0.getId());
        }

        return CompletableFuture.completedFuture(editResponse0);
    }
}