package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomerListResponseMutationResolver;
import fake.graphql.example.package.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerListResponseGeneratedResolver implements EditCustomerListResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<EditResponse>> editCustomerListResponse(List<String> ids,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var editCustomerListResponseResult = testCustomerService.editCustomerListResponse(ids);


        var editResponseList = new ArrayList<EditResponse>();
        for (var itEditCustomerListResponseResult : editCustomerListResponseResult) {
            var editResponse = new EditResponse();
            editResponse.setId(itEditCustomerListResponseResult.getId());
            editResponse.setFirstName(itEditCustomerListResponseResult.getFirstName());
            editResponse.setEmail(itEditCustomerListResponseResult.getSecretEmail());
            editResponseList.add(editResponse);
        }

        return CompletableFuture.completedFuture(editResponseList);
    }
}