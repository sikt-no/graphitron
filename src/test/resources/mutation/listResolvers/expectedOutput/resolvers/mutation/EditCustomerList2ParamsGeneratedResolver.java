package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.EditCustomerList2ParamsMutationResolver;
import fake.graphql.example.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerList2ParamsGeneratedResolver implements EditCustomerList2ParamsMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<String>> editCustomerList2Params(List<EditInput> input,
            List<String> lastNames, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        var transform = new InputTransformer(env, ctx);

        var inputRecordList = transform.editInputToJOOQRecord(input, "input");

        var editCustomerList2ParamsResult = testCustomerService.editCustomerList2Params(inputRecordList, lastNames);

        return CompletableFuture.completedFuture(editCustomerList2ParamsResult);
    }
}