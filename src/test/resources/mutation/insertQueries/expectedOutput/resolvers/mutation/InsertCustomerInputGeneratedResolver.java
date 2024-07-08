package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomerInputDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.InsertCustomerInputMutationResolver;
import fake.graphql.example.model.InsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class InsertCustomerInputGeneratedResolver implements InsertCustomerInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<String> insertCustomerInput(InsertInput input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = InsertCustomerInputDBQueries.insertCustomerInput(transform.getCtx(), inputRecord);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}
