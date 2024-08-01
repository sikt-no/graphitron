package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomerInputAndResponseDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.DeleteCustomerInputAndResponseMutationResolver;
import fake.graphql.example.model.DeleteInput;
import fake.graphql.example.model.DeleteResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class DeleteCustomerInputAndResponseGeneratedResolver implements DeleteCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<DeleteResponse> deleteCustomerInputAndResponse(DeleteInput input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.deleteInputToJOOQRecord(input, "input");

        var rowsUpdated = DeleteCustomerInputAndResponseDBQueries.deleteCustomerInputAndResponse(transform.getCtx(), inputRecord);

        var deleteResponse = new DeleteResponse();
        deleteResponse.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(deleteResponse);
    }
}
