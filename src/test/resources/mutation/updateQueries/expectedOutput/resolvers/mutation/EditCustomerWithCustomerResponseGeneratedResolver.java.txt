package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.EditCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.EditResponseWithCustomer;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditCustomerWithCustomerResponseGeneratedResolver implements EditCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private EditCustomerWithCustomerResponseDBQueries editCustomerWithCustomerResponseDBQueries;

    @Override
    public CompletableFuture<EditResponseWithCustomer> editCustomerWithCustomerResponse(
            EditInput input, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var select = new SelectionSet(env.getSelectionSet());
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inputRecord = new CustomerRecord();
        inputRecord.attach(ctx.configuration());

        if (input != null) {
            if (flatArguments.contains("input/email")) {
                inputRecord.setEmail(input.getEmail());
            }
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
            }
            if (flatArguments.contains("input/firstName")) {
                inputRecord.setFirstName(input.getFirstName());
            }
        }

        var rowsUpdated = editCustomerWithCustomerResponseDBQueries.editCustomerWithCustomerResponse(ctx, inputRecord);
        var inputRecordCustomer = getEditResponseWithCustomerCustomer(ctx, inputRecord, select);

        var editResponseWithCustomer = new EditResponseWithCustomer();
        editResponseWithCustomer.setCustomer(inputRecordCustomer);

        return CompletableFuture.completedFuture(editResponseWithCustomer);
    }

    private Customer getEditResponseWithCustomerCustomer(DSLContext ctx, CustomerRecord idContainer,
            SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}