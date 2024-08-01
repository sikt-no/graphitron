package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomerInputAndResponseDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.UpsertCustomerInputAndResponseMutationResolver;
import fake.graphql.example.model.UpsertInput;
import fake.graphql.example.model.UpsertResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class UpsertCustomerInputAndResponseGeneratedResolver implements UpsertCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<UpsertResponse> upsertCustomerInputAndResponse(UpsertInput input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.upsertInputToJOOQRecord(input, "input");

        var rowsUpdated = UpsertCustomerInputAndResponseDBQueries.upsertCustomerInputAndResponse(transform.getCtx(), inputRecord);

        var upsertResponse = new UpsertResponse();
        upsertResponse.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(upsertResponse);
    }
}
