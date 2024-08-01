package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.InsertCustomersWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.InsertCustomersWithCustomerResponseMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditResponseWithCustomer;
import fake.graphql.example.model.InsertInput;
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
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class InsertCustomersWithCustomerResponseGeneratedResolver implements InsertCustomersWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<EditResponseWithCustomer>> insertCustomersWithCustomerResponse(
            List<InsertInput> input, DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.insertInputToJOOQRecord(input, "input");

        var rowsUpdated = InsertCustomersWithCustomerResponseDBQueries.insertCustomersWithCustomerResponse(transform.getCtx(), inputRecordList);
        var inputRecordCustomer = getEditResponseWithCustomerCustomer(transform, inputRecordList, transform.getSelect());

        var editResponseWithCustomerList = new ArrayList<EditResponseWithCustomer>();
        for (var itInputRecordList : inputRecordList) {
            var editResponseWithCustomer = new EditResponseWithCustomer();
            editResponseWithCustomer.setCustomer(inputRecordCustomer.get(itInputRecordList.getId()));
            editResponseWithCustomerList.add(editResponseWithCustomer);
        }

        return CompletableFuture.completedFuture(editResponseWithCustomerList);
    }

    private Map<String, Customer> getEditResponseWithCustomerCustomer(RecordTransformer transform,
            List<CustomerRecord> idContainer, SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return Map.of();
        }

        return CustomerDBQueries.loadCustomerByIdsAsNode(transform.getCtx(), idContainer.stream().map(it -> it.getId()).collect(Collectors.toSet()), select.withPrefix("customer"));
    }
}
