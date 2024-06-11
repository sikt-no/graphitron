package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerInput2MutationResolver;
import fake.graphql.example.model.EditCustomerResponse2;
import fake.graphql.example.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerInput2GeneratedResolver implements EditCustomerInput2MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponse2> editCustomerInput2(EditInput input,
                                                                       DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var transform = new RecordTransformer(env, ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input", "input");

        transform.validate();
        var editCustomerInput2 = testCustomerService.editCustomerInputAndResponse(inputRecord);

        var editCustomerResponse2 = transform.editCustomerResponse2ToGraphType(editCustomerInput2, "");

        return CompletableFuture.completedFuture(editCustomerResponse2);
    }
}