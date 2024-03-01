package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomer2ParamsDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomer2ParamsMutationResolver;
import fake.graphql.example.model.EditInput;
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

public class EditCustomer2ParamsGeneratedResolver implements EditCustomer2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditCustomer2ParamsDBQueries editCustomer2ParamsDBQueries;

    @Override
    public CompletableFuture<List<String>> editCustomer2Params(List<EditInput> input,
            String lastName, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, ctx);

        var inputRecordList = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = editCustomer2ParamsDBQueries.editCustomer2Params(ctx, inputRecordList, lastName);

        return CompletableFuture.completedFuture(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));
    }
}
