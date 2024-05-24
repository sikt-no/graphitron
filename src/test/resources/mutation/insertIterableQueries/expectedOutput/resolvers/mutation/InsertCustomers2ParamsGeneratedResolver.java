package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomers2ParamsDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.InsertCustomers2ParamsMutationResolver;
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

public class InsertCustomers2ParamsGeneratedResolver implements InsertCustomers2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private InsertCustomers2ParamsDBQueries insertCustomers2ParamsDBQueries;

    @Override
    public CompletableFuture<List<String>> insertCustomers2Params(List<InsertInput> input,
            String lastName, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = insertCustomers2ParamsDBQueries.insertCustomers2Params(ctx, inputRecordList, lastName);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}