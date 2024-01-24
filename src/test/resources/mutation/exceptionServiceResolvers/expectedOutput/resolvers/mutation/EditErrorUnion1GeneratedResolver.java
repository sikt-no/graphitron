package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.EditErrorUnion1MutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.EditCustomerResponse2;
import fake.graphql.example.package.model.EditCustomerResponse3;
import fake.graphql.example.package.model.EditCustomerResponseUnion1;
import fake.graphql.example.package.model.EditErrorsUnion1;
import fake.graphql.example.package.model.SomeErrorA;
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
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditErrorUnion1GeneratedResolver implements EditErrorUnion1MutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<EditCustomerResponseUnion1> editErrorUnion1(String name,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var select = new SelectionSet(env.getSelectionSet());
        no.fellesstudentsystem.graphitron.services.TestCustomerService.EditCustomerResponse editErrorUnion1Result = null;
        var editErrorsUnion1List = new ArrayList<EditErrorsUnion1>();
        try {
            editErrorUnion1Result = testCustomerService.editErrorUnion1(name);
        } catch (TestException e) {
            var error = new SomeErrorA();
            error.setMessage(e.getMessage());
            error.setPath(List.of("editErrorUnion1"));
            editErrorsUnion1List.add(error);
        }

        if (editErrorUnion1Result == null) {
            var editCustomerResponseUnion1 = new EditCustomerResponseUnion1();
            editCustomerResponseUnion1.setErrors(editErrorsUnion1List);
            return CompletableFuture.completedFuture(editCustomerResponseUnion1);
        }

        var editCustomerResponse2Result = editErrorUnion1Result.getEditCustomerResponse2();
        var editCustomerResponse3Result = editErrorUnion1Result.getEditCustomerResponse3();

        var editCustomerResponse2Customer = getEditCustomerResponse2Customer(ctx, editCustomerResponse2Result, select);
        var editCustomerResponse3Customer = getEditCustomerResponse3Customer(ctx, editCustomerResponse3Result, select);

        var editCustomerResponseUnion1 = new EditCustomerResponseUnion1();
        editCustomerResponseUnion1.setId(editErrorUnion1Result.getId());

        var editCustomerResponse2 = new EditCustomerResponse2();
        editCustomerResponse2.setId(editCustomerResponse2Result.getId2());
        editCustomerResponse2.setCustomer(editCustomerResponse2Customer);
        editCustomerResponseUnion1.setEditCustomerResponse2(editCustomerResponse2);

        var editCustomerResponse3List = new ArrayList<EditCustomerResponse3>();
        for (var itEditCustomerResponse3Result : editCustomerResponse3Result) {
            var editCustomerResponse3 = new EditCustomerResponse3();
            editCustomerResponse3.setId(itEditCustomerResponse3Result.getId3());
            editCustomerResponse3.setCustomer(editCustomerResponse3Customer.get(editCustomerResponse3.getId()));
            editCustomerResponse3List.add(editCustomerResponse3);
        }
        editCustomerResponseUnion1.setEditCustomerResponse3(editCustomerResponse3List);
        editCustomerResponseUnion1.setErrors(editErrorsUnion1List);

        return CompletableFuture.completedFuture(editCustomerResponseUnion1);
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