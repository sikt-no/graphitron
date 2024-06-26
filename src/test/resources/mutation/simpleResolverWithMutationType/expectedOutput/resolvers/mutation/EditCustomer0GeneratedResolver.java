package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomer0DBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomer0MutationResolver;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse0;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomer0GeneratedResolver implements EditCustomer0MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse0> editCustomer0(String id, EditInput in,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inRecord = transform.editInputToJOOQRecord(in, "in");

        var rowsUpdated = EditCustomer0DBQueries.editCustomer0(ctx, id, inRecord);

        var editResponse0 = new EditResponse0();
        editResponse0.setId0(id);

        return CompletableFuture.completedFuture(editResponse0);
    }
}
