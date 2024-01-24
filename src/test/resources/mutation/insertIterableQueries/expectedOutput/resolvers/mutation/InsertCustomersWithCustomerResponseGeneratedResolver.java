package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomersWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.InsertCustomersWithCustomerResponseMutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.EditResponseWithCustomer;
import fake.graphql.example.package.model.InsertInput;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class InsertCustomersWithCustomerResponseGeneratedResolver implements InsertCustomersWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private InsertCustomersWithCustomerResponseDBQueries insertCustomersWithCustomerResponseDBQueries;

    @Override
    public CompletableFuture<List<EditResponseWithCustomer>> insertCustomersWithCustomerResponse(
            List<InsertInput> input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var select = new SelectionSet(env.getSelectionSet());
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        List<CustomerRecord> inputRecordList = new ArrayList<CustomerRecord>();


        if (input != null) {
            for (int itInputIndex = 0; itInputIndex < input.size(); itInputIndex++) {
                var itInput = input.get(itInputIndex);
                if (itInput == null) continue;
                var inputRecord = new CustomerRecord();
                inputRecord.attach(ctx.configuration());
                if (flatArguments.contains("input/id")) {
                    inputRecord.setId(itInput.getId());
                }
                if (flatArguments.contains("input/customerId")) {
                    inputRecord.setCustomerId(itInput.getCustomerId());
                }
                if (flatArguments.contains("input/firstName")) {
                    inputRecord.setFirstName(itInput.getFirstName());
                }
                if (flatArguments.contains("input/lastName")) {
                    inputRecord.setLastName(itInput.getLastName());
                }
                if (flatArguments.contains("input/storeId")) {
                    inputRecord.setStoreId(itInput.getStoreId());
                }
                if (flatArguments.contains("input/addressId")) {
                    inputRecord.setAddressId(itInput.getAddressId());
                }
                if (flatArguments.contains("input/active")) {
                    inputRecord.setActivebool(itInput.getActive());
                }
                if (flatArguments.contains("input/createdDate")) {
                    inputRecord.setCreateDate(itInput.getCreatedDate());
                }
                inputRecordList.add(inputRecord);
            }
        }

        var rowsUpdated = insertCustomersWithCustomerResponseDBQueries.insertCustomersWithCustomerResponse(ctx, inputRecordList);
        var inputRecordCustomer = getEditResponseWithCustomerCustomer(ctx, inputRecordList, select);

        var editResponseWithCustomerList = new ArrayList<EditResponseWithCustomer>();
        for (var itInputRecordList : inputRecordList) {
            var editResponseWithCustomer = new EditResponseWithCustomer();
            editResponseWithCustomer.setCustomer(inputRecordCustomer.get(itInputRecordList.getId()));
            editResponseWithCustomerList.add(editResponseWithCustomer);
        }

        return CompletableFuture.completedFuture(editResponseWithCustomerList);
    }

    private Map<String, Customer> getEditResponseWithCustomerCustomer(DSLContext ctx,
            List<CustomerRecord> idContainer, SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return Map.of();
        }

        var ids = idContainer.stream().map(it -> it.getId()).collect(Collectors.toSet());
        return customerDBQueries.loadCustomerByIdsAsNode(ctx, ids, select.withPrefix("customer"));
    }
}