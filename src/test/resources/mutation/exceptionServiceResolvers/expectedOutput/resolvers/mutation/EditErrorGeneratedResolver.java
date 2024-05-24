package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditErrorMutationResolver;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.SomeErrorB;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.exceptions.TestExceptionCause;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditErrorGeneratedResolver implements EditErrorMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponse> editError(EditInput input,
                                                             DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");
        var edit2Record = new CustomerRecord();
        edit2Record.attach(ctx.configuration());

        if (input != null) {
            var edit2 = input.getEdit2();
            edit2Record = transform.editInput2ToJOOQRecord(edit2, "input/edit2");
        }

        EditCustomerResponse1 editError = null;
        var someErrorBList = new ArrayList<SomeErrorB>();
        try {
            editError = testCustomerService.editError(inputRecord, edit2Record);
        } catch (TestExceptionCause e) {
            var error = new SomeErrorB();
            error.setMessage(e.getMessage());
            var cause = e.getCauseField();
            var causeName = Map.of("ID", "EditInput.id", "LAST_NAME", "EditInput.lastName", "EMAIL", "EditInput.EditInput2.email", "FIRST_NAME", "EditInput.name").getOrDefault(cause != null ? cause : "", "undefined");
            error.setPath(List.of(("Mutation.editError." + causeName).split("\\.")));
            someErrorBList.add(error);
        }

        if (editError == null) {
            var editCustomerResponse = new EditCustomerResponse();
            editCustomerResponse.setErrors(someErrorBList);
            return CompletableFuture.completedFuture(editCustomerResponse);
        }

        var editCustomerResponse = transform.editCustomerResponseToGraphType(editError, "");

        if (editError != null && transform.getSelect().contains("errors")) {
            editCustomerResponse.setErrors(someErrorBList);
        }

        return CompletableFuture.completedFuture(editCustomerResponse);
    }
}
