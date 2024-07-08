package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerInputDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerInputMutationResolver;
import fake.graphql.example.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class EditCustomerInputGeneratedResolver implements EditCustomerInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<String>> editCustomerInput(List<EditInput> input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = EditCustomerInputDBQueries.editCustomerInput(transform.getCtx(), inputRecordList);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}
