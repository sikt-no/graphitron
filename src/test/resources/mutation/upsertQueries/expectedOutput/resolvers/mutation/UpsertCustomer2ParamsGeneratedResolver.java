package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomer2ParamsDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.UpsertCustomer2ParamsMutationResolver;
import fake.graphql.example.model.UpsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class UpsertCustomer2ParamsGeneratedResolver implements UpsertCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private UpsertCustomer2ParamsDBQueries upsertCustomer2ParamsDBQueries;

    @Override
    public CompletableFuture<String> upsertCustomer2Params(UpsertInput input, String lastName,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, ctx);

        var inputRecord = transform.upsertInputToJOOQRecord(input, "input");

        var rowsUpdated = upsertCustomer2ParamsDBQueries.upsertCustomer2Params(ctx, inputRecord, lastName);

        return CompletableFuture.completedFuture(inputRecord.getId());
    }
}