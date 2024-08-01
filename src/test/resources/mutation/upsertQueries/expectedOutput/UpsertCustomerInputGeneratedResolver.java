package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomerInputDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.UpsertCustomerInputMutationResolver;
import fake.graphql.example.model.UpsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class UpsertCustomerInputGeneratedResolver implements UpsertCustomerInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<String> upsertCustomerInput(UpsertInput input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.upsertInputToJOOQRecord(input, "input");

        var rowsUpdated = UpsertCustomerInputDBQueries.upsertCustomerInput(transform.getCtx(), inputRecord);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}
