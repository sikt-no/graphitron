package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomerInputDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.UpsertCustomerInputMutationResolver;
import fake.graphql.example.model.UpsertInput;
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

public class UpsertCustomerInputGeneratedResolver implements UpsertCustomerInputMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private UpsertCustomerInputDBQueries upsertCustomerInputDBQueries;

    @Override
    public CompletableFuture<List<String>> upsertCustomerInput(List<UpsertInput> input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new InputTransformer(env, ctx);

        var inputRecordList = transform.upsertInputToJOOQRecord(input, "input");

        var rowsUpdated = upsertCustomerInputDBQueries.upsertCustomerInput(ctx, inputRecordList);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}