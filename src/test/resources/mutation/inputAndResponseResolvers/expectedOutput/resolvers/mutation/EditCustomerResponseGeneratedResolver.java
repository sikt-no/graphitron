package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomerResponseMutationResolver;
import fake.graphql.example.package.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import org.jooq.DSLContext;

public class EditCustomerResponseGeneratedResolver implements EditCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse> editCustomerResponse(String id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var testCustomerService = new TestCustomerService(ctx);
        var editCustomerResponseResult = testCustomerService.editCustomerResponse(id);


        var editResponse = new EditResponse();
        editResponse.setId(editCustomerResponseResult.getId());
        editResponse.setFirstName(editCustomerResponseResult.getFIRST_NAME());
        editResponse.setPostalCode(editCustomerResponseResult.getPOSTAL_CODE());

        return CompletableFuture.completedFuture(editResponse);
    }
}