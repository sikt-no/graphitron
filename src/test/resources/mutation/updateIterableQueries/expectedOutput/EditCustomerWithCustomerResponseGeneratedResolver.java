package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponseWithCustomer;
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

public class EditCustomerWithCustomerResponseGeneratedResolver implements EditCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<EditResponseWithCustomer>> editCustomerWithCustomerResponse(
            List<EditInput> input, DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.editInputToJOOQRecord(input, "input");

        var rowsUpdated = EditCustomerWithCustomerResponseDBQueries.editCustomerWithCustomerResponse(transform.getCtx(), inputRecordList);
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
