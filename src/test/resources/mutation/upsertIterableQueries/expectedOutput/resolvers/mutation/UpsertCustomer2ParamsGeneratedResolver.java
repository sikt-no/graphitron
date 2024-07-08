package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomer2ParamsDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.UpsertCustomer2ParamsMutationResolver;
import fake.graphql.example.model.UpsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class UpsertCustomer2ParamsGeneratedResolver implements UpsertCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<String>> upsertCustomer2Params(List<UpsertInput> input,
            String lastName, DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.upsertInputToJOOQRecord(input, "input");

        var rowsUpdated = UpsertCustomer2ParamsDBQueries.upsertCustomer2Params(transform.getCtx(), inputRecordList, lastName);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}
