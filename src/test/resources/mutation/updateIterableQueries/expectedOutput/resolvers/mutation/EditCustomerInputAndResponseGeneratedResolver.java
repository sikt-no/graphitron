package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerInputAndResponseDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerInputAndResponseMutationResolver;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerInputAndResponseGeneratedResolver implements EditCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<EditResponse>> editCustomerInputAndResponse(List<EditInput> input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = EditCustomerInputAndResponseDBQueries.editCustomerInputAndResponse(ctx, inputRecordList);

        var editResponseList = new ArrayList<EditResponse>();
        for (var itInputRecordList : inputRecordList) {
            var editResponse = new EditResponse();
            editResponse.setId(itInputRecordList.getId());
            editResponseList.add(editResponse);
        }

        return CompletableFuture.completedFuture(editResponseList);
    }
}
