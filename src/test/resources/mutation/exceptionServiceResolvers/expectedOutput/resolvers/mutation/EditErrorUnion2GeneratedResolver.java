package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.EditErrorUnion2MutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.EditCustomerResponse2;
import fake.graphql.example.package.model.EditCustomerResponse3;
import fake.graphql.example.package.model.EditCustomerResponseUnion2;
import fake.graphql.example.package.model.EditErrorsUnion2;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.SomeErrorA;
import fake.graphql.example.package.model.SomeErrorB;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.exceptions.TestException;
import no.fellesstudentsystem.graphitron.exceptions.TestExceptionCause;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditErrorUnion2GeneratedResolver implements EditErrorUnion2MutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<EditCustomerResponseUnion2> editErrorUnion2(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var select = new SelectionSet(env.getSelectionSet());
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inputRecord = new CustomerRecord();
        inputRecord.attach(ctx.configuration());
        var edit2Record = new CustomerRecord();
        edit2Record.attach(ctx.configuration());

        if (input != null) {
            var edit2 = input.getEdit2();
            if (edit2 != null) {
                if (flatArguments.contains("input/edit2/email")) {
                    edit2Record.setEmail(edit2.getEmail());
                }
            }
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
            }
            if (flatArguments.contains("input/name")) {
                inputRecord.setFirstName(input.getName());
            }
            if (flatArguments.contains("input/lastName")) {
                inputRecord.setLastName(input.getLastName());
            }
        }

        no.fellesstudentsystem.graphitron.services.TestCustomerService.EditCustomerResponse editErrorUnion2Result = null;
        var editErrorsUnion2List = new ArrayList<EditErrorsUnion2>();
        try {
            editErrorUnion2Result = testCustomerService.editErrorUnion2(inputRecord, edit2Record);
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

        if (editErrorUnion2Result == null) {
            var editCustomerResponseUnion2 = new EditCustomerResponseUnion2();
            editCustomerResponseUnion2.setErrors(editErrorsUnion2List);
            return CompletableFuture.completedFuture(editCustomerResponseUnion2);
        }

        var editCustomerResponse2Result = editErrorUnion2Result.getEditCustomerResponse2();
        var editCustomerResponse3Result = editErrorUnion2Result.getEditCustomerResponse3();

        var editCustomerResponse2Customer = getEditCustomerResponse2Customer(ctx, editCustomerResponse2Result, select);
        var editCustomerResponse3Customer = getEditCustomerResponse3Customer(ctx, editCustomerResponse3Result, select);

        var editCustomerResponseUnion2 = new EditCustomerResponseUnion2();
        editCustomerResponseUnion2.setId(editErrorUnion2Result.getId());

        var editCustomerResponse2 = new EditCustomerResponse2();
        editCustomerResponse2.setId(editCustomerResponse2Result.getId2());
        editCustomerResponse2.setCustomer(editCustomerResponse2Customer);
        editCustomerResponseUnion2.setEditCustomerResponse2(editCustomerResponse2);

        var editCustomerResponse3List = new ArrayList<EditCustomerResponse3>();
        for (var itEditCustomerResponse3Result : editCustomerResponse3Result) {
            var editCustomerResponse3 = new EditCustomerResponse3();
            editCustomerResponse3.setId(itEditCustomerResponse3Result.getId3());
            editCustomerResponse3.setCustomer(editCustomerResponse3Customer.get(editCustomerResponse3.getId()));
            editCustomerResponse3List.add(editCustomerResponse3);
        }
        editCustomerResponseUnion2.setEditCustomerResponse3(editCustomerResponse3List);
        editCustomerResponseUnion2.setErrors(editErrorsUnion2List);

        return CompletableFuture.completedFuture(editCustomerResponseUnion2);
    }

    private Customer getEditCustomerResponse2Customer(DSLContext ctx,
            no.fellesstudentsystem.graphitron.services.TestCustomerService.EditCustomerResponse idContainer,
            SelectionSet select) {
        if (!select.contains("editCustomerResponse2/customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getCustomer().getId()), select.withPrefix("editCustomerResponse2/customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }

    private Map<String, Customer> getEditCustomerResponse3Customer(DSLContext ctx,
            List<no.fellesstudentsystem.graphitron.services.TestCustomerService.EditCustomerResponse> idContainer,
            SelectionSet select) {
        if (!select.contains("editCustomerResponse3/customer") || idContainer == null) {
            return Map.of();
        }

        var ids = idContainer.stream().map(it -> it.getCustomer3().getId()).collect(Collectors.toSet());
        return customerDBQueries.loadCustomerByIdsAsNode(ctx, ids, select.withPrefix("editCustomerResponse3/customer"));
    }
}