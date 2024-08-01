package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomersInputAndResponseDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.InsertCustomersInputAndResponseMutationResolver;
import fake.graphql.example.model.EditResponse;
import fake.graphql.example.model.InsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class InsertCustomersInputAndResponseGeneratedResolver implements InsertCustomersInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<EditResponse>> insertCustomersInputAndResponse(
            List<InsertInput> input, DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = InsertCustomersInputAndResponseDBQueries.insertCustomersInputAndResponse(transform.getCtx(), inputRecordList);

        var editResponseList = new ArrayList<EditResponse>();
        for (var itInputRecordList : inputRecordList) {
            var editResponse = new EditResponse();
            editResponse.setId(itInputRecordList.getId());
            editResponseList.add(editResponse);
        }

        return CompletableFuture.completedFuture(editResponseList);
    }
}
