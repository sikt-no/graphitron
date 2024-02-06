package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.EditCustomerMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestCustomerService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerGeneratedResolver implements EditCustomerMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Override
    public CompletableFuture<Customer> editCustomer(EditInput input, String s,
                                                    DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testCustomerService = new TestCustomerService(ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new InputTransformer(env, ctx);

        var inputRecord = transform.editInputToJavaRecord(input, "input");


        var editCustomerResult = testCustomerService.editCustomerWithRecordInputs(inputRecord, s);
        var customerEditCustomer = getCustomerEditCustomer(ctx, editCustomerResult, select);

        return CompletableFuture.completedFuture(customerEditCustomer);
    }

    private Customer getCustomerEditCustomer(DSLContext ctx, CustomerRecord idContainer,
                                             SelectionSet select) {
        if (!select.contains("editCustomer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("editCustomer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}
