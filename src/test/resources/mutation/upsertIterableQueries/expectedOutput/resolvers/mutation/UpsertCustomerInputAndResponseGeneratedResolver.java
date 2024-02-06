package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomerInputAndResponseDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.UpsertCustomerInputAndResponseMutationResolver;
import fake.graphql.example.model.UpsertInput;
import fake.graphql.example.model.UpsertResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class UpsertCustomerInputAndResponseGeneratedResolver implements UpsertCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private UpsertCustomerInputAndResponseDBQueries upsertCustomerInputAndResponseDBQueries;

    @Override
    public CompletableFuture<List<UpsertResponse>> upsertCustomerInputAndResponse(
            List<UpsertInput> input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new InputTransformer(env, ctx);

        var inputRecordList = transform.upsertInputToJOOQRecord(input, "input");

        var rowsUpdated = upsertCustomerInputAndResponseDBQueries.upsertCustomerInputAndResponse(ctx, inputRecordList);

        var upsertResponseList = new ArrayList<UpsertResponse>();
        for (var itInputRecordList : inputRecordList) {
            var upsertResponse = new UpsertResponse();
            upsertResponse.setId(itInputRecordList.getId());
            upsertResponseList.add(upsertResponse);
        }

        return CompletableFuture.completedFuture(upsertResponseList);
    }
}