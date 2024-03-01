package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditErrorUnion2MutationResolver;
import fake.graphql.example.model.EditCustomerResponseUnion2;
import fake.graphql.example.model.EditErrorsUnion2;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.SomeErrorA;
import fake.graphql.example.model.SomeErrorB;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.exceptions.TestException;
import no.fellesstudentsystem.graphitron.exceptions.TestExceptionCause;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditErrorUnion2GeneratedResolver implements EditErrorUnion2MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponseUnion2> editErrorUnion2(EditInput input,
                                                                         DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        var transform = new RecordTransformer(env, ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");
        var edit2Record = new CustomerRecord();
        edit2Record.attach(ctx.configuration());

        if (input != null) {
            var edit2 = input.getEdit2();
            edit2Record = transform.editInput2ToJOOQRecord(edit2, "input/edit2");
        }

        EditCustomerResponse1 editErrorUnion2 = null;
        var editErrorsUnion2List = new ArrayList<EditErrorsUnion2>();
        try {
            editErrorUnion2 = testCustomerService.editErrorUnion2(inputRecord, edit2Record);
        } catch (TestException e) {
            var error = new SomeErrorA();
            error.setMessage(e.getMessage());
            error.setPath(List.of("editErrorUnion2"));
            editErrorsUnion2List.add(error);
        } catch (TestExceptionCause e) {
            var error = new SomeErrorB();
            error.setMessage(e.getMessage());
            var cause = e.getCauseField();
            var causeName = Map.of("ID", "EditInput.id", "LAST_NAME", "EditInput.lastName", "EMAIL", "EditInput.EditInput2.email", "FIRST_NAME", "EditInput.name").getOrDefault(cause != null ? cause : "", "undefined");
            error.setPath(List.of(("Mutation.editErrorUnion2." + causeName).split("\\.")));
            editErrorsUnion2List.add(error);
        }

        if (editErrorUnion2 == null) {
            var editCustomerResponseUnion2 = new EditCustomerResponseUnion2();
            editCustomerResponseUnion2.setErrors(editErrorsUnion2List);
            return CompletableFuture.completedFuture(editCustomerResponseUnion2);
        }

        var editCustomerResponseUnion2 = transform.editCustomerResponseUnion2ToGraphType(editErrorUnion2, "");

        if (editErrorUnion2 != null && transform.getArguments().contains("errors")) {
            editCustomerResponseUnion2.setErrors(editErrorsUnion2List);
        }

        return CompletableFuture.completedFuture(editCustomerResponseUnion2);
    }
}
