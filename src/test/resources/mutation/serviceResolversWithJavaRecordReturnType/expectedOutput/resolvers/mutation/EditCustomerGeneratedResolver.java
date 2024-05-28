package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerMutationResolver;
import fake.graphql.example.model.EditResponseLevel1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerGeneratedResolver implements EditCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponseLevel1> editCustomer(String id,
                                                              DataFetchingEnvironment env) throws Exception {
        var testCustomerService = new TestCustomerService(ResolverHelpers.selectContext(env, this.ctx));

        var transform = new RecordTransformer(env, this.ctx);

        var editCustomer = testCustomerService.editCustomerID(id);

        var editResponseLevel1 = transform.editResponseLevel1ToGraphType(editCustomer, "");

        return CompletableFuture.completedFuture(editResponseLevel1);
    }
}
