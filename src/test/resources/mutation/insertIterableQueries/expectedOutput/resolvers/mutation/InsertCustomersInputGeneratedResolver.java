package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomersInputDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.InsertCustomersInputMutationResolver;
import fake.graphql.example.model.InsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class InsertCustomersInputGeneratedResolver implements InsertCustomersInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<String>> insertCustomersInput(List<InsertInput> input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = InsertCustomersInputDBQueries.insertCustomersInput(transform.getCtx(), inputRecordList);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}
