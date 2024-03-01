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
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class InsertCustomer2ParamsGeneratedResolver implements InsertCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private InsertCustomer2ParamsDBQueries insertCustomer2ParamsDBQueries;

    @Override
    public CompletableFuture<String> insertCustomer2Params(InsertInput input, String lastName,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, ctx);

        var inputRecord = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = insertCustomer2ParamsDBQueries.insertCustomer2Params(ctx, inputRecord, lastName);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}