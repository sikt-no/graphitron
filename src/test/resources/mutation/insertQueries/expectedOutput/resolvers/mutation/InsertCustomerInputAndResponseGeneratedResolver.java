package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomerInputAndResponseDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.InsertCustomerInputAndResponseMutationResolver;
import fake.graphql.example.model.InsertInput;
import fake.graphql.example.model.InsertResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class InsertCustomerInputAndResponseGeneratedResolver implements InsertCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<InsertResponse> insertCustomerInputAndResponse(InsertInput input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = InsertCustomerInputAndResponseDBQueries.insertCustomerInputAndResponse(transform.getCtx(), inputRecord);

        var insertResponse = new InsertResponse();
        insertResponse.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(insertResponse);
    }
}
