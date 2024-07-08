package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomer2ParamsDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomer2ParamsMutationResolver;
import fake.graphql.example.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class EditCustomer2ParamsGeneratedResolver implements EditCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<String> editCustomer2Params(EditInput input, String lastName,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = EditCustomer2ParamsDBQueries.editCustomer2Params(transform.getCtx(), inputRecord, lastName);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}
