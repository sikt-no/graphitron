package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerListInputAndResponseMutationResolver;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import org.jooq.DSLContext;

public class EditCustomerListInputAndResponseGeneratedResolver implements EditCustomerListInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<EditResponse>> editCustomerListInputAndResponse(
            List<EditInput> input, DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.editInputToJOOQRecord(input, "input");

        var testCustomerService = new TestCustomerService(transform.getCtx());

        var editCustomerListInputAndResponse = testCustomerService.editCustomerListInputAndResponse(inputRecordList);

        var editResponseList = transform.editResponseToGraphType(editCustomerListInputAndResponse, "");

        return CompletableFuture.completedFuture(editResponseList);
    }
}