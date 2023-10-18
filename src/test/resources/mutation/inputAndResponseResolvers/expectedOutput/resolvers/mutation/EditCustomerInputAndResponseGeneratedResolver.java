package fake.code.generated.resolvers.mutation;

import fake.graphql.example.package.api.EditCustomerInputAndResponseMutationResolver;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerInputAndResponseGeneratedResolver implements EditCustomerInputAndResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditResponse> editCustomerInputAndResponse(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var testCustomerService = new TestCustomerService(ctx);
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inputRecord = new CustomerRecord();
        inputRecord.attach(ctx.configuration());

        if (input != null) {
            if (flatArguments.contains("input/postalCode")) {
                inputRecord.setPostalCode(input.getPostalCode());
            }
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
            }
            if (flatArguments.contains("input/firstName")) {
                inputRecord.setFirstName(input.getFirstName());
            }
        }

        var editCustomerInputAndResponseResult = testCustomerService.editCustomerInputAndResponse(inputRecord);


        var editResponse = new EditResponse();
        editResponse.setId(editCustomerInputAndResponseResult.getId());
        editResponse.setFirstName(editCustomerInputAndResponseResult.getFIRST_NAME());
        editResponse.setPostalCode(editCustomerInputAndResponseResult.getPOSTAL_CODE());

        return CompletableFuture.completedFuture(editResponse);
    }
}