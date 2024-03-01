package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomerIterable2DBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerWithCustomerIterable2MutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.ListedNodeResponse;
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

        var transform = new RecordTransformer(env, ctx);

        var inputRecordList = transform.editInputToJOOQRecord(input, "input");

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

        return customerDBQueries.loadCustomerByIdsAsNode(ctx, idContainer.stream().map(it -> it.getId()).collect(Collectors.toSet()), select.withPrefix("customers"));
    }
}