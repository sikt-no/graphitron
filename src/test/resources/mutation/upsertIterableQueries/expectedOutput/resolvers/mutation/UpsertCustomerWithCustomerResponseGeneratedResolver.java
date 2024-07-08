package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.UpsertCustomerWithCustomerResponseDBQueries;
import fake.code.generated.queries.query.CustomerDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.UpsertCustomerWithCustomerResponseMutationResolver;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.UpsertInput;
import fake.graphql.example.model.UpsertResponseWithCustomer;
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

public class UpsertCustomerWithCustomerResponseGeneratedResolver implements UpsertCustomerWithCustomerResponseMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<UpsertResponseWithCustomer>> upsertCustomerWithCustomerResponse(
            List<UpsertInput> input, DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.upsertInputToJOOQRecord(input, "input");

        var rowsUpdated = UpsertCustomerWithCustomerResponseDBQueries.upsertCustomerWithCustomerResponse(transform.getCtx(), inputRecordList);
        var inputRecordCustomer = getUpsertResponseWithCustomerCustomer(transform.getCtx(), inputRecordList, transform.getSelect());

        var upsertResponseWithCustomerList = new ArrayList<UpsertResponseWithCustomer>();
        for (var itInputRecordList : inputRecordList) {
            var upsertResponseWithCustomer = new UpsertResponseWithCustomer();
            upsertResponseWithCustomer.setCustomer(inputRecordCustomer.get(itInputRecordList.getId()));
            upsertResponseWithCustomerList.add(upsertResponseWithCustomer);
        }

        return CompletableFuture.completedFuture(upsertResponseWithCustomerList);
    }

    private Map<String, Customer> getUpsertResponseWithCustomerCustomer(DSLContext ctx,
            List<CustomerRecord> idContainer, SelectionSet select) {
        if (!select.contains("customer") || idContainer == null) {
            return Map.of();
        }

        return CustomerDBQueries.loadCustomerByIdsAsNode(ctx, idContainer.stream().map(it -> it.getId()).collect(Collectors.toSet()), select.withPrefix("customer"));
    }
}
