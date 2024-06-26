package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerMutationResolver;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerGeneratedResolver implements EditCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse> editCustomer(String id, EditInput in,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inRecord = transform.editInputToJOOQRecord(in, "in", "in");

        transform.validate();

        var rowsUpdated = EditCustomerDBQueries.editCustomer(ctx, id, inRecord);

        var editResponse = new EditResponse();
        editResponse.setId(id);

        return CompletableFuture.completedFuture(editResponse);
    }
}
