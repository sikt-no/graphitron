package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditErrorListMutationResolver;
import fake.graphql.example.model.EditCustomerResponseList;
import fake.graphql.example.model.EditInput2;
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
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse2;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditErrorListGeneratedResolver implements EditErrorListMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponseList> editErrorList(List<EditInput2> input,
                                                                     DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.editInput2ToJOOQRecord(input, "input");

        List<EditCustomerResponse2> editErrorList = null;
        var someErrorBList = new ArrayList<SomeErrorB>();
        try {
            editErrorList = testCustomerService.editErrorList(inputRecordList);
        } catch (TestExceptionCause e) {
            var error = new SomeErrorB();
            error.setMessage(e.getMessage());
            var cause = e.getCauseField();
            var causeName = Map.of("EMAIL", "EditInput2.email").getOrDefault(cause != null ? cause : "", "undefined");
            error.setPath(List.of(("Mutation.editErrorList." + causeName).split("\\.")));
            someErrorBList.add(error);
        }

        if (editErrorList == null) {
            var editCustomerResponseList = new EditCustomerResponseList();
            editCustomerResponseList.setErrors(someErrorBList);
            return CompletableFuture.completedFuture(editCustomerResponseList);
        }


        var editCustomerResponseList = new EditCustomerResponseList();

        if (editErrorList != null && transform.getSelect().contains("editCustomerResponse2List")) {
            editCustomerResponseList.setEditCustomerResponse2List(transform.editCustomerResponse2ToGraphType(editErrorList, "editCustomerResponse2List"));
        }

        if (editErrorList != null && transform.getSelect().contains("errors")) {
            editCustomerResponseList.setErrors(someErrorBList);
        }


        return CompletableFuture.completedFuture(editCustomerResponseList);
    }
}