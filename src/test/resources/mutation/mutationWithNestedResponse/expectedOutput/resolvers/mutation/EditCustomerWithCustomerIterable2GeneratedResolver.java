package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomerIterable2DBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.graphql.example.package.api.EditCustomerWithCustomerIterable2MutationResolver;
import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.EditInput;
import fake.graphql.example.package.model.ListedNodeResponse;
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

public class EditCustomerWithCustomerIterable2GeneratedResolver implements EditCustomerWithCustomerIterable2MutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private CustomerDBQueries customerDBQueries;

    @Inject
    private EditCustomerWithCustomerIterable2DBQueries editCustomerWithCustomerIterable2DBQueries;

    @Override
    public CompletableFuture<ListedNodeResponse> editCustomerWithCustomerIterable2(
            List<EditInput> input, DataFetchingEnvironment env) throws Exception {
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
                if (flatArguments.contains("input/email")) {
                    inputRecord.setEmail(itInput.getEmail());
                }
                if (flatArguments.contains("input/id")) {
                    inputRecord.setId(itInput.getId());
                }
                if (flatArguments.contains("input/firstName")) {
                    inputRecord.setFirstName(itInput.getFirstName());
                }
                inputRecordList.add(inputRecord);
            }
        }

        var rowsUpdated = editCustomerWithCustomerIterable2DBQueries.editCustomerWithCustomerIterable2(ctx, inputRecordList);
        var inputRecordCustomers = getListedNodeResponseCustomers(ctx, inputRecordList, select);

        var listedNodeResponse = new ListedNodeResponse();

        var customerList = new ArrayList<Customer>();
        for (var itInputRecordList : inputRecordList) {
            customerList.add(inputRecordCustomers.get(itInputRecordList.getId()));
        }
        listedNodeResponse.setCustomers(customerList);

        return CompletableFuture.completedFuture(listedNodeResponse);
    }

    private Map<String, Customer> getListedNodeResponseCustomers(DSLContext ctx,
            List<CustomerRecord> idContainer, SelectionSet select) {
        if (!select.contains("customers") || idContainer == null) {
            return Map.of();
        }

        var ids = idContainer.stream().map(it -> it.getId()).collect(Collectors.toSet());
        return customerDBQueries.loadCustomerByIdsAsNode(ctx, ids, select.withPrefix("customers"));
    }
}