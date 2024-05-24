package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditErrorUnion1MutationResolver;
import fake.graphql.example.model.EditCustomerResponseUnion1;
import fake.graphql.example.model.EditErrorsUnion1;
import fake.graphql.example.model.SomeErrorA;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.exceptions.TestException;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditErrorUnion1GeneratedResolver implements EditErrorUnion1MutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditCustomerResponseUnion1> editErrorUnion1(String name,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);

        var transform = new RecordTransformer(env, this.ctx);

        EditCustomerResponse1 editErrorUnion1 = null;
        var editErrorsUnion1List = new ArrayList<EditErrorsUnion1>();
        try {
            editErrorUnion1 = testCustomerService.editErrorUnion1(name);
        } catch (TestException e) {
            var error = new SomeErrorA();
            error.setMessage(e.getMessage());
            error.setPath(List.of("editErrorUnion1"));
            editErrorsUnion1List.add(error);
        }

        if (editErrorUnion1 == null) {
            var editCustomerResponseUnion1 = new EditCustomerResponseUnion1();
            editCustomerResponseUnion1.setErrors(editErrorsUnion1List);
            return CompletableFuture.completedFuture(editCustomerResponseUnion1);
        }

        var editCustomerResponseUnion1 = transform.editCustomerResponseUnion1ToGraphType(editErrorUnion1, "");

        if (editErrorUnion1 != null && transform.getSelect().contains("errors")) {
            editCustomerResponseUnion1.setErrors(editErrorsUnion1List);
        }

        return CompletableFuture.completedFuture(editCustomerResponseUnion1);
    }
}