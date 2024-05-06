package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerInput2MutationResolver;
import fake.graphql.example.model.EditCustomerResponse2;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.SomeErrorA;
import fake.graphql.example.model.ValidationErrorAndHandledError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.exceptions.TestException;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditCustomerInput2GeneratedResolver implements EditCustomerInput2MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponse2> editCustomerInput2(EditInput input,
                                                                       DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var transform = new RecordTransformer(env, ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input", "input");


        transform.validate();
        EditCustomerResponse1 editCustomerInput2 = null;
        var validationErrorAndHandledErrorList = new ArrayList<ValidationErrorAndHandledError>();
        try {
            editCustomerInput2 = testCustomerService.editCustomerInput2(inputRecord);
        } catch (TestException e) {
            var error = new SomeErrorA();
            error.setMessage(e.getMessage());
            error.setPath(List.of("editCustomerInput2"));
            validationErrorAndHandledErrorList.add(error);
        }

        if (editCustomerInput2 == null) {
            var editCustomerResponse2 = new EditCustomerResponse2();
            editCustomerResponse2.setErrors(validationErrorAndHandledErrorList);
            return CompletableFuture.completedFuture(editCustomerResponse2);
        }


        var editCustomerResponse2 = transform.editCustomerResponse2ToGraphType(editCustomerInput2, "");

        if (editCustomerInput2 != null && transform.getSelect().contains("errors")) {
            editCustomerResponse2.setErrors(validationErrorAndHandledErrorList);
        }


        return CompletableFuture.completedFuture(editCustomerResponse2);
    }
}