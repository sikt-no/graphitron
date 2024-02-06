package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.api.EditCustomerWithCustomerMutationResolver;
import fake.graphql.example.model.Customer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerWithCustomerGeneratedResolver implements EditCustomerWithCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<Customer> editCustomerWithCustomer(String id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var select = new SelectionSet(env.getSelectionSet());
        var terminatorResult = testCustomerService.editCustomerWithCustomer(id);
        var customerEditCustomerWithCustomer = getCustomerEditCustomerWithCustomer(ctx, terminatorResult, select);

        return CompletableFuture.completedFuture(customerEditCustomerWithCustomer);
    }

    private Customer getCustomerEditCustomerWithCustomer(DSLContext ctx,
            CustomerRecord idContainer,
            SelectionSet select) {
        if (!select.contains("editCustomerWithCustomer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("editCustomerWithCustomer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}