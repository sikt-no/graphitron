package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomer2ParamsDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.InsertCustomer2ParamsMutationResolver;
import fake.graphql.example.model.InsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class InsertCustomer2ParamsGeneratedResolver implements InsertCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<String> insertCustomer2Params(InsertInput input, String lastName,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = InsertCustomer2ParamsDBQueries.insertCustomer2Params(transform.getCtx(), inputRecord, lastName);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}
