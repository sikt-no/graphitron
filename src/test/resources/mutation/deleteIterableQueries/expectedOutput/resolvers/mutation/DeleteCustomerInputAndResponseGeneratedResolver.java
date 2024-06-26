package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomerInputAndResponseDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.DeleteCustomerInputAndResponseMutationResolver;
import fake.graphql.example.model.DeleteInput;
import fake.graphql.example.model.DeleteResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class DeleteCustomerInputAndResponseGeneratedResolver implements DeleteCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<DeleteResponse>> deleteCustomerInputAndResponse(
            List<DeleteInput> input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.deleteInputToJOOQRecord(input, "input");

        var rowsUpdated = DeleteCustomerInputAndResponseDBQueries.deleteCustomerInputAndResponse(ctx, inputRecordList);

        var deleteResponseList = new ArrayList<DeleteResponse>();
        for (var itInputRecordList : inputRecordList) {
            var deleteResponse = new DeleteResponse();
            deleteResponse.setId(itInputRecordList.getId());
            deleteResponseList.add(deleteResponse);
        }

        return CompletableFuture.completedFuture(deleteResponseList);
    }
}
