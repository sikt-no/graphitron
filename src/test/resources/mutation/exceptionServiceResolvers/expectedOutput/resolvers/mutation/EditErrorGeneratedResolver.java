package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.EditErrorMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditCustomerResponse2;
import fake.graphql.example.model.EditCustomerResponse3;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.SomeErrorB;
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
import no.fellesstudentsystem.graphitron.exceptions.TestExceptionCause;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;

import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditErrorGeneratedResolver implements EditErrorMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<EditCustomerResponse> editError(EditInput input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new InputTransformer(env, ctx);

        var inputRecord = transform.editInputToJOOQRecord(input, "input");
        var edit2Record = new CustomerRecord();
        edit2Record.attach(ctx.configuration());

        if (input != null) {
            var edit2 = input.getEdit2();
            edit2Record = transform.editInput2ToJOOQRecord(edit2, "input/edit2");
        }

        TestCustomerService.EditCustomerResponse editErrorResult = null;
        var someErrorBList = new ArrayList<SomeErrorB>();
        try {
            editErrorResult = testCustomerService.editError(inputRecord, edit2Record);
        } catch (TestExceptionCause e) {
            var error = new SomeErrorB();
            error.setMessage(e.getMessage());
            var cause = e.getCauseField();
            var causeName = Map.of("ID", "EditInput.id", "LAST_NAME", "EditInput.lastName", "EMAIL", "EditInput.EditInput2.email", "FIRST_NAME", "EditInput.name").getOrDefault(cause != null ? cause : "", "undefined");
            error.setPath(List.of(("Mutation.editError." + causeName).split("\\.")));
            someErrorBList.add(error);
        }

        if (editErrorResult == null) {
            var editCustomerResponse = new EditCustomerResponse();
            editCustomerResponse.setErrors(someErrorBList);
            return CompletableFuture.completedFuture(editCustomerResponse);
        }

        var editCustomerResponse2Result = editErrorResult.getEditCustomerResponse2();
        var editCustomerResponse3Result = editErrorResult.getEditCustomerResponse3();

        var editCustomerResponse2Customer = getEditCustomerResponse2Customer(ctx, editCustomerResponse2Result, select);
        var editCustomerResponse3Customer = getEditCustomerResponse3Customer(ctx, editCustomerResponse3Result, select);

        var editCustomerResponse = new EditCustomerResponse();
        editCustomerResponse.setId(editErrorResult.getId());

        var editCustomerResponse2 = new EditCustomerResponse2();
        editCustomerResponse2.setId(editCustomerResponse2Result.getId2());
        editCustomerResponse2.setCustomer(editCustomerResponse2Customer);
        editCustomerResponse.setEditCustomerResponse2(editCustomerResponse2);

        var editCustomerResponse3List = new ArrayList<EditCustomerResponse3>();
        for (var itEditCustomerResponse3Result : editCustomerResponse3Result) {
            var editCustomerResponse3 = new EditCustomerResponse3();
            editCustomerResponse3.setId(itEditCustomerResponse3Result.getId3());
            editCustomerResponse3.setCustomer(editCustomerResponse3Customer.get(editCustomerResponse3.getId()));
            editCustomerResponse3List.add(editCustomerResponse3);
        }
        editCustomerResponse.setEditCustomerResponse3(editCustomerResponse3List);
        editCustomerResponse.setErrors(someErrorBList);

        return CompletableFuture.completedFuture(editCustomerResponse);
    }

    private Customer getEditCustomerResponse2Customer(DSLContext ctx,
            TestCustomerService.EditCustomerResponse idContainer,
            SelectionSet select) {
        if (!select.contains("editCustomerResponse2/customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getCustomer().getId()), select.withPrefix("editCustomerResponse2/customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }

    private Map<String, Customer> getEditCustomerResponse3Customer(DSLContext ctx,
            List<TestCustomerService.EditCustomerResponse> idContainer,
            SelectionSet select) {
        if (!select.contains("editCustomerResponse3/customer") || idContainer == null) {
            return Map.of();
        }

        var ids = idContainer.stream().map(it -> it.getCustomer3().getId()).collect(Collectors.toSet());
        return customerDBQueries.loadCustomerByIdsAsNode(ctx, ids, select.withPrefix("editCustomerResponse3/customer"));
    }
}