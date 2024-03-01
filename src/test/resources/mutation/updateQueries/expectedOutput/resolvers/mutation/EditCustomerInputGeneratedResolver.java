package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerInputDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerInputMutationResolver;
import fake.graphql.example.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerInputGeneratedResolver implements EditCustomerInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomerInputDBQueries editCustomerInputDBQueries;

    @Override
    public CompletableFuture<String> editCustomerInput(EditInput input, DataFetchingEnvironment env)
            throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = editCustomerInputDBQueries.editCustomerInput(ctx, inputRecord);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}