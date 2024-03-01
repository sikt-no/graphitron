package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerListResponseMutationResolver;
import fake.graphql.example.model.EditResponse;
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

public class EditCustomerListResponseGeneratedResolver implements EditCustomerListResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<EditResponse>> editCustomerListResponse(List<String> ids,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        var transform = new RecordTransformer(env, ctx);

        var editCustomerListResponse = testCustomerService.editCustomerListResponse(ids);

        var editResponseList = transform.editResponseToGraphType(editCustomerListResponse, "");

        return CompletableFuture.completedFuture(editResponseList);
    }
}