package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.queries.query.PaymentDBQueries;
import fake.graphql.example.package.api.EditCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.EditResponseWithCustomer;
import fake.graphql.example.package.model.Payment;
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
import org.jooq.DSLContext;

public class EditCustomerWithCustomerResponseGeneratedResolver implements EditCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private PaymentDBQueries paymentDBQueries;

    @Override
    public CompletableFuture<EditResponseWithCustomer> editCustomerWithCustomerResponse(String id,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var select = new SelectionSet(env.getSelectionSet());
        var editCustomerWithCustomerResponseResult = testCustomerService.editCustomerWithCustomerResponse(id);

        var editResponseWithCustomerCustomer = getEditResponseWithCustomerCustomer(ctx, editCustomerWithCustomerResponseResult, select);
        var editResponseWithCustomerPayment = getEditResponseWithCustomerPayment(ctx, editCustomerWithCustomerResponseResult, select);

        var editResponseWithCustomer = new EditResponseWithCustomer();
        editResponseWithCustomer.setId(editCustomerWithCustomerResponseResult.getId());
        editResponseWithCustomer.setCustomer(editResponseWithCustomerCustomer);
        editResponseWithCustomer.setPayment(editResponseWithCustomerPayment);

        return CompletableFuture.completedFuture(editResponseWithCustomer);
    }

    private Customer getEditResponseWithCustomerCustomer(DSLContext ctx,
            no.fellesstudentsystem.graphitron.services.TestCustomerService.EditCustomerResponse idContainer,
            SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getC().getId()), select.withPrefix("customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }

    private Payment getEditResponseWithCustomerPayment(DSLContext ctx,
            no.fellesstudentsystem.graphitron.services.TestCustomerService.EditCustomerResponse idContainer,
            SelectionSet select) {
        if (!select.contains("payment") || idContainer == null) {
            return null;
        }

        var nodes = paymentDBQueries.loadPaymentByIdsAsNode(ctx, Set.of(idContainer.getPayment().getId()), select.withPrefix("payment"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}