package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.DeleteCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.DeleteCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.DeleteInput;
import fake.graphql.example.model.DeleteResponseWithCustomer;
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

public class DeleteCustomerWithCustomerResponseGeneratedResolver implements DeleteCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<DeleteResponseWithCustomer>> deleteCustomerWithCustomerResponse(
            List<DeleteInput> input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var select = new SelectionSet(env.getSelectionSet());

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.deleteInputToJOOQRecord(input, "input");

        var rowsUpdated = DeleteCustomerWithCustomerResponseDBQueries.deleteCustomerWithCustomerResponse(ctx, inputRecordList);
        var inputRecordCustomer = getDeleteResponseWithCustomerCustomer(ctx, inputRecordList, select);

        var deleteResponseWithCustomerList = new ArrayList<DeleteResponseWithCustomer>();
        for (var itInputRecordList : inputRecordList) {
            var deleteResponseWithCustomer = new DeleteResponseWithCustomer();
            deleteResponseWithCustomer.setCustomer(inputRecordCustomer.get(itInputRecordList.getId()));
            deleteResponseWithCustomerList.add(deleteResponseWithCustomer);
        }

        return CompletableFuture.completedFuture(deleteResponseWithCustomerList);
    }

    private Map<String, Customer> getDeleteResponseWithCustomerCustomer(DSLContext ctx,
            List<CustomerRecord> idContainer, SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return Map.of();
        }

        return CustomerDBQueries.loadCustomerByIdsAsNode(ctx, idContainer.stream().map(it -> it.getId()).collect(Collectors.toSet()), select.withPrefix("customer"));
    }
}
