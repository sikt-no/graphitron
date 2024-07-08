package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomerInputDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.DeleteCustomerInputMutationResolver;
import fake.graphql.example.model.DeleteInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class DeleteCustomerInputGeneratedResolver implements DeleteCustomerInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<String>> deleteCustomerInput(List<DeleteInput> input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.deleteInputToJOOQRecord(input, "input");

        var rowsUpdated = DeleteCustomerInputDBQueries.deleteCustomerInput(transform.getCtx(), inputRecordList);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}
