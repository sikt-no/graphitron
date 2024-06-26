package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomer2ParamsDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.DeleteCustomer2ParamsMutationResolver;
import fake.graphql.example.model.DeleteInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;

import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class DeleteCustomer2ParamsGeneratedResolver implements DeleteCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<String>> deleteCustomer2Params(List<DeleteInput> input,
            String lastName, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.deleteInputToJOOQRecord(input, "input");

        var rowsUpdated = DeleteCustomer2ParamsDBQueries.deleteCustomer2Params(ctx, inputRecordList, lastName);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}
