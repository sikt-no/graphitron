package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.InsertCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.InsertInput;
import fake.graphql.example.package.model.InsertResponseWithCustomer;
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

public class InsertCustomerWithCustomerResponseGeneratedResolver implements InsertCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private InsertCustomerWithCustomerResponseDBQueries insertCustomerWithCustomerResponseDBQueries;

    @Override
    public CompletableFuture<InsertResponseWithCustomer> insertCustomerWithCustomerResponse(
            InsertInput input, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var select = new SelectionSet(env.getSelectionSet());
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inputRecord = new CustomerRecord();
        inputRecord.attach(ctx.configuration());

        if (input != null) {
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
            }
            if (flatArguments.contains("input/customerId")) {
                inputRecord.setCustomerId(input.getCustomerId());
            }
            if (flatArguments.contains("input/firstName")) {
                inputRecord.setFirstName(input.getFirstName());
            }
            if (flatArguments.contains("input/lastName")) {
                inputRecord.setLastName(input.getLastName());
            }
            if (flatArguments.contains("input/storeId")) {
                inputRecord.setStoreId(input.getStoreId());
            }
            if (flatArguments.contains("input/addressId")) {
                inputRecord.setAddressId(input.getAddressId());
            }
            if (flatArguments.contains("input/active")) {
                inputRecord.setActivebool(input.getActive());
            }
            if (flatArguments.contains("input/createdDate")) {
                inputRecord.setCreateDate(input.getCreatedDate());
            }
        }

        var rowsUpdated = insertCustomerWithCustomerResponseDBQueries.insertCustomerWithCustomerResponse(ctx, inputRecord);
        var inputRecordCustomer = getInsertResponseWithCustomerCustomer(ctx, inputRecord, select);

        var insertResponseWithCustomer = new InsertResponseWithCustomer();
        insertResponseWithCustomer.setCustomer(inputRecordCustomer);

        return CompletableFuture.completedFuture(insertResponseWithCustomer);
    }

    private Customer getInsertResponseWithCustomerCustomer(DSLContext ctx,
            CustomerRecord idContainer, SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return null;
        }

        var nodes = customerDBQueries.loadCustomerByIdsAsNode(ctx, Set.of(idContainer.getId()), select.withPrefix("customer"));
        return nodes.values().stream().findFirst().orElse(null);
    }
}