package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomer2ParamsDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.DeleteCustomer2ParamsMutationResolver;
import fake.graphql.example.model.DeleteInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class DeleteCustomer2ParamsGeneratedResolver implements DeleteCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<String> deleteCustomer2Params(DeleteInput input, String lastName,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.deleteInputToJOOQRecord(input, "input");

        var rowsUpdated = DeleteCustomer2ParamsDBQueries.deleteCustomer2Params(transform.getCtx(), inputRecord, lastName);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}
