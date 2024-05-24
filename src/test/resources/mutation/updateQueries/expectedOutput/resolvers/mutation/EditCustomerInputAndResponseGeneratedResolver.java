package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerInputAndResponseDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerInputAndResponseMutationResolver;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerInputAndResponseGeneratedResolver implements EditCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomerInputAndResponseDBQueries editCustomerInputAndResponseDBQueries;

    @Override
    public CompletableFuture<EditResponse> editCustomerInputAndResponse(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = editCustomerInputAndResponseDBQueries.editCustomerInputAndResponse(ctx, inputRecord);

        var editResponse = new EditResponse();
        editResponse.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(editResponse);
    }
}