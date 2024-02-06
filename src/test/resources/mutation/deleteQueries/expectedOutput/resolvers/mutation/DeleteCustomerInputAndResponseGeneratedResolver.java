package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomerInputAndResponseDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.DeleteCustomerInputAndResponseMutationResolver;
import fake.graphql.example.model.DeleteInput;
import fake.graphql.example.model.DeleteResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class DeleteCustomerInputAndResponseGeneratedResolver implements DeleteCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private DeleteCustomerInputAndResponseDBQueries deleteCustomerInputAndResponseDBQueries;

    @Override
    public CompletableFuture<DeleteResponse> deleteCustomerInputAndResponse(DeleteInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new InputTransformer(env, ctx);

        var inputRecord = transform.deleteInputToJOOQRecord(input, "input");

        var rowsUpdated = deleteCustomerInputAndResponseDBQueries.deleteCustomerInputAndResponse(ctx, inputRecord);

        var deleteResponse = new DeleteResponse();
        deleteResponse.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(deleteResponse);
    }
}