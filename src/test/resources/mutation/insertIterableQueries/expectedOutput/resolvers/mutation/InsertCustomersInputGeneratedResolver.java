package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomersInputDBQueries;
import fake.code.generated.transform.InputTransformer;
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
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class InsertCustomersInputGeneratedResolver implements InsertCustomersInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private InsertCustomersInputDBQueries insertCustomersInputDBQueries;

    @Override
    public CompletableFuture<List<String>> insertCustomersInput(List<InsertInput> input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new InputTransformer(env, ctx);

        var inputRecordList = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = insertCustomersInputDBQueries.insertCustomersInput(ctx, inputRecordList);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}