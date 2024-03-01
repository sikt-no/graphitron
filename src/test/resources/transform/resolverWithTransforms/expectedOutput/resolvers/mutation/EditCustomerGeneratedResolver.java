package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerMutationResolver;
import fake.graphql.example.model.EndreInput;
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

    @Inject
    private EditCustomerDBQueries editCustomerDBQueries;

    @Override
    public CompletableFuture<String> editCustomer(EndreInput in, DataFetchingEnvironment env) throws
            Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var transform = new RecordTransformer(env, ctx);

        var inRecord = transform.endreInputToJOOQRecord(in, "in");

        var rowsUpdated = editCustomerDBQueries.editCustomer(ctx, inRecord);

        return CompletableFuture.completedFuture(inRecord.getId());
    }
}
